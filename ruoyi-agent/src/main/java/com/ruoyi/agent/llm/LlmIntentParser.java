package com.ruoyi.agent.llm;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.agent.core.Action;
import com.ruoyi.agent.core.Intent;
import com.ruoyi.agent.core.IntentParser;

/**
 * 大模型意图解析器。
 * <p>
 * 借鉴 Pydantic AI 的「结构化输出优先」：只接受固定 JSON 结构，字段不在白名单里就丢掉；
 * 解析失败返回 null，由组合器降级到规则解析器。模型永远不直接产生回复正文里的数字。
 *
 * @author ruoyi
 */
public class LlmIntentParser implements IntentParser
{
    private static final Logger log = LoggerFactory.getLogger(LlmIntentParser.class);

    private static final String SYSTEM = """
            你是数据中心微网调度系统的指令解析器。
            把用户的一句话转成 JSON，只输出 JSON，不要输出解释、不要用代码围栏。

            action 只能是下列之一：
            QUERY_STATUS（问当前负荷/光伏/电价/状态）
            QUERY_PLAN（看计划列表）
            QUERY_PLAN_DETAIL（看某份计划的明细）
            QUERY_REPORT（问某天省了多少钱/成本/复盘）
            SOLVE_PLAN（重跑/重新求解计划）
            UNDO（撤销刚生成的计划）
            UPDATE_STRATEGY（改策略参数，比如“把储能余量改到 20%”）
            SELECT_STRATEGY（切换启用的调度策略，比如“换成成本优先的策略”“切到削峰那条”）
            QUERY_STRATEGY（问现在用的哪条策略、有哪些策略可选）
            SMALLTALK（问你是谁、闲聊、和调度无关的话）
            EXPLAIN（问为什么）
            HELP（问你能做什么）
            CONFIRM（确认执行）
            CANCEL（取消）
            UNKNOWN（听不懂）

            slots 只允许这些键：
            dcCode：只能是 DC-A、DC-B、DC-C、DC-D 之一（北区=DC-A，南区=DC-B，东区=DC-C，西区=DC-D）
            date：yyyy-MM-dd
            planId：整数
            limit：整数
            socTarget：数字，0-100，仅 UPDATE_STRATEGY 使用
            strategyCode：形如 STR001，仅 SELECT_STRATEGY 使用
            strategyName：策略名称或口语说法（成本优先/消纳优先/削峰优先），仅 SELECT_STRATEGY 使用

            如果用户这句话是省略式追问（例如「那南区呢」「昨天呢」「再跑一遍」），
            请沿用「上一轮」里没被覆盖的槽位，并保留同样的 action。

            输出格式：{"action":"...","slots":{...},"confidence":0.0}
            """;

    private final DeepSeekClient client;

    public LlmIntentParser(DeepSeekClient client)
    {
        this.client = client;
    }

    @Override
    public String name()
    {
        return "llm";
    }

    @Override
    public boolean available()
    {
        return client != null && client.configured();
    }

    @Override
    public Intent parse(String text, Intent previous)
    {
        if (!available() || text == null || text.isBlank())
        {
            return null;
        }
        try
        {
            String today = LocalDate.now().toString();
            StringBuilder user = new StringBuilder();
            user.append("今天的日期是 ").append(today).append("。\n");
            if (previous != null && previous.action() != Action.UNKNOWN)
            {
                user.append("上一轮：action=").append(previous.action().name())
                        .append(", dcCode=").append(previous.dcCode())
                        .append(", date=").append(previous.date()).append("\n");
            }
            user.append("用户说：").append(text);
            DeepSeekClient.Completion completion = client.complete(SYSTEM, user.toString());
            JsonNode node = client.mapper().readTree(completion.content());
            Action action = Action.of(node.path("action").asText("UNKNOWN"));
            if (action == Action.UNKNOWN)
            {
                return null;
            }
            Map<String, Object> slots = new LinkedHashMap<>();
            JsonNode s = node.path("slots");
            if (s.isObject())
            {
                copyIfPresent(s, slots, "dcCode", "dcCode");
                copyIfPresent(s, slots, "date", "date");
                copyIfPresent(s, slots, "planId", "planId");
                copyIfPresent(s, slots, "limit", "limit");
                copyIfPresent(s, slots, "socTarget", "socTarget");
                copyIfPresent(s, slots, "strategyCode", "strategyCode");
                copyIfPresent(s, slots, "strategyName", "strategyName");
            }
            double conf = node.path("confidence").asDouble(0.8);
            return new Intent(action, slots, Math.max(0.0, Math.min(1.0, conf)), name())
                    .withTokens(completion.totalTokens());
        }
        catch (Exception e)
        {
            log.warn("[agent] 大模型解析失败，降级规则解析：{}", e.getMessage());
            return null;
        }
    }

    private void copyIfPresent(JsonNode src, Map<String, Object> dst, String key, String outKey)
    {
        JsonNode v = src.get(key);
        if (v == null || v.isNull())
        {
            return;
        }
        if (v.isNumber())
        {
            dst.put(outKey, v.numberValue());
        }
        else if (!v.asText().isBlank())
        {
            dst.put(outKey, v.asText());
        }
    }
}
