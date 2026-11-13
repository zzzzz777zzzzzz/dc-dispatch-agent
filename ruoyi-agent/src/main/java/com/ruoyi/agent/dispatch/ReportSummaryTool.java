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
 * 某天的复盘：成本、基线、节约、碳排、消纳。
 *
 * @author ruoyi
 */
public class ReportSummaryTool implements AgentTool
{
    private final DispatchDao dao;

    public ReportSummaryTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "report.summary";
    }

    @Override
    public String title()
    {
        return "查复盘数据";
    }

    @Override
    public String description()
    {
        return "对比某天计划与基线的成本、碳排与新能源消纳率";
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
        LocalDate date = args == null ? null : DispatchToolSupport.parseDate((String) args.get("date"));
        DcInfo dc = DispatchToolSupport.resolveDc(dao, code);
        if (dc == null)
        {
            return ToolResult.fail("还没有配置数据中心档案。");
        }
        if (date == null)
        {
            date = dao.maxHistoryDate();
        }
        if (date == null)
        {
            return ToolResult.fail("历史表是空的，没有可复盘的日期。");
        }

        List<PlanRow> rows = dao.listPlans(dc.dcCode(), 100);
        PlanRow hit = null;
        String note = null;
        if (rows != null)
        {
            for (PlanRow r : rows)
            {
                if (date.equals(r.planDate()))
                {
                    hit = r;
                    break;
                }
            }
            if (hit == null && !rows.isEmpty())
            {
                PlanRow latest = rows.get(0);
                note = date + " 没有计划，最近一份是 " + latest.planDate() + "（" + latest.planCode() + "）";
                hit = latest;
                date = latest.planDate();
            }
        }
        if (hit == null)
        {
            return ToolResult.fail(dc.dcName() + " 还没有生成过计划，没法复盘。");
        }

        List<String> facts = new ArrayList<>();
        facts.add("日期：" + date + "（" + dc.dcName() + "）");
        facts.add("购电成本：" + DispatchToolSupport.money(hit.optCost())
                + "，基线 " + DispatchToolSupport.money(hit.baseCost()));
        facts.add("节约：" + DispatchToolSupport.money(hit.costSaving())
                + "，降幅 " + DispatchToolSupport.pct(hit.costSavingRate()));
        facts.add("碳排：" + DispatchToolSupport.num(hit.optEmission(), 0) + " kg，基线 "
                + DispatchToolSupport.num(hit.baseEmission(), 0) + " kg");
        facts.add("新能源消纳率：" + DispatchToolSupport.pct(hit.optConsumptionRate())
                + "，基线 " + DispatchToolSupport.pct(hit.baseConsumptionRate()));
        facts.add("峰值：" + DispatchToolSupport.kw(hit.optPeakKw())
                + "，基线 " + DispatchToolSupport.kw(hit.basePeakKw()));
        if (note != null)
        {
            facts.add("提示：" + note);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planCode", hit.planCode());
        data.put("date", date.toString());
        data.put("saving", hit.costSaving());
        data.put("savingRate", hit.costSavingRate());
        return ToolResult.ok(date + " 复盘（" + dc.dcName() + "）", facts, data);
    }
}
