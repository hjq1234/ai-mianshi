package com.ke.nhservice.aimianshi.wrapper.llm;

import com.ke.nhservice.aimianshi.common.exception.NonRetryableException;
import com.ke.nhservice.aimianshi.common.exception.RetryableException;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 走 OpenAI 兼容协议的客户端，用 JDK 自带的 HttpClient 实现。
 *
 * 为什么不引第三方 SDK 或 Spring 的 RestClient：
 * 请求就一个 POST + 一个 Bearer 头，JDK HttpClient 零依赖、API 十年不变，
 * 而且 wrapper 包不依赖 Spring 也方便日后单独复用。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final LlmProperties props;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(LlmProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new NonRetryableException(
                    "未配置 LLM api-key，请设置环境变量 DEEPSEEK_API_KEY 或修改 application.yml 的 app.llm.api-key", null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModel());
        payload.put("messages", messages);
        payload.put("temperature", props.getTemperature());
        payload.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(props.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 连接失败 / 读超时 —— 可重试
            throw new RetryableException("调用 LLM 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("调用 LLM 被中断", e);
        }

        int status = response.statusCode();
        long cost = System.currentTimeMillis() - startedAt;

        if (status >= 500 || status == 429) {
            log.warn("LLM 返回 {}，耗时 {} ms，将进入重试", status, cost);
            throw new RetryableException("LLM 返回 " + status + ": " + JsonUtil.abbreviate(response.body()), null);
        }
        if (status >= 400) {
            // 参数错、鉴权错，重试多少次都一样
            throw new NonRetryableException("LLM 返回 " + status + ": " + JsonUtil.abbreviate(response.body()), null);
        }

        log.debug("LLM 调用成功，耗时 {} ms，prompt {} 字符，completion {} 字符",
                cost, JsonUtil.toJson(messages).length(), response.body().length());

        String content = JsonUtil.fromJson(response.body(), ChatCompletionResponse.class).firstContent();
        if (content == null || content.isBlank()) {
            throw new RetryableException("LLM 返回内容为空", null);
        }
        return content;
    }
}