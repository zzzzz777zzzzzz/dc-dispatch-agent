package com.ruoyi.agent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ruoyi.agent.core.ReplyGenerator;

/**
 * 用大模型回答闲聊与自我介绍。
 * <p>
 * 失败一律返回 null，由编排器回落到固定答复——闲聊不能成为整个链路的故障点。
 *
 * @author ruoyi
 */
public class LlmReplyGenerator implements ReplyGenerator
{
    private static final Logger log = LoggerFactory.getLogger(LlmReplyGenerator.class);

    private static final String SYSTEM = """
            你是「数据中心微网调度系统」里的调度智能体，正在和调度员对话。
            你负责把一句话变成调度动作：查状态、查计划、查复盘、重跑计划、撤销、改策略参数。
            回答要求：
            1. 用两三句中文，第一人称，克制、具体，不要罗列功能清单，不要用分点。
            2. 不要用“作为 AI”“我是一个语言模型”这类套话。
            3. 你清楚自己的边界：意图理解用大模型，调度计算用本地 MILP 求解器，
               所有数字来自系统查询，你不会编造。
            4. 不要主动推销自己的能力，用户只是随口问一句。
            """;

    private final DeepSeekClient client;

    public LlmReplyGenerator(DeepSeekClient client)
    {
        this.client = client;
    }

    @Override
    public boolean available()
    {
        return client != null && client.configured();
    }

    @Override
    public Reply answer(String question, String context)
    {
        if (!available())
        {
            return null;
        }
        try
        {
            String user = "背景：" + context + "\n用户说：" + question;
            DeepSeekClient.Completion c = client.complete(SYSTEM, user);
            String text = c.content() == null ? "" : c.content().trim();
            if (text.isEmpty())
            {
                return null;
            }
            return new Reply(text, c.totalTokens());
        }
        catch (Exception e)
        {
            log.warn("[agent] 闲聊回答失败，回落固定答复：{}", e.getMessage());
            return null;
        }
    }
}
