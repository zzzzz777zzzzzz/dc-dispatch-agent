package com.ruoyi.agent.core;

/**
 * 调度动作清单。
 * <p>
 * 借鉴 OpenAI Agents SDK 的「工具白名单」思路：模型不能自由发挥，只能在固定动作里选。
 * 每个动作绑定一个工具，工具之外的请求一律走 {@link #HELP} 或 {@link #UNKNOWN}。
 *
 * @author ruoyi
 */
public enum Action
{
    /** 看当前状态：负荷、光伏、电价、装机 */
    QUERY_STATUS("state.snapshot", "查当前状态"),

    /** 看计划列表 */
    QUERY_PLAN("plan.list", "查计划列表"),

    /** 看某份计划的 96 点明细 */
    QUERY_PLAN_DETAIL("plan.detail", "查计划明细"),

    /** 某天的成本与基线对比 */
    QUERY_REPORT("report.summary", "查复盘数据"),

    /** 重跑计划（写动作，需确认） */
    SOLVE_PLAN("plan.solve", "重跑计划"),

    /** 撤销刚生成的计划（写动作，5 分钟内有效） */
    UNDO("plan.undo", "撤销计划"),

    /** 改策略参数（写动作，需确认） */
    UPDATE_STRATEGY("strategy.update", "改策略参数"),

    /** 切换启用的调度策略（写动作，需确认） */
    SELECT_STRATEGY("strategy.select", "切换调度策略"),

    /** 看当前启用的策略与全部可选策略 */
    QUERY_STRATEGY("strategy.list", "查调度策略"),

    /** 解释原因，不落库 */
    EXPLAIN(null, "解释"),

    /** 问「你是谁」这类闲聊，交给模型用自己的话说 */
    SMALLTALK(null, "闲聊"),

    /** 能做哪些事 */
    HELP(null, "能力说明"),

    /** 确认待执行动作 */
    CONFIRM(null, "确认"),

    /** 放弃待执行动作 */
    CANCEL(null, "取消"),

    /** 没听懂 */
    UNKNOWN(null, "未识别");

    private final String toolId;
    private final String label;

    Action(String toolId, String label)
    {
        this.toolId = toolId;
        this.label = label;
    }

    public String toolId()
    {
        return toolId;
    }

    public String label()
    {
        return label;
    }

    public boolean isWrite()
    {
        return this == SOLVE_PLAN || this == UNDO || this == UPDATE_STRATEGY || this == SELECT_STRATEGY;
    }

    public static Action of(String name)
    {
        if (name == null)
        {
            return UNKNOWN;
        }
        try
        {
            return valueOf(name.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            return UNKNOWN;
        }
    }
}
