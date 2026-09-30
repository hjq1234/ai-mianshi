package com.ke.nhservice.aimianshi.wrapper.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    public record Choice(Message message,

                         // ★ 必须显式写 @JsonProperty。项目里没有配 property-naming-strategy
                         //   （grep 过：全项目一个 @JsonProperty 都没有），Jackson 走默认的 camelCase，
                         //   声明成 finishReason 是**绑不上** "finish_reason" 的——
                         //   结果是 truncated 永远是 0、截断提示永不出现，而且不报任何错。
                         //   这个类原有的 role / content / message 都是单个词，看不出这个问题，
                         //   finish_reason 是这里第一个多词字段。
                         @JsonProperty("finish_reason") String finishReason) {
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

    /** 为什么停下。"length" = 撞到输出长度上限被截断。网关不给这个字段时为 null */
    public String firstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).finishReason();
    }
}