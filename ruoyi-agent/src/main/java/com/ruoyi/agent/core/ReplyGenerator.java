package com.ruoyi.agent.core;

/**
 * 自由回答生成器：只用于「你是谁」这类不属于任何调度动作的话。
 * <p>
 * 放在 core 里只定义接口，实现（大模型）在 llm 包里，保持核心层不依赖具体模型。
 *
 * @author ruoyi
 */
public interface ReplyGenerator
{
    boolean available();

    /** 生成失败返回 null，由调用方回落到固定答复 */
    Reply answer(String question, String context);

    /** 一段回答与它的 token 用量 */
    record Reply(String text, int tokens)
    {
    }
}
