package com.ruoyi.agent.core;

/**
 * 一次对话请求。
 *
 * @author ruoyi
 */
public class ChatRequest
{
    /** 用户原话（或语音识别后的文本） */
    private String text;

    /** 会话 id，前端生成 */
    private String sessionId;

    /** 确认令牌，需要确认的动作会返回它 */
    private String confirmToken;

    /** 是否已确认 */
    private boolean confirmed;

    public String getText()
    {
        return text;
    }

    public void setText(String text)
    {
        this.text = text;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public String getConfirmToken()
    {
        return confirmToken;
    }

    public void setConfirmToken(String confirmToken)
    {
        this.confirmToken = confirmToken;
    }

    public boolean isConfirmed()
    {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed)
    {
        this.confirmed = confirmed;
    }
}
