package com.ruoyi.agent.core;

/**
 * 一次运行的上下文。对应 LangGraph 里 thread 的 state。
 *
 * @author ruoyi
 */
public final class AgentContext
{
    private final String runId;
    private final String sessionId;
    private final String user;

    public AgentContext(String runId, String sessionId, String user)
    {
        this.runId = runId;
        this.sessionId = sessionId;
        this.user = user;
    }

    public String runId()
    {
        return runId;
    }

    public String sessionId()
    {
        return sessionId;
    }

    public String user()
    {
        return user;
    }
}
