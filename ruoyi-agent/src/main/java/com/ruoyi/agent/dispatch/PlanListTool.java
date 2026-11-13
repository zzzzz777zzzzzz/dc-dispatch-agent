package com.ruoyi.agent.dispatch;

import java.time.LocalDate;
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
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;

/**
 * 查计划列表。
 *
 * @author ruoyi
 */
public class PlanListTool implements AgentTool
{
    private final DispatchDao dao;

    public PlanListTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "plan.list";
    }

    @Override
    public String title()
    {
        return "查计划列表";
    }

    @Override
    public String description()
    {
        return "列出某个数据中心最近若干份调度计划，含成本与相对基线的降幅";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.READ;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        String code = args == null ? null : (String) args.get("dcCode");
        int limit = 5;
        if (args != null && args.get("limit") instanceof Number n)
        {
            limit = Math.max(1, Math.min(20, n.intValue()));
        }
        LocalDate asked = args == null ? null
                : DispatchToolSupport.parseDate(String.valueOf(args.get("date")));
        DcInfo dc = DispatchToolSupport.resolveDc(dao, code);
        if (dc == null)
        {
            return ToolResult.fail("还没有配置数据中心档案。");
        }
        List<PlanRow> rows;
        List<String> facts = new ArrayList<>();
        String headline;
        if (asked != null)
        {
            PlanRow one = dao.findPlanByDate(dc.dcCode(), asked);
            if (one == null)
            {
                LocalDate lastPlan = dao.maxPlanDateOf(dc.dcCode());
                String tail = lastPlan == null
                        ? dc.dcName() + " 还没有生成过任何计划。"
                        : "最近一份是 " + lastPlan + "。要说“重跑 " + asked + " 的计划”，我先生成一份。";
                return ToolResult.fail(asked + " 没有 " + dc.dcName() + " 的调度计划。" + tail);
            }
            rows = List.of(one);
            headline = dc.dcName() + " · " + asked + " 的计划";
        }
        else
        {
            rows = dao.listPlans(dc.dcCode(), limit);
            if (rows == null || rows.isEmpty())
            {
                return ToolResult.fail(dc.dcName() + " 还没有生成过调度计划，可以先说“重跑明天的计划”。");
            }
            headline = dc.dcName() + " 最近 " + rows.size() + " 份计划";
        }
        for (PlanRow r : rows)
        {
            facts.add(DispatchToolSupport.md(r.planDate())
                    + "　" + r.planCode()
                    + "　成本 " + DispatchToolSupport.money(r.optCost())
                    + "　较基线 " + DispatchToolSupport.pct(r.costSavingRate())
                    + "　峰值 " + DispatchToolSupport.kw(r.optPeakKw()));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        if (asked != null)
        {
            data.put("date", asked.toString());
        }
        return ToolResult.ok(headline, facts, data);
    }
}
