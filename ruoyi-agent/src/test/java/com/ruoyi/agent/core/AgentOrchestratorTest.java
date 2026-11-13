package com.ruoyi.agent.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 编排器用例：验证状态机、三道闸与确认令牌，不依赖数据库。
 *
 * @author ruoyi
 */
class AgentOrchestratorTest
{
    private AtomicInteger solveCalls;
    private AgentOrchestrator orchestrator;

    /** 假的求解工具，只数调用次数 */
    private static final class StubSolveTool implements AgentTool
    {
        private final AtomicInteger calls;

        StubSolveTool(AtomicInteger calls)
        {
            this.calls = calls;
        }

        @Override
        public String id()
        {
            return "plan.solve";
        }

        @Override
        public String title()
        {
            return "重跑计划";
        }

        @Override
        public String description()
        {
            return "stub";
        }

        @Override
        public SideEffect sideEffect()
        {
            return SideEffect.WRITE;
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args)
        {
            calls.incrementAndGet();
            return ToolResult.ok("计划已生成", List.of("站点：DC-A"));
        }
    }

    @BeforeEach
    void setUp()
    {
        solveCalls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry(List.of(new StubSolveTool(solveCalls)));
        orchestrator = new AgentOrchestrator(registry,
                new CompositeIntentParser(new RuleIntentParser()), new ConversationStore());
    }

    /** 假的状态工具：把收到的槽位原样记下来，供断言「日期有没有传下去」 */
    private static final class StubStatusTool implements AgentTool
    {
        private final List<Map<String, Object>> seen = new java.util.ArrayList<>();

        @Override
        public String id()
        {
            return "state.snapshot";
        }

        @Override
        public String title()
        {
            return "查当前状态";
        }

        @Override
        public String description()
        {
            return "stub";
        }

        @Override
        public SideEffect sideEffect()
        {
            return SideEffect.READ;
        }

        @Override
        public ToolResult execute(AgentContext ctx, Map<String, Object> args)
        {
            seen.add(new java.util.LinkedHashMap<>(args == null ? Map.of() : args));
            return ToolResult.ok("北区数据中心 A 状态", List.of("站点：DC-A"));
        }
    }

    /** 模拟大模型把「就要今天的」这类只有条件的追问误判成闲聊 */
    private static final class MisreadParser implements IntentParser
    {
        @Override
        public String name()
        {
            return "fake";
        }

        @Override
        public boolean available()
        {
            return true;
        }

        @Override
        public Intent parse(String text, Intent previous)
        {
            if (text.contains("状态"))
            {
                return new Intent(Action.QUERY_STATUS, Map.of("dcCode", "DC-A"), 0.9, "fake");
            }
            return new Intent(Action.SMALLTALK, null, 0.9, "fake");
        }
    }

    /** 假的撰写器：固定返回一段话，用来验证「合规就用、越界就退」 */
    private static final class StubComposer implements ReplyComposer
    {
        private final String text;

        StubComposer(String text)
        {
            this.text = text;
        }

        @Override
        public boolean available()
        {
            return true;
        }

        @Override
        public Composed compose(Request request)
        {
            return new Composed(text, 12);
        }
    }

    private AgentOrchestrator statusOrchestrator(StubStatusTool status, ReplyComposer composer)
    {
        return new AgentOrchestrator(new ToolRegistry(List.of(status)),
                new CompositeIntentParser(new RuleIntentParser()), new ConversationStore(), null, composer);
    }

    @Test
    void composedProseIsUsedWhenNumbersCheckOut()
    {
        StubStatusTool status = new StubStatusTool();
        ChatReply reply = statusOrchestrator(status, new StubComposer("北区 A 的站点编码是 DC-A，设备运行正常。"))
                .handle(request("北区现在什么状态", "s7"), "tester");

        assertEquals("北区 A 的站点编码是 DC-A，设备运行正常。", reply.getReply());
        assertEquals("llm", reply.getComposedBy());
        assertTrue(reply.getTokens() >= 12);
    }

    @Test
    void composedProseWithInventedNumberFallsBackToTemplate()
    {
        StubStatusTool status = new StubStatusTool();
        ChatReply reply = statusOrchestrator(status, new StubComposer("北区 A 负荷大约 999 kW。"))
                .handle(request("北区现在什么状态", "s8"), "tester");

        assertEquals("template", reply.getComposedBy(), "有数字对不上就必须退回模板");
        assertTrue(reply.getReply().contains("站点：DC-A"), "模板正文要保留事实清单");
        assertTrue(!reply.getReply().contains("999"));
    }

    @Test
    void followUpWithOnlyADateKeepsThePreviousAction()
    {
        StubStatusTool status = new StubStatusTool();
        AgentOrchestrator orch = new AgentOrchestrator(new ToolRegistry(List.of(status)),
                new MisreadParser(), new ConversationStore());

        orch.handle(request("北区现在什么状态", "s5"), "tester");
        ChatReply second = orch.handle(request("就要今天的", "s5"), "tester");

        assertEquals("DONE", second.getPhase());
        assertEquals(2, status.seen.size(), "第二次仍应走状态查询，而不是被当成闲聊");
        assertEquals(java.time.LocalDate.now().toString(), String.valueOf(status.seen.get(1).get("date")),
                "「今天」必须作为日期槽位传到工具");
        assertTrue(second.getAssumptions().stream().anyMatch(a -> a.contains("上一轮的动作")));
    }

    @Test
    void explicitDateIsPassedThroughToTheTool()
    {
        StubStatusTool status = new StubStatusTool();
        AgentOrchestrator orch = new AgentOrchestrator(new ToolRegistry(List.of(status)),
                new CompositeIntentParser(new RuleIntentParser()), new ConversationStore());

        orch.handle(request("北区 2026-10-20 什么状态", "s6"), "tester");

        assertEquals("2026-10-20", String.valueOf(status.seen.get(0).get("date")));
    }

    private ChatRequest request(String text, String session)
    {
        ChatRequest r = new ChatRequest();
        r.setText(text);
        r.setSessionId(session);
        return r;
    }

    @Test
    void writeActionWaitsForConfirm()
    {
        ChatReply first = orchestrator.handle(request("重跑明天的北区A计划", "s1"), "tester");
        assertEquals("NEED_CONFIRM", first.getPhase());
        assertTrue(first.isNeedConfirm());
        assertTrue(first.getConfirmToken() != null && !first.getConfirmToken().isBlank());
        assertEquals(0, solveCalls.get(), "确认之前不应该执行");

        ChatRequest confirm = new ChatRequest();
        confirm.setSessionId("s1");
        confirm.setConfirmed(true);
        confirm.setConfirmToken(first.getConfirmToken());
        ChatReply second = orchestrator.handle(confirm, "tester");

        assertEquals("DONE", second.getPhase());
        assertEquals(1, solveCalls.get(), "确认之后应当执行一次");
    }

    @Test
    void reusedTokenIsRejected()
    {
        ChatReply first = orchestrator.handle(request("重跑明天的计划", "s2"), "tester");
        String token = first.getConfirmToken();

        ChatRequest confirm = new ChatRequest();
        confirm.setSessionId("s2");
        confirm.setConfirmed(true);
        confirm.setConfirmToken(token);
        orchestrator.handle(confirm, "tester");

        ChatReply again = orchestrator.handle(confirm, "tester");
        assertEquals(1, solveCalls.get(), "同一个令牌不能执行第二次");
        assertTrue(again.getReply().contains("过期") || again.getReply().contains("没有待确认"));
    }

    @Test
    void unknownTextAsksInsteadOfActing()
    {
        ChatReply reply = orchestrator.handle(request("今天天气不错", "s3"), "tester");
        assertEquals("CLARIFY", reply.getPhase());
        assertEquals(0, solveCalls.get());
    }

    @Test
    void cancelClearsPending()
    {
        orchestrator.handle(request("重跑明天的计划", "s4"), "tester");
        ChatReply cancel = orchestrator.handle(request("取消", "s4"), "tester");
        assertTrue(cancel.getReply().contains("取消"));

        ChatRequest confirm = new ChatRequest();
        confirm.setSessionId("s4");
        confirm.setConfirmed(true);
        ChatReply after = orchestrator.handle(confirm, "tester");
        assertEquals(0, solveCalls.get(), "取消之后不应再执行");
        assertTrue(after.getReply().contains("没有待确认"), "应当明确说没有待确认的动作");
    }
}
