package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。
 * <p>
 * {@code facts} 是预先渲染好的「已带单位的一句话事实」，回复正文只能由 facts 拼装而成，
 * 模型不参与数字生成——这就是方案里「不产数字」的落地方式。
 *
 * @author ruoyi
 */
public final class ToolResult
{
    private final boolean ok;
    private final String headline;
    private final List<String> facts;
    private final Map<String, Object> data;
    private final String error;

    private ToolResult(boolean ok, String headline, List<String> facts,
            Map<String, Object> data, String error)
    {
        this.ok = ok;
        this.headline = headline;
        this.facts = facts == null ? new ArrayList<>() : facts;
        this.data = data == null ? new LinkedHashMap<>() : data;
        this.error = error;
    }

    public static ToolResult ok(String headline, List<String> facts, Map<String, Object> data)
    {
        return new ToolResult(true, headline, facts, data, null);
    }

    public static ToolResult ok(String headline, List<String> facts)
    {
        return new ToolResult(true, headline, facts, null, null);
    }

    public static ToolResult fail(String error)
    {
        return new ToolResult(false, null, null, null, error);
    }

    public boolean ok()
    {
        return ok;
    }

    public String headline()
    {
        return headline;
    }

    public List<String> facts()
    {
        return facts;
    }

    public Map<String, Object> data()
    {
        return data;
    }

    public String error()
    {
        return error;
    }
}
