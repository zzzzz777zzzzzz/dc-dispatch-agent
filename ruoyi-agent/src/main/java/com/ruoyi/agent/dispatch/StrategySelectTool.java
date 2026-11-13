package com.ruoyi.agent.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ruoyi.agent.core.AgentContext;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.SideEffect;
import com.ruoyi.agent.core.ToolResult;
import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchModels.StrategyParams;

/**
 * 切换启用的调度策略。
 * <p>
 * 「换成成本优先的策略」这句话落到库里就是一次启用状态的切换：把目标策略置为启用、其余停用。
 * 求解只认启用中的第一条，所以这里必须是单选——切换之后下一次求解才生效，不会动已经算好的计划。
 *
 * @author ruoyi
 */
public class StrategySelectTool implements AgentTool
{
    private final DispatchDao dao;

    public StrategySelectTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "strategy.select";
    }

    @Override
    public String title()
    {
        return "切换调度策略";
    }

    @Override
    public String description()
    {
        return "把启用的调度策略换成指定的那一条（成本优先 / 消纳优先 / 削峰优先），下次求解生效";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.WRITE;
    }

    @Override
    public boolean requiresConfirm()
    {
        return true;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        List<StrategyParams> all = dao.listStrategies();
        if (all == null || all.isEmpty())
        {
            return ToolResult.fail("库里还没有调度策略，请先执行 sql/dispatch_core.sql。");
        }
        String code = args == null ? null : text(args.get("strategyCode"));
        String name = args == null ? null : text(args.get("strategyName"));
        StrategyParams target = resolve(all, code, name);
        if (target == null)
        {
            return ToolResult.fail("没认出要切到哪条策略。" + describe(all));
        }

        StrategyParams current = null;
        for (StrategyParams s : all)
        {
            if (s.status() == 0)
            {
                current = s;
                break;
            }
        }
        if (current != null && current.strategyId() == target.strategyId())
        {
            List<String> facts = new ArrayList<>();
            facts.add("当前启用的就是：" + label(target));
            facts.add("侧重：" + DispatchToolSupport.strategyFocus(target.strategyType()));
            facts.add("参数：" + DispatchToolSupport.strategyParams(target));
            facts.add("要立刻看到效果，可以说“重跑今天北区的计划”。");
            return ToolResult.ok("已经是这条策略", facts, data(target, false));
        }

        dao.activateStrategy(target.strategyId());

        List<String> facts = new ArrayList<>();
        facts.add("调度策略：" + (current == null ? "（无）" : label(current)) + " → " + label(target));
        facts.add("侧重：" + DispatchToolSupport.strategyFocus(target.strategyType())
                + "；" + DispatchToolSupport.strategyFocusDetail(target.strategyType()));
        facts.add("参数：" + DispatchToolSupport.strategyParams(target));
        facts.add("生效时机：下一次求解。已经算好的计划不会变，要重算就说“重跑今天北区的计划”");
        if (current != null)
        {
            facts.add("要改回去，直接说“换回 " + current.strategyName() + "”");
        }
        return ToolResult.ok("策略已切换", facts, data(target, true));
    }

    private Map<String, Object> data(StrategyParams s, boolean switched)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategyId", s.strategyId());
        data.put("strategyCode", s.strategyCode());
        data.put("strategyType", s.strategyType());
        data.put("switched", switched);
        return data;
    }

    /**
     * 解析目标策略：先按编号，再按名称包含，最后按口语说法（成本/消纳/削峰）。
     */
    private StrategyParams resolve(List<StrategyParams> all, String code, String name)
    {
        if (code != null && !code.isBlank())
        {
            for (StrategyParams s : all)
            {
                if (code.trim().equalsIgnoreCase(s.strategyCode()))
                {
                    return s;
                }
            }
        }
        String keyword = name != null && !name.isBlank() ? name : code;
        if (keyword == null || keyword.isBlank())
        {
            return null;
        }
        for (StrategyParams s : all)
        {
            if (s.strategyName() != null && s.strategyName().contains(keyword))
            {
                return s;
            }
        }
        String type = typeOf(keyword);
        if (type != null)
        {
            for (StrategyParams s : all)
            {
                if (type.equals(s.strategyType()))
                {
                    return s;
                }
            }
        }
        return null;
    }

    /** 口语说法 → 策略类型 */
    private String typeOf(String keyword)
    {
        String k = keyword.toUpperCase(Locale.ROOT);
        if (k.contains("成本") || k.contains("经济") || k.contains("省钱") || k.contains("最省"))
        {
            return "COMPREHENSIVE_DISPATCH";
        }
        if (k.contains("消纳") || k.contains("新能源") || k.contains("绿电") || k.contains("光伏"))
        {
            return "MICROGRID_DISPATCH";
        }
        if (k.contains("削峰") || k.contains("峰值") || k.contains("负载") || k.contains("均衡"))
        {
            return "LOAD_DISPATCH";
        }
        return null;
    }

    private String describe(List<StrategyParams> all)
    {
        StringBuilder sb = new StringBuilder("现在可选：");
        for (StrategyParams s : all)
        {
            sb.append("\n· ").append(label(s)).append("——")
                    .append(DispatchToolSupport.strategyFocus(s.strategyType()));
        }
        return sb.toString();
    }

    private String label(StrategyParams s)
    {
        return s.strategyName() + "（" + s.strategyCode() + "）";
    }

    private String text(Object v)
    {
        return v == null ? null : String.valueOf(v);
    }
}
