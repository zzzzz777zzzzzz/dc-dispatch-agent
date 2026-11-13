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
import com.ruoyi.system.dispatch.DispatchModels.PlanRow;

/**
 * 撤销刚生成的计划。
 * <p>
 * 只删本会话刚建的那一份，且限 5 分钟——撤销是给「手滑」用的，不是给「删历史」用的。
 * 时间窗与会话校验在编排器里做，这里只负责删。
 *
 * @author ruoyi
 */
public class UndoPlanTool implements AgentTool
{
    private final DispatchDao dao;

    public UndoPlanTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "plan.undo";
    }

    @Override
    public String title()
    {
        return "撤销计划";
    }

    @Override
    public String description()
    {
        return "撤销本会话 5 分钟内刚生成的调度计划";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.WRITE;
    }

    /** 撤销本身就是回退，不再叠一层确认 */
    @Override
    public boolean requiresConfirm()
    {
        return false;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        Object raw = args == null ? null : args.get("planId");
        if (raw == null)
        {
            return ToolResult.fail("这个会话里还没有可撤销的计划。");
        }
        long planId;
        try
        {
            planId = Long.parseLong(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
            return ToolResult.fail("计划编号不对：" + raw);
        }

        PlanRow row = dao.findPlan(planId);
        if (row == null)
        {
            return ToolResult.fail("计划 " + planId + " 不存在，可能已经撤销过了。");
        }
        int deleted = dao.deletePlan(planId);
        if (deleted <= 0)
        {
            return ToolResult.fail("删除没有生效，请到计划页面手工确认。");
        }

        List<String> facts = new ArrayList<>();
        facts.add("已撤销：" + row.planCode());
        facts.add("站点与日期：" + row.dcName() + " / " + row.planDate());
        facts.add("同时删除了该计划的逐点明细（" + row.periodCount() + " 个时段）");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", planId);
        data.put("deleted", deleted);
        return ToolResult.ok("计划已撤销", facts, data);
    }
}
