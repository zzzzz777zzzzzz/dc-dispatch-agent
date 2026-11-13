package com.ruoyi.agent.core;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图解析器。
 * <p>
 * 它不是「降级方案」那么简单：调度语言高度模板化（站名 + 日期 + 参数 + 动作），
 * 规则在常见句式上比大模型更稳、更快、更可解释。大模型负责的是规则覆盖不到的表述。
 * <p>
 * 带上一轮意图后，能处理省略式追问：「那南区呢」「昨天呢」「再跑一遍」。
 *
 * @author ruoyi
 */
public class RuleIntentParser implements IntentParser
{
    private static final String[][] DC_WORDS = {
            { "北区", "DC-A" }, { "南区", "DC-B" }, { "东区", "DC-C" }, { "西区", "DC-D" }
    };
    private static final Pattern DC_LETTER = Pattern.compile("([ABCDabcd])\\s*区");
    private static final Pattern DC_CODE = Pattern.compile("(?i)dc[-_ ]?([abcd])");
    private static final Pattern PLAN_ID = Pattern.compile("(?:计划|方案)\\s*[#＃第]?\\s*(\\d{1,6})"
            + "|(\\d{1,6})\\s*号\\s*(?:计划|方案)");
    private static final Pattern LIMIT = Pattern.compile("(?:最近|最后|近)\\s*(\\d{1,2})\\s*份");
    /** 改参数：改到 20% / 调到 15% / 设成 30% / 改回 65% */
    private static final Pattern SOC_TARGET = Pattern.compile(
            "(?:改到|改成|调到|调成|设为|设成|设置为|换到|换成|调整到|调整成|改回|调回)\\s*(\\d{1,3})\\s*%?");
    /** 策略编号：STR001 / str-3 */
    private static final Pattern STRATEGY_CODE = Pattern.compile("(?i)str\\s*-?\\s*(\\d{1,3})");

    /** 短句才允许继承上下文，避免长句被误判成追问 */
    private static final int FOLLOW_UP_MAX_LEN = 16;

    private final Supplier<LocalDate> clock;

    public RuleIntentParser()
    {
        this(LocalDate::now);
    }

    public RuleIntentParser(Supplier<LocalDate> clock)
    {
        this.clock = clock;
    }

    @Override
    public String name()
    {
        return "rule";
    }

    @Override
    public boolean available()
    {
        return true;
    }

    @Override
    public Intent parse(String text, Intent previous)
    {
        if (text == null || text.isBlank())
        {
            return null;
        }
        String t = text.trim();
        Action action = detectAction(t);
        String dc = detectDc(t);
        LocalDate date = Dates.resolve(t, clock.get());

        // 省略式追问：没有动作也没有站点，但上一轮有——沿用动作
        boolean shortUtterance = t.length() <= FOLLOW_UP_MAX_LEN;
        boolean hasPrev = previous != null && previous.action() != Action.UNKNOWN;
        boolean inheritedAction = false;
        if (action == Action.UNKNOWN && hasPrev && shortUtterance && (dc != null || date != null))
        {
            action = previous.action();
            inheritedAction = true;
        }
        boolean sameAction = hasPrev && action == previous.action() && shortUtterance;

        Map<String, Object> slots = new LinkedHashMap<>();
        boolean inheritedDc = false;
        boolean inheritedDate = false;
        if (dc == null && sameAction && previous.dcCode() != null)
        {
            dc = previous.dcCode();
            inheritedDc = true;
        }
        if (date == null && sameAction && previous.date() != null)
        {
            try
            {
                date = LocalDate.parse(previous.date());
                inheritedDate = true;
            }
            catch (RuntimeException ignored)
            {
                // 上一轮日期不合法就不继承
            }
        }

        if (dc != null)
        {
            slots.put("dcCode", dc);
        }
        if (date != null)
        {
            slots.put("date", Dates.iso(date));
        }
        Long planId = detectPlanId(t);
        if (planId != null)
        {
            slots.put("planId", planId);
        }
        Integer limit = detectLimit(t);
        if (limit != null)
        {
            slots.put("limit", limit);
        }
        if (action == Action.UPDATE_STRATEGY)
        {
            Double target = detectSocTarget(t);
            if (target != null)
            {
                slots.put("socTarget", target);
            }
        }
        if (action == Action.SELECT_STRATEGY)
        {
            Matcher code = STRATEGY_CODE.matcher(t);
            if (code.find())
            {
                slots.put("strategyCode", String.format("STR%03d", Integer.parseInt(code.group(1))));
            }
            // 原话整句带下去，工具用「名称包含」和「成本/消纳/削峰」两套规则去认
            slots.put("strategyName", t);
        }

        double confidence;
        if (action == Action.UNKNOWN)
        {
            confidence = 0.25;
        }
        else if (dc != null || action == Action.HELP || action == Action.CONFIRM)
        {
            confidence = 0.92;
        }
        else
        {
            confidence = 0.78;
        }

        // 假设要挂到最终返回的对象上，不能挂在中间变量上
        Intent intent = new Intent(action, slots, confidence, name());
        if (inheritedAction)
        {
            intent.withAssumption("沿用上一轮的动作：" + action.label());
        }
        if (inheritedDc)
        {
            intent.withAssumption("沿用上一轮的站点：" + dc);
        }
        if (inheritedDate)
        {
            intent.withAssumption("沿用上一轮的日期：" + Dates.iso(date));
        }
        return intent;
    }

    private Action detectAction(String t)
    {
        if (containsAny(t, "你是谁", "你叫什么", "你是什么", "介绍一下你", "你是哪个", "你是人", "你是机器"))
        {
            return Action.SMALLTALK;
        }
        if (containsAny(t, "你能做什么", "你会什么", "能干什么", "帮助", "怎么用", "help"))
        {
            return Action.HELP;
        }
        if (containsAny(t, "撤销", "撤回", "撤掉", "undo", "回退"))
        {
            return Action.UNDO;
        }
        if (containsAny(t, "取消", "算了", "不用了", "别执行"))
        {
            return Action.CANCEL;
        }
        if (t.length() <= 6 && containsAny(t, "确认", "确定", "执行吧", "同意", "可以", "好的", "好"))
        {
            return Action.CONFIRM;
        }
        if (SOC_TARGET.matcher(t).find())
        {
            return Action.UPDATE_STRATEGY;
        }
        if (isStrategySwitch(t))
        {
            return Action.SELECT_STRATEGY;
        }
        if (t.contains("策略") && containsAny(t, "现在", "当前", "哪条", "哪个", "什么", "有哪", "列一下", "几条"))
        {
            return Action.QUERY_STRATEGY;
        }
        if (containsAny(t, "重跑", "重新算", "重新求解", "重算", "再算", "生成计划", "求解", "跑一遍", "出一版", "再来一版"))
        {
            return Action.SOLVE_PLAN;
        }
        if (containsAny(t, "为什么", "原因", "解释", "怎么回事", "凭什么"))
        {
            return Action.EXPLAIN;
        }
        if (containsAny(t, "省了多少", "省多少", "省钱", "成本", "收益", "降幅",
                "复盘", "账单", "花了多少", "节约"))
        {
            return Action.QUERY_REPORT;
        }
        if (containsAny(t, "明细", "详情", "逐点", "96 点", "96点"))
        {
            return Action.QUERY_PLAN_DETAIL;
        }
        if (containsAny(t, "状态", "现在", "当前", "负荷", "出力", "电价", "soc", "SOC", "健康"))
        {
            return Action.QUERY_STATUS;
        }
        if (containsAny(t, "计划", "方案", "排产"))
        {
            return Action.QUERY_PLAN;
        }
        return Action.UNKNOWN;
    }

    /**
     * 是不是在说「换策略」。
     * <p>
     * 必须同时出现「策略」和换的动作词：只凭「换成」会误伤「把 SOC 换成 20%」，
     * 只凭「策略」会把「策略现在是什么」也当成切换。
     */
    private boolean isStrategySwitch(String t)
    {
        if (!t.contains("策略"))
        {
            return false;
        }
        return containsAny(t, "切换", "换成", "换到", "换回", "改用", "启用", "切到", "设为", "设成");
    }

    private String detectDc(String t)
    {
        for (String[] pair : DC_WORDS)
        {
            if (t.contains(pair[0]))
            {
                return pair[1];
            }
        }
        Matcher m = DC_CODE.matcher(t);
        if (m.find())
        {
            return "DC-" + m.group(1).toUpperCase();
        }
        m = DC_LETTER.matcher(t);
        if (m.find())
        {
            return "DC-" + m.group(1).toUpperCase();
        }
        return null;
    }

    private Long detectPlanId(String t)
    {
        Matcher m = PLAN_ID.matcher(t);
        if (m.find())
        {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            try
            {
                return Long.parseLong(g);
            }
            catch (NumberFormatException ignored)
            {
                return null;
            }
        }
        return null;
    }

    private Integer detectLimit(String t)
    {
        Matcher m = LIMIT.matcher(t);
        if (m.find())
        {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private Double detectSocTarget(String t)
    {
        Matcher m = SOC_TARGET.matcher(t);
        if (m.find())
        {
            return Double.parseDouble(m.group(1));
        }
        return null;
    }

    private boolean containsAny(String t, String... words)
    {
        for (String w : words)
        {
            if (t.contains(w))
            {
                return true;
            }
        }
        return false;
    }
}
