package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心。启动时装配，运行时只读。
 *
 * @author ruoyi
 */
public class ToolRegistry
{
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<? extends AgentTool> items)
    {
        for (AgentTool t : items)
        {
            AgentTool old = tools.put(t.id(), t);
            if (old != null)
            {
                throw new IllegalStateException("工具 id 重复：" + t.id());
            }
        }
    }

    public AgentTool get(String id)
    {
        return id == null ? null : tools.get(id);
    }

    public AgentTool byAction(Action action)
    {
        return action == null ? null : get(action.toolId());
    }

    public List<AgentTool> all()
    {
        return new ArrayList<>(tools.values());
    }

    public int size()
    {
        return tools.size();
    }
}
