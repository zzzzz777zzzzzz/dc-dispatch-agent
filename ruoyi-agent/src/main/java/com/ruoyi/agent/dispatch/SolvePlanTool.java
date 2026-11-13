package com.ruoyi.agent.dispatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.agent.core.AgentContext;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.SideEffect;
import com.ruoyi.agent.core.ToolResult;
import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchModels.DcInfo;
import com.ruoyi.system.dispatch.DispatchModels.LoadPoint;
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;
import com.ruoyi.system.dispatch.DispatchPlanService;

/**
 * 重跑计划：求解并落库。唯一的写动作，必须过二次确认。
 * <p>
 * 日期不将就：用户说了哪天就只解哪天。当天凑不齐 96 点就直接说明并给出下一步，
 * 不悄悄换成最近一天——「我问今天，你给我上周」是最伤信任的一种"聪明"。
 *
 * @author ruoyi
 */
public class SolvePlanTool implements AgentTool
{
    private final DispatchDao dao;
    private final DispatchPlanService planService;

    public SolvePlanTool(DispatchDao dao, DispatchPlanService planService)
    {
        this.dao = dao;
        this.planService = planService;
    }

    @Override
    public String id()
    {
        return "plan.solve";
    }

    @Override
    public String title()
    {
        return "重跑计划";
    }

    @Override
    public String description()
    {
        return "按指定站点与日期重新求解调度计划并落库，会新增一份计划";
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
        String code = args == null ? null : (String) args.get("dcCode");
        LocalDate asked = args == null ? null
                : DispatchToolSupport.parseDate(String.valueOf(args.get("date")));
        DcInfo dc = DispatchToolSupport.resolveDc(dao, code);
        if (dc == null)
        {
            return ToolResult.fail("还没有配置数据中心档案。");
        }
        LocalDate latest = dao.maxHistoryDate();
        if (asked == null && latest == null)
        {
            return ToolResult.fail("历史表是空的，先导入数据或生成演示数据。");
        }
        LocalDate date = asked != null ? asked : latest;

        List<LoadPoint> day = dao.historyOfDay(dc.dcCode(), date);
        if (day.size() < 96)
        {
            StringBuilder msg = new StringBuilder();
            msg.append(date).append(" 只有 ").append(day.size())
                    .append(" 个采样点，凑不齐 96 点没法求解；库里遥测最新到 ").append(latest).append("。");
            if (asked != null && asked.isAfter(LocalDate.now()))
            {
                msg.append("未来的日期不会有遥测，等那天到了再跑。");
            }
            else
            {
                msg.append("要补数据就点首页的「一键生成演示数据」；想看最近一份计划，可以说“看看最近 5 份计划”。");
            }
            return ToolResult.fail(msg.toString());
        }

        // 幂等：同一站、同一天、同一策略、10 分钟内已经解过，就直接复用，不再插一份。
        // 换了策略就不该复用——否则「切换策略后重跑」看起来毫无变化，实际是拿到了旧计划。
        PlanRow recent = findRecentSameDay(dc.dcCode(), date);
        if (recent != null)
        {
            List<String> facts = new ArrayList<>();
            facts.add("计划：" + recent.planCode() + "（" + recent.dcName() + " / " + recent.planDate() + "）");
            facts.add("成本：" + DispatchToolSupport.money(recent.optCost())
                    + "，基线 " + DispatchToolSupport.money(recent.baseCost())
                    + "，节约 " + DispatchToolSupport.money(recent.costSaving())
                    + "（" + DispatchToolSupport.pct(recent.costSavingRate()) + "）");
            facts.add("提示：这份计划 10 分钟内刚生成过，直接复用，没有重复求解");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", recent.planId());
            data.put("planCode", recent.planCode());
            data.put("date", recent.planDate().toString());
            data.put("reused", true);
            return ToolResult.ok("计划已存在，直接复用", facts, data);
        }

        long begin = System.currentTimeMillis();
        PlanRow row;
        try
        {
            row = planService.generate(dc.dcCode(), null, date);
        }
        catch (RuntimeException e)
        {
            return ToolResult.fail("求解失败：" + e.getMessage());
        }
        long cost = System.currentTimeMillis() - begin;

        List<String> facts = new ArrayList<>();
        com.ruoyi.system.dispatch.DispatchModels.StrategyParams strategy = dao.findStrategy(null);
        if (strategy != null)
        {
            facts.add("策略：" + strategy.strategyName() + "（" + strategy.strategyCode() + "）· "
                    + DispatchToolSupport.strategyFocus(strategy.strategyType()));
        }
        facts.add("计划：" + row.planCode() + "（" + row.dcName() + " / " + row.planDate() + "）");
        facts.add("成本：" + DispatchToolSupport.money(row.optCost())
                + "，基线 " + DispatchToolSupport.money(row.baseCost())
                + "，节约 " + DispatchToolSupport.money(row.costSaving())
                + "（" + DispatchToolSupport.pct(row.costSavingRate()) + "）");
        facts.add("峰值：" + DispatchToolSupport.kw(row.optPeakKw())
                + "，较基线降低 " + DispatchToolSupport.pct(
                        row.basePeakKw() == null || row.basePeakKw() == 0 ? null
                                : 1 - row.optPeakKw() / row.basePeakKw()));
        facts.add("求解：" + row.solver() + "，耗时 " + cost + " ms，共 " + row.periodCount() + " 个时段");
        facts.add("提示：5 分钟内可以说“撤销”，把这份计划删掉");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", row.planId());
        data.put("planCode", row.planCode());
        data.put("date", row.planDate().toString());
        return ToolResult.ok("计划已生成", facts, data);
    }

    /** 同一站、同一天、同一策略、10 分钟内生成的计划，用于幂等判重 */
    private PlanRow findRecentSameDay(String dcCode, LocalDate date)
    {
        List<PlanRow> rows = dao.listPlans(dcCode, 20);
        if (rows == null || rows.isEmpty())
        {
            return null;
        }
        var strategy = dao.findStrategy(null);
        Long activeId = strategy == null ? null : strategy.strategyId();
        String signature = DispatchPlanService.strategySignature(strategy);
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        PlanRow best = null;
        for (PlanRow r : rows)
        {
            if (!date.equals(r.planDate()) || r.createTime() == null)
            {
                continue;
            }
            // 策略或参数变了就不能算同一份计划
            if (!java.util.Objects.equals(activeId, r.strategyId()))
            {
                continue;
            }
            if (r.remark() == null || !r.remark().contains(signature))
            {
                continue;
            }
            if (r.createTime().isBefore(threshold))
            {
                continue;
            }
            if (best == null || r.createTime().isAfter(best.createTime()))
            {
                best = r;
            }
        }
        return best;
    }
}
