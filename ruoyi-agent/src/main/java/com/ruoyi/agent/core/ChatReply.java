package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次对话回复。
 *
 * @author ruoyi
 */
public class ChatReply
{
    private String runId;
    private String phase;
    private String reply;
    private List<Citation> citations = new ArrayList<>();
    private boolean needConfirm;
    private String confirmToken;
    private Map<String, Object> card;
    private String parser;
    /** 正文由谁写的：template（工具模板）/ llm（模型改写） */
    private String composedBy;
    private long latencyMs;
    private int tokens;
    private List<String> assumptions = new ArrayList<>();

    public static ChatReply of(String runId, String phase, String reply)
    {
        ChatReply r = new ChatReply();
        r.runId = runId;
        r.phase = phase;
        r.reply = reply;
        return r;
    }

    public String getRunId()
    {
        return runId;
    }

    public void setRunId(String runId)
    {
        this.runId = runId;
    }

    public String getPhase()
    {
        return phase;
    }

    public void setPhase(String phase)
    {
        this.phase = phase;
    }

    public String getReply()
    {
        return reply;
    }

    public void setReply(String reply)
    {
        this.reply = reply;
    }

    public List<Citation> getCitations()
    {
        return citations;
    }

    public void setCitations(List<Citation> citations)
    {
        this.citations = citations;
    }

    public boolean isNeedConfirm()
    {
        return needConfirm;
    }

    public void setNeedConfirm(boolean needConfirm)
    {
        this.needConfirm = needConfirm;
    }

    public String getConfirmToken()
    {
        return confirmToken;
    }

    public void setConfirmToken(String confirmToken)
    {
        this.confirmToken = confirmToken;
    }

    public Map<String, Object> getCard()
    {
        return card;
    }

    public void setCard(Map<String, Object> card)
    {
        this.card = card;
    }

    public String getParser()
    {
        return parser;
    }

    public void setParser(String parser)
    {
        this.parser = parser;
    }

    public String getComposedBy()
    {
        return composedBy;
    }

    public void setComposedBy(String composedBy)
    {
        this.composedBy = composedBy;
    }

    public long getLatencyMs()
    {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs)
    {
        this.latencyMs = latencyMs;
    }

    public int getTokens()
    {
        return tokens;
    }

    public void setTokens(int tokens)
    {
        this.tokens = tokens;
    }

    public List<String> getAssumptions()
    {
        return assumptions;
    }

    public void setAssumptions(List<String> assumptions)
    {
        this.assumptions = assumptions;
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("phase", phase);
        m.put("reply", reply);
        m.put("needConfirm", needConfirm);
        m.put("confirmToken", confirmToken);
        m.put("card", card);
        m.put("parser", parser);
        m.put("composedBy", composedBy);
        m.put("latencyMs", latencyMs);
        m.put("tokens", tokens);
        m.put("assumptions", assumptions);
        List<Map<String, Object>> cites = new ArrayList<>();
        for (Citation c : citations)
        {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("callId", c.callId());
            one.put("toolId", c.toolId());
            one.put("title", c.title());
            one.put("label", c.label());
            cites.add(one);
        }
        m.put("citations", cites);
        return m;
    }
}
