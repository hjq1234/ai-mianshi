package com.ke.nhservice.aimianshi.wrapper.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ke.nhservice.aimianshi.common.exception.NonRetryableException;

import java.util.List;

/**
 * OpenAI 兼容的 /chat/completions 响应。
 * 只声明用得到的字段，其余靠 @JsonIgnoreProperties 忽略——
 * 各家厂商都会塞一堆自己的扩展字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    /** 取第一个 choice 的正文；结构不对就抛 NonRetryableException */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            throw new NonRetryableException("LLM 响应结构异常：没有 choices[0].message", null);
        }
        return choices.get(0).message().content();
    }
}