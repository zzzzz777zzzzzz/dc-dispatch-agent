package com.ruoyi.agent.core;

/**
 * 意图解析器。两种实现：规则、大模型；组合器按优先级回退。
 *
 * @author ruoyi
 */
public interface IntentParser
{
    /** 解析器名称，会写进回复里，便于演示时说明当前模式 */
    String name();

    /** 当前是否可用（例如没有配置 API Key 时大模型解析器不可用） */
    boolean available();

    /**
     * 解析；无法识别时返回 null，由组合器降级。
     *
     * @param text     用户原话
     * @param previous 上一轮意图，用于处理「那南区呢」这类省略式追问；首轮为 null
     */
    Intent parse(String text, Intent previous);

    /** 无上下文解析 */
    default Intent parse(String text)
    {
        return parse(text, null);
    }
}
