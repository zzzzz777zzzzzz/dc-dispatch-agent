package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组合解析器：按顺序尝试，先成功的赢。
 * <p>
 * 借鉴 LangGraph 的 fallback 边：大模型不可用或输出不合规时，自动落到规则解析器，
 * 保证「功能不中断，只是不再会解释」。
 *
 * @author ruoyi
 */
public class CompositeIntentParser implements IntentParser
{
    private static final Logger log = LoggerFactory.getLogger(CompositeIntentParser.class);

    private final List<IntentParser> parsers;

    public CompositeIntentParser(IntentParser... parsers)
    {
        List<IntentParser> list = new ArrayList<>();
        Collections.addAll(list, parsers);
        this.parsers = list;
    }

    @Override
    public String name()
    {
        for (IntentParser p : parsers)
        {
            if (p.available())
            {
                return p.name();
            }
        }
        return "none";
    }

    @Override
    public boolean available()
    {
        return !parsers.isEmpty();
    }

    @Override
    public Intent parse(String text, Intent previous)
    {
        for (IntentParser p : parsers)
        {
            if (!p.available())
            {
                continue;
            }
            try
            {
                Intent intent = p.parse(text, previous);
                if (intent != null && intent.action() != Action.UNKNOWN)
                {
                    return intent;
                }
            }
            catch (Exception e)
            {
                log.warn("解析器 {} 失败，降级下一个：{}", p.name(), e.getMessage());
            }
        }
        return new Intent(Action.UNKNOWN, null, 0.2, name());
    }

    public boolean llmActive()
    {
        for (IntentParser p : parsers)
        {
            if ("llm".equals(p.name()) && p.available())
            {
                return true;
            }
        }
        return false;
    }
}
