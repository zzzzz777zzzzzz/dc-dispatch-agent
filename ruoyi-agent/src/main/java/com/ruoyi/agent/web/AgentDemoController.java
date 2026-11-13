package com.ruoyi.agent.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.agent.config.AgentProperties;
import com.ruoyi.agent.core.AgentOrchestrator;
import com.ruoyi.agent.core.ChatReply;
import com.ruoyi.agent.core.ChatRequest;
import com.ruoyi.agent.core.IntentParser;
import com.ruoyi.agent.core.ToolRegistry;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;

/**
 * 演示入口：免登录，只在 {@code agent.demo.open=true} 时存在。
 * <p>
 * 生产 profile 不打开这个开关，Bean 根本不会注册，接口直接 404。
 * 单独成类，是为了让「演示能力」和「生产接口」在代码上就分开。
 *
 * @author ruoyi
 */
@Anonymous
@RestController
@RequestMapping("/agent/demo")
@ConditionalOnProperty(prefix = "agent.demo", name = "open", havingValue = "true")
public class AgentDemoController
{
    private final AgentOrchestrator orchestrator;
    private final ToolRegistry registry;
    private final IntentParser parser;
    private final AgentProperties props;

    public AgentDemoController(AgentOrchestrator orchestrator, ToolRegistry registry,
            IntentParser parser, AgentProperties props)
    {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.parser = parser;
        this.props = props;
    }

    @PostMapping("/chat")
    public AjaxResult chat(@RequestBody ChatRequest request)
    {
        ChatReply reply = orchestrator.handle(request, "demo");
        return AjaxResult.success(reply.toMap());
    }

    /** 演示用自检，与 /agent/health 同内容 */
    @GetMapping("/health")
    public AjaxResult health()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parser", parser.name());
        m.put("llmConfigured", props.getLlm().isEnabled()
                && props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank());
        m.put("tools", registry.size());
        return AjaxResult.success(m);
    }
}
