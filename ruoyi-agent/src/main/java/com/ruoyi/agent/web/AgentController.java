package com.ruoyi.agent.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.agent.config.AgentProperties;
import com.ruoyi.agent.core.AgentOrchestrator;
import com.ruoyi.agent.core.AgentTool;
import com.ruoyi.agent.core.ChatReply;
import com.ruoyi.agent.core.ChatRequest;
import com.ruoyi.agent.core.IntentParser;
import com.ruoyi.agent.core.ToolRegistry;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;

/**
 * 调度指令入口。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/agent")
public class AgentController
{
    private final AgentOrchestrator orchestrator;
    private final ToolRegistry registry;
    private final IntentParser parser;
    private final AgentProperties props;

    public AgentController(AgentOrchestrator orchestrator, ToolRegistry registry,
            IntentParser parser, AgentProperties props)
    {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.parser = parser;
        this.props = props;
    }

    /** 一句话 → 一次调度动作 */
    @PostMapping("/chat")
    public AjaxResult chat(@RequestBody ChatRequest request)
    {
        ChatReply reply = orchestrator.handle(request, currentUser());
        return AjaxResult.success(reply.toMap());
    }

    /** 工具清单，前端与演示用 */
    @GetMapping("/tools")
    public AjaxResult tools()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentTool t : registry.all())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("title", t.title());
            m.put("description", t.description());
            m.put("sideEffect", t.sideEffect().name());
            m.put("requiresConfirm", t.requiresConfirm());
            list.add(m);
        }
        return AjaxResult.success(list);
    }

    /** 自检：当前跑在什么模式 */
    @GetMapping("/health")
    public AjaxResult health()
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parser", parser.name());
        m.put("llmConfigured", props.getLlm().isEnabled()
                && props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank());
        m.put("llmModel", props.getLlm().getModel());
        m.put("asrEnabled", props.getAsr().isEnabled());
        m.put("tools", registry.size());
        return AjaxResult.success(m);
    }

    private String currentUser()
    {
        try
        {
            String name = SecurityUtils.getUsername();
            return name == null || name.isBlank() ? "anonymous" : name;
        }
        catch (Exception e)
        {
            return "anonymous";
        }
    }
}
