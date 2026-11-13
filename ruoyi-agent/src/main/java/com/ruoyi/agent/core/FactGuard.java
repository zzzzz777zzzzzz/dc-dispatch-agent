package com.ruoyi.agent.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字守门员。
 * <p>
 * 大模型只允许「改写」，不允许「新增」：正文里出现的每一个数字，都必须能在工具事实或用户原话里
 * 找到出处。找不到就说明它在编（常见的是把 873 写成 870、把 3992 写成 4000），
 * 这时退回模板正文——宁可读起来生硬，也不能让一个编出来的数字进调度记录。
 *
 * @author ruoyi
 */
public final class FactGuard
{
    /** 整数或小数；不做千分位、百分号、单位的解析，只比数字本身 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private FactGuard()
    {
    }

    /**
     * 找出正文里第一个在事实中找不到出处的数字。
     *
     * @param prose    模型写的正文
     * @param facts    工具给出的事实清单
     * @param userText 用户原话（用户自己说的数字，例如「20%」「11月20号」是合法的）
     * @return 越界的数字；全部有出处时返回 null
     */
    public static String offendingNumber(String prose, List<String> facts, String userText)
    {
        if (prose == null || prose.isBlank())
        {
            return null;
        }
        Set<String> allowed = new HashSet<>();
        collect(allowed, userText);
        if (facts != null)
        {
            for (String f : facts)
            {
                collect(allowed, f);
                // 事实里写「7,841 元」，正文写成「7841 元」也算有出处
                collect(allowed, f.replace(",", ""));
            }
        }
        Matcher m = NUMBER.matcher(prose);
        while (m.find())
        {
            String token = m.group();
            if (!allowed.contains(token) && !allowed.contains(normalize(token)))
            {
                return token;
            }
        }
        return null;
    }

    private static void collect(Set<String> into, String text)
    {
        if (text == null)
        {
            return;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find())
        {
            into.add(m.group());
            into.add(normalize(m.group()));
        }
    }

    /** 874.0 与 874 视为同一个数字 */
    private static String normalize(String token)
    {
        if (token.indexOf('.') < 0)
        {
            return token;
        }
        String t = token;
        while (t.endsWith("0"))
        {
            t = t.substring(0, t.length() - 1);
        }
        if (t.endsWith("."))
        {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
