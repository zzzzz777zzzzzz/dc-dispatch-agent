package com.ruoyi.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能体配置。全部可通过 application.yml 覆盖，不写死在代码里。
 *
 * @author ruoyi
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties
{
    private final Llm llm = new Llm();
    private final Asr asr = new Asr();

    public Llm getLlm()
    {
        return llm;
    }

    public Asr getAsr()
    {
        return asr;
    }

    /** 大模型配置 */
    public static class Llm
    {
        /** 是否启用；关闭后只用规则解析 */
        private boolean enabled = true;
        /** API Key，建议用环境变量 DEEPSEEK_API_KEY 注入，不要写进仓库 */
        private String apiKey = "";
        /** OpenAI 兼容地址 */
        private String baseUrl = "https://api.deepseek.com";
        /** 模型名 */
        private String model = "deepseek-chat";
        /** 超时秒数 */
        private int timeoutSeconds = 20;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getApiKey()
        {
            return apiKey;
        }

        public void setApiKey(String apiKey)
        {
            this.apiKey = apiKey;
        }

        public String getBaseUrl()
        {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl)
        {
            this.baseUrl = baseUrl;
        }

        public String getModel()
        {
            return model;
        }

        public void setModel(String model)
        {
            this.model = model;
        }

        public int getTimeoutSeconds()
        {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds)
        {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** 语音识别配置，一期只留接口 */
    public static class Asr
    {
        private boolean enabled = false;
        private String provider = "browser";

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getProvider()
        {
            return provider;
        }

        public void setProvider(String provider)
        {
            this.provider = provider;
        }
    }
}
