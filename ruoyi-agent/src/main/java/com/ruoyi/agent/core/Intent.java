package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析出来的调度意图。
 * <p>
 * 借鉴 Pydantic AI 的做法：先定结构，再让模型填。模型填不出合规结构，就退回规则解析器，
 * 不允许「半懂半猜」地直接执行。
 *
 * @author ruoyi
 */
public final class Intent
{
    private final Action action;
    private final Map<String, Object> slots;
    private final double confidence;
    private final String source;
    private final List<String> assumptions;
    private int tokens;

    public Intent(Action action, Map<String, Object> slots, double confidence, String source)
    {
        this.action = action;
        this.slots = slots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slots);
        this.confidence = confidence;
        this.source = source;
        this.assumptions = new ArrayList<>();
    }

    public Action action()
    {
        return action;
    }

    public Map<String, Object> slots()
    {
        return slots;
    }

    public double confidence()
    {
        return confidence;
    }

    public String source()
    {
        return source;
    }

    /** 本次解析消耗的 token；规则解析为 0 */
    public int tokens()
    {
        return tokens;
    }

    public Intent withTokens(int tokens)
    {
        this.tokens = tokens;
        return this;
    }

    public List<String> assumptions()
    {
        return assumptions;
    }

    public Intent withAssumption(String text)
    {
        if (text != null && !text.isBlank() && !assumptions.contains(text))
        {
            assumptions.add(text);
        }
        return this;
    }

    public String dcCode()
    {
        Object v = slots.get("dcCode");
        return v == null ? null : String.valueOf(v);
    }

    public String date()
    {
        Object v = slots.get("date");
        return v == null ? null : String.valueOf(v);
    }

    public Long planId()
    {
        Object v = slots.get("planId");
        if (v == null)
        {
            return null;
        }
        if (v instanceof Number n)
        {
            return n.longValue();
        }
        try
        {
            return Long.parseLong(String.valueOf(v).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    public int limit(int fallback)
    {
        Object v = slots.get("limit");
        if (v instanceof Number n)
        {
            return n.intValue();
        }
        return fallback;
    }

    @Override
    public String toString()
    {
        return action + slots.toString() + " conf=" + confidence + " src=" + source;
    }
}
