package com.ruoyi.agent.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DeepSeek 薄客户端。
 * <p>
 * 刻意不引入 LangChain4j / Spring AI：这里只需要「发一段话、收一段 JSON」，
 * 三十行足够，换来的是可换厂商、可降级、可移植。
 *
 * @author ruoyi
 */
public class DeepSeekClient
{
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int timeoutSeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DeepSeekClient(String apiKey, String baseUrl, String model, int timeoutSeconds)
    {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "https://api.deepseek.com" : baseUrl.replaceAll("/+$", "");
        this.model = model == null ? "deepseek-chat" : model;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
    }

    public boolean configured()
    {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 单轮对话，返回助手文本与 token 用量。
     */
    public Completion complete(String system, String user) throws Exception
    {
        return complete(system, user, 0, 400);
    }

    /**
     * 单轮对话，可指定温度与最大输出长度。
     * <p>
     * 解析类调用用 0（要稳定），撰写类调用给一点温度（要像人话），但上限压得很低，
     * 免得模型写长文把数字带飘。
     */
    public Completion complete(String system, String user, double temperature, int maxTokens) throws Exception
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        body.put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2)
        {
            throw new IllegalStateException("模型接口返回 " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty())
        {
            throw new IllegalStateException("模型返回中没有 choices");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        int tokens = root.path("usage").path("total_tokens").asInt(0);
        return new Completion(stripFence(content), tokens);
    }

    /** 一次模型调用的结果 */
    public record Completion(String content, int totalTokens)
    {
    }

    private String stripFence(String text)
    {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```"))
        {
            int nl = t.indexOf('\n');
            if (nl > 0)
            {
                t = t.substring(nl + 1);
            }
            if (t.endsWith("```"))
            {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    public ObjectMapper mapper()
    {
        return mapper;
    }
}
