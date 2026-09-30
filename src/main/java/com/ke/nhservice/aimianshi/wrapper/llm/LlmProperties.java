package com.ke.nhservice.aimianshi.wrapper.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private double temperature = 0.7;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 120;

    /**
     * 输出长度上限。0 = **不发** max_tokens 这个字段（用网关的默认值）。
     * 见 OpenAiCompatibleClient 里那段说明：不知道网关默认是多少，硬设可能把它改小。
     */
    private int maxTokens = 0;

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }

    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxTokens() { return maxTokens; }

    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    /** 去掉末尾斜杠，拼 /chat/completions 时不会出现双斜杠 */
    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }
}