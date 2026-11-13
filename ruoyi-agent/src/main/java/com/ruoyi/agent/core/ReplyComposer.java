package com.ruoyi.agent.core;

import java.util.List;

/**
 * 正文撰写器：把工具返回的事实写成调度员会说的一段话。
 * <p>
 * 和 {@link ReplyGenerator} 的分工：
 * <ul>
 *   <li>{@code ReplyGenerator} 回答「你是谁」这类没有任何工具结果的话；</li>
 *   <li>{@code ReplyComposer} 只在已经有工具事实之后工作，只能改写，不能新增内容。</li>
 * </ul>
 * 实现（大模型）放在 llm 包里，核心层不依赖具体模型；退回模板时调用方什么也不用改。
 *
 * @author ruoyi
 */
public interface ReplyComposer
{
    boolean available();

    /** 撰写失败、超时或不合规一律返回 null，由编排器回落到模板正文 */
    Composed compose(Request request);

    /** 一次撰写的输入：用户原话 + 工具标题 + 事实清单 */
    record Request(String userText, String toolTitle, String headline, List<String> facts)
    {
    }

    /** 撰写结果与 token 用量 */
    record Composed(String text, int tokens)
    {
    }
}
