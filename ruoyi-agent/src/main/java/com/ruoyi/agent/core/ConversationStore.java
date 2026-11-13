package com.ruoyi.agent.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话上下文。
 * <p>
 * 借鉴 Letta 的「工作记忆」思路，但只保留最小必要：上一轮意图 + 最近几句原话。
 * 不存完整对话、不存工具返回的原始数据——那些用 runId 回查，避免上下文被大对象撑爆。
 *
 * @author ruoyi
 */
public class ConversationStore
{
    /** 每会话保留的原话条数 */
    private static final int MAX_HISTORY = 6;
    /** 空闲 30 分钟回收 */
    private static final long TTL_MS = 30 * 60 * 1000L;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public Session get(String sessionId)
    {
        String key = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        Session s = sessions.computeIfAbsent(key, Session::new);
        s.lastActive = System.currentTimeMillis();
        return s;
    }

    /** 记住这一轮：原话 + 解析后的意图（含默认值回填） */
    public void remember(String sessionId, String userText, Intent intent)
    {
        Session s = get(sessionId);
        s.lastIntent = intent;
        if (userText != null && !userText.isBlank())
        {
            s.history.addLast(userText);
            while (s.history.size() > MAX_HISTORY)
            {
                s.history.removeFirst();
            }
        }
        sessions.values().removeIf(Session::expired);
    }

    /** 记住刚生成的计划，供 5 分钟内撤销 */
    public void rememberPlan(String sessionId, long planId, String planCode)
    {
        Session s = get(sessionId);
        s.lastPlanId = planId;
        s.lastPlanCode = planCode;
        s.lastPlanAt = System.currentTimeMillis();
    }

    public void clear(String sessionId)
    {
        sessions.remove(sessionId == null ? "default" : sessionId);
    }

    /** 一个会话的工作记忆 */
    public static final class Session
    {
        private final String id;
        private volatile long lastActive = System.currentTimeMillis();
        private volatile Intent lastIntent;
        private volatile Long lastPlanId;
        private volatile String lastPlanCode;
        private volatile long lastPlanAt;
        private final Deque<String> history = new ArrayDeque<>();

        Session(String id)
        {
            this.id = id;
        }

        public String id()
        {
            return id;
        }

        public Intent lastIntent()
        {
            return lastIntent;
        }

        public Long lastPlanId()
        {
            return lastPlanId;
        }

        public String lastPlanCode()
        {
            return lastPlanCode;
        }

        /** 距离上次生成计划过去了多少毫秒；没生成过返回 -1 */
        public long millisSincePlan()
        {
            return lastPlanAt == 0 ? -1 : System.currentTimeMillis() - lastPlanAt;
        }

        public void forgetPlan()
        {
            lastPlanId = null;
            lastPlanCode = null;
            lastPlanAt = 0;
        }

        boolean expired()
        {
            return System.currentTimeMillis() - lastActive > TTL_MS;
        }
    }
}
