package com.ruoyi.agent.core;

/**
 * 证据引用。回复里出现的每组数字，背后必须有一个 citation。
 *
 * @author ruoyi
 */
public final class Citation
{
    private final String callId;
    private final String toolId;
    private final String title;

    public Citation(String callId, String toolId, String title)
    {
        this.callId = callId;
        this.toolId = toolId;
        this.title = title;
    }

    public String callId()
    {
        return callId;
    }

    public String toolId()
    {
        return toolId;
    }

    public String title()
    {
        return title;
    }

    public String label()
    {
        return toolId + " #" + callId;
    }
}
