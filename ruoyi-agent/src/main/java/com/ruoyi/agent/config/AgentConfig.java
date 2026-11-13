package com.ruoyi.agent.config;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ruoyi.agent.core.AgentOrchestrator;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.CompositeIntentParser;
import com.ruoyi.agent.core.ConversationStore;
import com.ruoyi.agent.core.IntentParser;
import com.ruoyi.agent.core.ReplyComposer;
import com.ruoyi.agent.core.ReplyGenerator;
import com.ruoyi.agent.core.RuleIntentParser;
import com.ruoyi.agent.core.ToolRegistry;
import com.ruoyi.agent.dispatch.PlanDetailTool;
import com.ruoyi.agent.dispatch.PlanListTool;
import com.ruoyi.agent.dispatch.ReportSummaryTool;
import com.ruoyi.agent.dispatch.SolvePlanTool;
import com.ruoyi.agent.dispatch.StateSnapshotTool;
import com.ruoyi.agent.dispatch.StrategyListTool;
import com.ruoyi.agent.dispatch.StrategySelectTool;
import com.ruoyi.agent.dispatch.StrategyUpdateTool;
import com.ruoyi.agent.dispatch.UndoPlanTool;
import com.ruoyi.agent.llm.DeepSeekClient;
import com.ruoyi.agent.llm.LlmIntentParser;
import com.ruoyi.agent.llm.LlmReplyComposer;
import com.ruoyi.agent.llm.LlmReplyGenerator;
import com.ruoyi.system.dispatch.DispatchDao;
import com.ruoyi.system.dispatch.DispatchPlanService;

/**
 * 智能体装配。
 * <p>
 * 只装配不用扫描：工具、解析器、编排器都是显式 new 出来的，启动时就能看到全貌，
 * 也便于将来把这一层整体复制到别的项目。
 *
 * @author ruoyi
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig
{
    @Bean
    public ToolRegistry agentToolRegistry(DispatchDao dao, DispatchPlanService planService)
    {
        List<AgentTool> tools = List.of(
                new StateSnapshotTool(dao),
                new PlanListTool(dao),
                new PlanDetailTool(dao),
                new ReportSummaryTool(dao),
                new SolvePlanTool(dao, planService),
                new UndoPlanTool(dao),
                new StrategyUpdateTool(dao),
                new StrategyListTool(dao),
                new StrategySelectTool(dao));
        return new ToolRegistry(tools);
    }

    @Bean
    public DeepSeekClient agentLlmClient(AgentProperties props)
    {
        AgentProperties.Llm cfg = props.getLlm();
        return new DeepSeekClient(cfg.getApiKey(), cfg.getBaseUrl(), cfg.getModel(), cfg.getTimeoutSeconds());
    }

    @Bean
    public IntentParser agentIntentParser(AgentProperties props, DeepSeekClient client)
    {
        RuleIntentParser rules = new RuleIntentParser();
        if (!props.getLlm().isEnabled())
        {
            return new CompositeIntentParser(rules);
        }
        // 先试大模型，失败自动落规则；没有 Key 时大模型解析器 self-disable
        return new CompositeIntentParser(new LlmIntentParser(client), rules);
    }

    @Bean
    public ReplyGenerator agentReplyGenerator(AgentProperties props, DeepSeekClient client)
    {
        if (!props.getLlm().isEnabled())
        {
            return null;
        }
        return new LlmReplyGenerator(client);
    }

    /**
     * 正文撰写：把工具事实写成一段人话。
     * 关掉大模型时不注册这个 Bean，编排器自动只用模板正文。
     */
    @Bean
    public ReplyComposer agentReplyComposer(AgentProperties props, DeepSeekClient client)
    {
        if (!props.getLlm().isEnabled())
        {
            return null;
        }
        return new LlmReplyComposer(client);
    }

    @Bean
    public ConversationStore agentConversationStore()
    {
        return new ConversationStore();
    }

    @Bean
    public AgentOrchestrator agentOrchestrator(ToolRegistry registry, IntentParser parser,
            ConversationStore store, ReplyGenerator replyGenerator, ReplyComposer composer)
    {
        return new AgentOrchestrator(registry, parser, store, replyGenerator, composer);
    }
}
