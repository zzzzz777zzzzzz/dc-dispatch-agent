package com.ruoyi.agent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调度智能体编排器。
 * <p>
 * 骨架照搬 LangGraph 的「显式状态机 + 检查点 + 人工中断点」：
 * <pre>
 *   PARSE → GUARD → (CONFIRM) → EXECUTE → COMPOSE → DONE
 * </pre>
 * 每一跳都可以被拦截，拦截后状态是 CLARIFY / NEED_CONFIRM / REJECTED，而不是异常。
 * <p>
 * 三道闸分别落在：{@code GUARD} 置信度与长度、{@code CONFIRM} 写动作二次确认、
 * 工具内部的硬约束校验。
 *
 * @author ruoyi
 */
public class AgentOrchestrator
{
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** 确认令牌有效期 */
    private static final long CONFIRM_TTL_MS = 10 * 60 * 1000L;
    /** 撤销窗口：只给手滑用的 */
    private static final long UNDO_WINDOW_MS = 5 * 60 * 1000L;
    /** 单句最大长度 */
    private static final int MAX_INPUT = 200;
    /** 低于这个置信度只反问，不执行 */
    private static final double MIN_CONFIDENCE = 0.5;

    /** 正文交给模型改写的工具：都是「一个问题一个结论」的场景 */
    private static final java.util.Set<String> PROSE_TOOLS = java.util.Set.of(
            "state.snapshot", "plan.detail", "report.summary");

    private final ToolRegistry registry;
    private final IntentParser parser;
    private final ConversationStore store;
    private final ReplyGenerator replyGenerator;
    private final ReplyComposer composer;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1000);

    public AgentOrchestrator(ToolRegistry registry, IntentParser parser, ConversationStore store)
    {
        this(registry, parser, store, null, null);
    }

    public AgentOrchestrator(ToolRegistry registry, IntentParser parser, ConversationStore store,
            ReplyGenerator replyGenerator)
    {
        this(registry, parser, store, replyGenerator, null);
    }

    public AgentOrchestrator(ToolRegistry registry, IntentParser parser, ConversationStore store,
            ReplyGenerator replyGenerator, ReplyComposer composer)
    {
        this.registry = registry;
        this.parser = parser;
        this.store = store;
        this.replyGenerator = replyGenerator;
        this.composer = composer;
    }

    public ChatReply handle(ChatRequest req, String user)
    {
        long t0 = System.currentTimeMillis();
        String runId = "RUN" + (seq.incrementAndGet());
        AgentContext ctx = new AgentContext(runId, req.getSessionId(), user);
        String text = req.getText() == null ? "" : req.getText().trim();
        String session = req.getSessionId() == null ? "default" : req.getSessionId();

        // 闸一：输入长度
        if (text.length() > MAX_INPUT)
        {
            return done(runId, "REJECTED", "这句话太长了，请用一句话说清楚要做什么，比如“重跑明天北区 A 的计划”。", t0, "guard");
        }

        // 确认路径优先
        if (req.isConfirmed())
        {
            Pending p = takePending(req.getConfirmToken(), session);
            if (p == null)
            {
                return done(runId, "DONE", "没有待确认的动作，或者确认已经过期（有效期十分钟），请重新下达指令。", t0, "confirm");
            }
            return execute(runId, ctx, p.intent, t0, null);
        }
        if (text.isEmpty())
        {
            return done(runId, "CLARIFY", "请说一句话。比如：查一下北区 A 现在的状态。", t0, "guard");
        }

        // 闸二：意图与置信度（带上一轮上下文，支持「那南区呢」这类追问）
        ConversationStore.Session memory = store.get(session);
        Intent intent = parser.parse(text, memory.lastIntent());
        // 确定性实体以原文为准：站名、日期不让模型猜
        SlotNormalizer.normalize(intent, text, java.time.LocalDate.now());
        // 模型把「就要今天的」当成闲聊时，按上一轮的动作纠正回来
        intent = reconcile(intent, memory.lastIntent());
        log.info("[agent] {} run={} text={} -> {}", user, runId, text, intent);
        if (intent.action() == Action.UNKNOWN || intent.confidence() < MIN_CONFIDENCE)
        {
            return clarify(runId, t0, intent);
        }

        switch (intent.action())
        {
            case SMALLTALK:
                return smalltalk(runId, text, t0, intent);
            case HELP:
                return help(runId, t0);
            case CANCEL:
                clearPending(session);
                return done(runId, "DONE", "好，已取消，什么都没动。", t0, intent.source());
            case CONFIRM:
            {
                Pending p = takePending(req.getConfirmToken(), session);
                if (p == null)
                {
                    return done(runId, "DONE", "现在没有等你确认的动作。", t0, intent.source());
                }
                return execute(runId, ctx, p.intent, t0, null);
            }
            case EXPLAIN:
                return explain(runId, intent, t0);
            case UNDO:
                return undo(runId, ctx, t0, text, session, intent.source());
            default:
                break;
        }

        AgentTool tool = registry.byAction(intent.action());
        if (tool == null)
        {
            return clarify(runId, t0, intent);
        }

        // 闸三：写动作二次确认
        if (tool.requiresConfirm())
        {
            String token = "tk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            pending.values().removeIf(Pending::expired);
            pending.put(token, new Pending(intent, session, System.currentTimeMillis() + CONFIRM_TTL_MS));
            return confirm(runId, intent, t0, token);
        }

        return execute(runId, ctx, intent, t0, text);
    }

    // ------------------------------------------------------------------ 执行

    private ChatReply execute(String runId, AgentContext ctx, Intent intent, long t0, String userText)
    {
        AgentTool tool = registry.byAction(intent.action());
        if (tool == null)
        {
            return done(runId, "DONE", "这个动作还没有接上工具。", t0, intent.source());
        }
        String callId = String.valueOf(seq.incrementAndGet());
        ToolResult result;
        try
        {
            result = tool.execute(ctx, intent.slots());
        }
        catch (Exception e)
        {
            log.error("[agent] 工具 {} 执行失败", tool.id(), e);
            result = ToolResult.fail(e.getClass().getSimpleName() + "：" + safeMessage(e));
        }

        ChatReply reply = new ChatReply();
        reply.setRunId(runId);
        reply.setParser(intent.source());
        reply.setLatencyMs(System.currentTimeMillis() - t0);
        reply.setAssumptions(intent.assumptions());

        if (!result.ok())
        {
            reply.setPhase("DONE");
            // 读动作没读到东西，就把原因原样说出来；写动作才加「没做成」
            reply.setReply(tool.sideEffect() == SideEffect.READ ? result.error() : "没做成。" + result.error());
            return reply;
        }

        StringBuilder sb = new StringBuilder(result.headline() == null ? "" : result.headline());
        for (String f : result.facts())
        {
            sb.append("\n· ").append(f);
        }
        reply.setPhase("DONE");
        reply.setReply(sb.toString().trim());
        reply.setTokens(intent.tokens());
        reply.setComposedBy("template");

        // 读动作的正文交给模型重写：事实不变，只换了说法。
        // 数字越界就整段弃用，回到模板——这就是「不产数字」在大模型参与正文之后的落地方式。
        // 列表类工具不参与改写：三行计划拆成散文反而更难对，模板原样列着更好读。
        if (composer != null && composer.available() && tool.sideEffect() == SideEffect.READ
                && PROSE_TOOLS.contains(tool.id()))
        {
            ReplyComposer.Composed composed = composer.compose(new ReplyComposer.Request(
                    userText, tool.title(), result.headline(), result.facts()));
            if (composed != null)
            {
                String bad = FactGuard.offendingNumber(composed.text(), result.facts(), userText);
                if (bad == null)
                {
                    reply.setReply(composed.text());
                    reply.setComposedBy("llm");
                    reply.setTokens(intent.tokens() + composed.tokens());
                }
                else
                {
                    log.warn("[agent] 撰写结果出现无出处数字 {}，已回落模板正文", bad);
                }
            }
        }

        // 耗时要在撰写之后结算，否则界面上的 MS 只算了工具、没算模型
        reply.setLatencyMs(System.currentTimeMillis() - t0);

        List<Citation> cites = new ArrayList<>();
        cites.add(new Citation(callId, tool.id(), tool.title()));
        reply.setCitations(cites);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", tool.title());
        card.put("lines", result.facts());
        card.put("actions", List.of());
        reply.setCard(card);

        // 把工具解析出来的默认值回填进槽位，下一轮追问才继承得到
        if (intent.dcCode() == null && result.data().get("dcCode") != null)
        {
            intent.slots().put("dcCode", String.valueOf(result.data().get("dcCode")));
        }
        if (intent.date() == null && result.data().get("date") != null)
        {
            intent.slots().put("date", String.valueOf(result.data().get("date")));
        }
        // 记住刚生成的计划，供 5 分钟内撤销
        if ("plan.solve".equals(tool.id()) && result.data().get("planId") instanceof Number n)
        {
            Object code = result.data().get("planCode");
            store.rememberPlan(ctx.sessionId(), n.longValue(), code == null ? null : String.valueOf(code));
        }
        if (intent.action() != Action.UNDO)
        {
            store.remember(ctx.sessionId(), userText, intent);
        }
        return reply;
    }

    // ------------------------------------------------------------------ 分支

    private ChatReply confirm(String runId, Intent intent, long t0, String token)
    {
        List<String> lines = new ArrayList<>();
        String headline;
        String cardTitle;
        if (intent.action() == Action.UPDATE_STRATEGY)
        {
            Object v = intent.slots().get("socTarget");
            lines.add("策略：当前启用中的调度策略");
            lines.add("储能 SOC 目标：" + (v == null ? "（没说改成多少）" : v + "%"));
            lines.add("动作：修改策略参数，下次求解生效");
            headline = "要改策略参数，请核对：";
            cardTitle = "待确认：改策略参数";
        }
        else if (intent.action() == Action.SELECT_STRATEGY)
        {
            Object code = intent.slots().get("strategyCode");
            Object name = intent.slots().get("strategyName");
            lines.add("目标策略：" + (name == null ? "（没说换成哪条）" : name));
            if (code != null)
            {
                lines.add("策略编号：" + code);
            }
            lines.add("动作：切换启用中的调度策略，其余策略停用");
            lines.add("生效时机：下一次求解；已经算好的计划不变");
            headline = "要切换调度策略，请核对：";
            cardTitle = "待确认：切换调度策略";
        }
        else
        {
            lines.add("站点：" + (intent.dcCode() == null ? "（未指定，取第一个数据中心）" : intent.dcCode()));
            lines.add("日期：" + (intent.date() == null ? "（未指定，取最近有数据的一天）" : intent.date()));
            lines.add("动作：求解并落库，会新增一份计划");
            headline = "要重跑计划，请核对参数：";
            cardTitle = "待确认：重跑计划";
        }

        StringBuilder echo = new StringBuilder(headline);
        for (String l : lines)
        {
            echo.append("\n· ").append(l);
        }
        echo.append("\n点“确认执行”才会写库，也可以说“取消”。");

        ChatReply r = new ChatReply();
        r.setRunId(runId);
        r.setPhase("NEED_CONFIRM");
        r.setNeedConfirm(true);
        r.setConfirmToken(token);
        r.setParser(intent.source());
        r.setLatencyMs(System.currentTimeMillis() - t0);
        r.setAssumptions(intent.assumptions());
        r.setReply(echo.toString());
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", cardTitle);
        card.put("lines", lines);
        card.put("actions", List.of(
                Map.of("label", "确认执行", "value", "confirm"),
                Map.of("label", "取消", "value", "cancel")));
        r.setCard(card);
        return r;
    }

    /**
     * 纠正大模型的过度理解。
     * <p>
     * 「我说的是今天」「那昨天的呢」这类话只有条件、没有动作词，模型容易当成闲聊或帮助请求。
     * 只要原文里抽出了明确的站点/日期槽位、而上一轮是查询动作，就按上一轮的动作继续，
     * 并把这个判断记成假设显示出来，不偷偷改。
     */
    private Intent reconcile(Intent intent, Intent previous)
    {
        if (intent == null || previous == null)
        {
            return intent;
        }
        Action a = intent.action();
        boolean misread = a == Action.SMALLTALK || a == Action.HELP || a == Action.UNKNOWN;
        boolean carriesSlot = intent.slots().containsKey("date") || intent.slots().containsKey("dcCode");
        boolean wasQuery = previous.action() == Action.QUERY_STATUS || previous.action() == Action.QUERY_PLAN
                || previous.action() == Action.QUERY_PLAN_DETAIL || previous.action() == Action.QUERY_REPORT;
        if (!misread || !carriesSlot || !wasQuery)
        {
            return intent;
        }
        Intent fixed = new Intent(previous.action(), intent.slots(),
                Math.max(intent.confidence(), 0.8), intent.source()).withTokens(intent.tokens());
        for (String note : intent.assumptions())
        {
            fixed.withAssumption(note);
        }
        fixed.withAssumption("按上一轮的动作理解：" + previous.action().label());
        return fixed;
    }

    private ChatReply clarify(String runId, long t0, Intent intent)
    {
        String hint = "没听明白。你可以这样说：\n"
                + "· 北区 A 现在什么状态\n"
                + "· 重跑明天北区 A 的计划\n"
                + "· 北区 A 昨天省了多少钱\n"
                + "· 看看最近 5 份计划";
        ChatReply r = done(runId, "CLARIFY", hint, t0, intent == null ? "none" : intent.source());
        r.setAssumptions(intent == null ? List.of() : intent.assumptions());
        return r;
    }

    /**
     * 闲聊/自我介绍：交给模型用自己的话说；模型不在就回一句固定的。
     */
    private ChatReply smalltalk(String runId, String text, long t0, Intent intent)
    {
        if (replyGenerator != null && replyGenerator.available())
        {
            ReplyGenerator.Reply r = replyGenerator.answer(text,
                    "这是一个数据中心微网调度系统，有 4 个数据中心，" + registry.size() + " 个调度工具");
            if (r != null)
            {
                ChatReply reply = done(runId, "DONE", r.text(), t0, "llm");
                reply.setTokens(r.tokens());
                return reply;
            }
        }
        String fallback = "我是这套调度系统里的智能体：你一句话，我负责判断该查还是该跑，"
                + "再把结果带着证据回给你。真正的调度计算是本地求解器做，我不编数字。";
        return done(runId, "DONE", fallback, t0, "rule");
    }

    private ChatReply help(String runId, long t0)
    {
        StringBuilder sb = new StringBuilder("我能做这几件事：");
        for (AgentTool tool : registry.all())
        {
            sb.append("\n· ").append(tool.title()).append("（").append(tool.id()).append("）—— ")
                    .append(tool.description());
        }
        sb.append("\n带“重跑”这类写动作，我会先弹一张确认卡，你点了才执行。");
        return done(runId, "DONE", sb.toString(), t0, "rule");
    }

    /**
     * 撤销：只针对本会话 5 分钟内刚生成的计划。
     */
    private ChatReply undo(String runId, AgentContext ctx, long t0, String text, String sessionId,
            String parserName)
    {
        ConversationStore.Session memory = store.get(sessionId);
        Long planId = memory.lastPlanId();
        if (planId == null)
        {
            return done(runId, "DONE", "这个会话里还没有可撤销的计划。刚生成过计划的话，5 分钟内说“撤销”就删掉。",
                    t0, parserName);
        }
        long elapsed = memory.millisSincePlan();
        if (elapsed > UNDO_WINDOW_MS)
        {
            return done(runId, "DONE", "上次生成计划已经过去 " + (elapsed / 60000)
                    + " 分钟，超过 5 分钟就不能撤销了，请到计划页面手工删除。", t0, parserName);
        }
        Intent intent = new Intent(Action.UNDO, new LinkedHashMap<>(), 0.95, parserName);
        intent.slots().put("planId", planId);
        ChatReply reply = execute(runId, ctx, intent, t0, text);
        if (reply.getPhase() != null && !"DONE".equals(reply.getPhase()))
        {
            return reply;
        }
        memory.forgetPlan();
        return reply;
    }

    /**
     * 解释类请求：不做数值推断，只说明依据从哪里来。
     */    private ChatReply explain(String runId, Intent intent, long t0)
    {
        String text = "解释类的问题，我按“计划怎么来的”回答：先看当日负荷与光伏预测，再由 MILP 在功率平衡、"
                + "储能 SOC、充放互斥、燃机爬坡、并网容量这几条约束下求最小成本，最后和基线（储能闲置、"
                + "新能源优先、电网兜底）对比得出节约。\n"
                + "如果你要问某一次具体的取舍，把站点和日期说给我，我调 plan.detail 把那天的 96 点读出来再讲。";
        ChatReply r = done(runId, "DONE", text, t0, intent.source());
        r.setAssumptions(intent.assumptions());
        return r;
    }

    // ------------------------------------------------------------------ 工具

    private ChatReply done(String runId, String phase, String text, long t0, String parser)
    {
        ChatReply r = new ChatReply();
        r.setRunId(runId);
        r.setPhase(phase);
        r.setReply(text);
        r.setParser(parser);
        r.setLatencyMs(System.currentTimeMillis() - t0);
        return r;
    }

    private Pending takePending(String token, String session)
    {
        if (token != null && !token.isBlank())
        {
            Pending p = pending.remove(token);
            if (p != null && !p.expired())
            {
                return p;
            }
        }
        // 没有令牌时，按会话取最近一个未过期动作（用户直接说“确认”的场景）
        Pending hit = null;
        for (Map.Entry<String, Pending> e : pending.entrySet())
        {
            Pending p = e.getValue();
            if (p.sessionId.equals(session) && !p.expired())
            {
                hit = p;
                pending.remove(e.getKey());
                break;
            }
        }
        return hit;
    }

    private void clearPending(String session)
    {
        pending.entrySet().removeIf(e -> e.getValue().sessionId.equals(session));
    }

    private String safeMessage(Exception e)
    {
        String m = e.getMessage();
        return m == null ? "未知错误" : m;
    }

    /** 待确认动作 */
    private static final class Pending
    {
        private final Intent intent;
        private final String sessionId;
        private final long expireAt;

        private Pending(Intent intent, String sessionId, long expireAt)
        {
            this.intent = intent;
            this.sessionId = sessionId;
            this.expireAt = expireAt;
        }

        private boolean expired()
        {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
