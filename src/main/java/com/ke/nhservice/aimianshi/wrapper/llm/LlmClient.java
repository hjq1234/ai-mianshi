package com.ke.nhservice.aimianshi.wrapper.llm;

import java.util.List;

/**
 * 大模型客户端。业务代码只依赖这个接口，
 * 换厂商 = 换实现类，不动业务代码。
 */
public interface LlmClient {

    String chat(List<ChatMessage> messages);

    /** 单轮对话的便捷方法：把 prompt 当作一条 user 消息 */
    default String chat(String prompt) {
        return chat(List.of(ChatMessage.user(prompt)));
    }
}