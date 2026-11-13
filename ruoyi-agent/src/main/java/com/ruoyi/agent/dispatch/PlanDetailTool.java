package com.ruoyi.agent.dispatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.agent.core.AgentContext;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.SideEffect;
import com.ruoyi.agent.core.ToolResult;
import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchModels.PlanItem;
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;

/**
 * 查某份计划的 96 点明细，聚合成人能读的几行。
 *
 * @author ruoyi
 */
public class PlanDetailTool implements AgentTool
{
    private final DispatchDao dao;

    public PlanDetailTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "plan.detail";
    }

    @Override
    public String title()
    {
        return "查计划明细";
    }

    @Override
    public String description()
    {
        return "读某份调度计划的逐点明细，汇总 SOC 区间、储能充放、峰值与外购电量";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.READ;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        Long planId = null;
        String code = null;
        java.time.LocalDate asked = null;
        if (args != null)
        {
            Object v = args.get("planId");
            if (v instanceof Number n)
            {
                planId = n.longValue();
            }
            else if (v != null)
            {
                try
                {
                    planId = Long.parseLong(String.valueOf(v).trim());
                }
                catch (NumberFormatException ignored)
                {
                    planId = null;
                }
            }
            code = (String) args.get("dcCode");
            asked = DispatchToolSupport.parseDate(String.valueOf(args.get("date")));
        }

        PlanRow row;
        if (planId != null)
        {
            row = dao.findPlan(planId);
            if (row == null)
            {
                return ToolResult.fail("没有找到编号为 " + planId + " 的计划。");
            }
        }
        else if (asked != null)
        {
            var dc = DispatchToolSupport.resolveDc(dao, code);
            if (dc == null)
            {
                return ToolResult.fail("还没有配置数据中心档案。");
            }
            row = dao.findPlanByDate(dc.dcCode(), asked);
            if (row == null)
            {
                java.time.LocalDate lastPlan = dao.maxPlanDateOf(dc.dcCode());
                String tail = lastPlan == null
                        ? dc.dcName() + " 还没有生成过任何计划。"
                        : "最近一份是 " + lastPlan + "，要不要看那份的明细？";
                return ToolResult.fail(asked + " 没有 " + dc.dcName() + " 的计划。 " + tail);
            }
        }
        else
        {
            var dc = DispatchToolSupport.resolveDc(dao, code);
            if (dc == null)
            {
                return ToolResult.fail("还没有配置数据中心档案。");
            }
            var rows = dao.listPlans(dc.dcCode(), 1);
            if (rows == null || rows.isEmpty())
            {
                return ToolResult.fail(dc.dcName() + " 还没有生成过计划。");
            }
            row = rows.get(0);
        }

        List<PlanItem> items = dao.listItems(row.planId());
        if (items == null || items.isEmpty())
        {
            return ToolResult.fail("计划 " + row.planCode() + " 没有明细数据。");
        }

        double socMin = Double.MAX_VALUE, socMax = -Double.MAX_VALUE;
        double charge = 0, discharge = 0, grid = 0, gas = 0, pvUsed = 0;
        for (PlanItem it : items)
        {
            socMin = Math.min(socMin, it.soc());
            socMax = Math.max(socMax, it.soc());
            charge += it.storageChargeKw();
            discharge += it.storageDischargeKw();
            grid += it.gridKw();
            gas += it.gasKw();
            pvUsed += it.pvUsedKw();
        }
        double dt = 0.25;

        List<String> facts = new ArrayList<>();
        facts.add("计划：" + row.planCode() + "（" + row.dcName() + " / " + row.planDate() + "）");
        facts.add("成本：" + DispatchToolSupport.money(row.optCost())
                + "，基线 " + DispatchToolSupport.money(row.baseCost())
                + "，节约 " + DispatchToolSupport.money(row.costSaving())
                + "（" + DispatchToolSupport.pct(row.costSavingRate()) + "）");
        facts.add("峰值：" + DispatchToolSupport.kw(row.optPeakKw())
                + "，基线 " + DispatchToolSupport.kw(row.basePeakKw()));
        facts.add("SOC 区间：" + DispatchToolSupport.pct(socMin) + " ~ " + DispatchToolSupport.pct(socMax));
        facts.add("储能充电 " + DispatchToolSupport.num(charge * dt, 0) + " kWh，放电 "
                + DispatchToolSupport.num(discharge * dt, 0) + " kWh");
        facts.add("全天购电 " + DispatchToolSupport.num(grid * dt, 0) + " kWh，光伏消纳 "
                + DispatchToolSupport.num(pvUsed * dt, 0) + " kWh，燃机发电 "
                + DispatchToolSupport.num(gas * dt, 0) + " kWh");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", row.planId());
        data.put("planCode", row.planCode());
        data.put("points", items.size());
        return ToolResult.ok("计划明细（" + items.size() + " 点）", facts, data);
    }
}
