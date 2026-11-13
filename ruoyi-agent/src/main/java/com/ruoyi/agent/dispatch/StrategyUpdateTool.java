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
 * 改策略参数：目前只开放储能 SOC 目标这一项。
 * <p>
 * 目标是百分比（0–100），数据库里存的也是百分比，读出来时 DAO 会除以 100。
 * 只改一个字段，所以不做撤销栈——把原值写在回执里，说「改回 65%」即可。
 *
 * @author ruoyi
 */
public class StrategyUpdateTool implements AgentTool
{
    private static final double MIN = 5;
    private static final double MAX = 95;

    private final DispatchDao dao;

    public StrategyUpdateTool(DispatchDao dao)
    {
        this.dao = dao;
    }

    @Override
    public String id()
    {
        return "strategy.update";
    }

    @Override
    public String title()
    {
        return "改策略参数";
    }

    @Override
    public String description()
    {
        return "修改当前调度策略的储能 SOC 目标（百分比），下次求解生效";
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
        Double percent = null;
        if (args != null && args.get("socTarget") instanceof Number n)
        {
            percent = n.doubleValue();
        }
        if (percent == null)
        {
            return ToolResult.fail("没听清要改成多少。可以说：把储能余量改到 20%。");
        }
        if (percent < MIN || percent > MAX)
        {
            return ToolResult.fail("储能 SOC 目标要在 " + (int) MIN + "%–" + (int) MAX + "% 之间，"
                    + percent + "% 不合理。");
        }

        StrategyParams strategy = dao.findStrategy(null);
        if (strategy == null)
        {
            return ToolResult.fail("没有启用中的调度策略，请先到策略页面启用一条。");
        }
        double oldPercent = strategy.socTarget() * 100;
        int updated = dao.updateStrategySocTarget(strategy.strategyId(), percent);
        if (updated <= 0)
        {
            return ToolResult.fail("更新没有生效，请到策略页面手工确认。");
        }

        List<String> facts = new ArrayList<>();
        facts.add("策略：" + strategy.strategyName() + "（" + strategy.strategyCode() + "）");
        facts.add("储能 SOC 目标：" + fmt(oldPercent) + "% → " + fmt(percent) + "%");
        facts.add("生效时机：下一次求解时使用");
        facts.add("要改回去，直接说“改回 " + fmt(oldPercent) + "%”");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategyId", strategy.strategyId());
        data.put("oldPercent", oldPercent);
        data.put("newPercent", percent);
        return ToolResult.ok("策略已更新", facts, data);
    }

    private String fmt(double v)
    {
        return String.format(Locale.CHINA, "%.0f", v);
    }
}
