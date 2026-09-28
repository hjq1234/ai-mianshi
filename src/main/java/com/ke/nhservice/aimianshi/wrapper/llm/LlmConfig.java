package com.ke.nhservice.aimianshi.wrapper.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /**
     * 装配顺序：重试装饰器在最外层，真实客户端在里面。
     * 注意是 new RetryableLlmClient(new OpenAiCompatibleClient(props))——
     * 装饰器包在外层，才能拦住内层抛出的 RetryableException。
     */
    @Bean
    public LlmClient llmClient(LlmProperties props) {
        return new RetryableLlmClient(new OpenAiCompatibleClient(props));
    }
}