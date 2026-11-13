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
import com.ruoyi.system.dispatch.DispatchModels.StrategyParams;

/**
 * 查调度策略：当前启用的是哪条、目标函数按什么侧重在算、还有哪些可选。
 *
 * @author ruoyi
 */
public class StrategyListTool implements AgentTool
{
    private final DispatchDao dao;

    public StrategyListTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "strategy.list";
    }

    @Override
    public String title()
    {
        return "查调度策略";
    }

    @Override
    public String description()
    {
        return "列出全部调度策略与当前启用的那条，说明各自的目标函数侧重与参数";
    }

    @Override
    public SideEffect sideEffect()
    {
        return SideEffect.READ;
    }

    @Override
    public ToolResult execute(AgentContext ctx, Map<String, Object> args)
    {
        List<StrategyParams> all = dao.listStrategies();
        if (all == null || all.isEmpty())
        {
            return ToolResult.fail("库里还没有调度策略，请先执行 sql/dispatch_core.sql。");
        }
        StrategyParams active = null;
        for (StrategyParams s : all)
        {
            if (s.status() == 0)
            {
                active = s;
                break;
            }
        }
        List<String> facts = new ArrayList<>();
        if (active == null)
        {
            facts.add("当前没有启用的策略，求解会用默认参数（SOC 55%、消纳率 92%）");
        }
        else
        {
            facts.add("当前启用：" + active.strategyName() + "（" + active.strategyCode() + "）· "
                    + DispatchToolSupport.strategyFocus(active.strategyType()));
            facts.add("这一条的权重：" + DispatchToolSupport.strategyFocusDetail(active.strategyType()));
            facts.add("参数：" + DispatchToolSupport.strategyParams(active));
        }
        for (StrategyParams s : all)
        {
            if (active != null && s.strategyId() == active.strategyId())
            {
                continue;
            }
            facts.add("可选：" + s.strategyName() + "（" + s.strategyCode() + "）· "
                    + DispatchToolSupport.strategyFocus(s.strategyType()) + " · "
                    + DispatchToolSupport.strategyParams(s));
        }
        facts.add("换策略说“换成成本优先的策略”，重算说“重跑今天北区的计划”");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", all.size());
        if (active != null)
        {
            data.put("strategyId", active.strategyId());
            data.put("strategyCode", active.strategyCode());
        }
        return ToolResult.ok(active == null ? "调度策略" : "当前策略：" + active.strategyName(), facts, data);
    }
}
