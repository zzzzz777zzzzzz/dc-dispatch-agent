package com.ruoyi.agent.core;

/**
 * 工具协议。
 * <p>
 * 借鉴 OpenAI Agents SDK / LangChain 的工具抽象：id、描述、副作用等级、是否需要确认。
 * 智能体能做的每一件事都必须实现这个接口，没有例外——这是「可审计」和「可复制」的前提。
 *
 * @author ruoyi
 */
public interface AgentTool
{
    /** 全局唯一 id，如 plan.solve */
    String id();

    /** 中文名，用于卡片与回执 */
    String title();

    /** 一句话说明，喂给大模型做工具选择 */
    String description();

    /** 副作用等级 */
    SideEffect sideEffect();

    /** 写动作默认需要确认 */
    default boolean requiresConfirm()
    {
        return sideEffect() != SideEffect.READ;
    }

    /**
     * 执行。
     *
     * @param ctx  运行上下文（会话、用户、runId）
     * @param args 参数，键与 {@link Intent} 的 slots 一致
     */
    ToolResult execute(AgentContext ctx, java.util.Map<String, Object> args);
}
