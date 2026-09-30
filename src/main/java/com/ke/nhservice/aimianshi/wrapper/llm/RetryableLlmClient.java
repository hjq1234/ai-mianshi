package com.ke.nhservice.aimianshi.wrapper.llm;

import com.ke.nhservice.aimianshi.common.exception.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 重试装饰器：首次失败后重试 4 次，共最多 5 次调用。
 *
 * 只捕获 RetryableException（超时 / 连接失败 / 5xx / 429）。
 * NonRetryableException（4xx）直接穿透，不做无谓等待。
 *
 * 已知影响：最坏情况光等待就是 75 秒，加上 5 次调用耗时，单次请求可能超过 2 分钟。
 * 前端 loading 文案要写明这一点。
 */
public class RetryableLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryableLlmClient.class);

    /** 第 1 次失败后等 5s，第 2 次等 10s，第 3 次等 20s，第 4 次等 40s */
    private static final long[] BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L};

    private final LlmClient delegate;

    public RetryableLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    /**
     * 重试逻辑只有这一份。
     *
     * ★ 不要在这里再覆写 chat(List)：接口上那个 default chat() 就是转发到这里的，
     *   另写一份等于同一个类里有两套重试代码，改一处漏一处。
     */
    @Override
    public Reply chatDetailed(List<ChatMessage> messages) {
        for (int attempt = 0; ; attempt++) {
            try {
                return delegate.chatDetailed(messages);
            } catch (RetryableException e) {
                if (attempt >= BACKOFF_MS.length) {
                    log.error("LLM 调用失败 {} 次后放弃: {}", attempt + 1, e.getMessage());
                    throw e;
                }
                long waitMs = BACKOFF_MS[attempt];
                log.warn("LLM 调用失败（第 {} 次），{} ms 后重试: {}", attempt + 1, waitMs, e.getMessage());
                sleep(waitMs);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("重试等待被中断", e);
        }
    }
}