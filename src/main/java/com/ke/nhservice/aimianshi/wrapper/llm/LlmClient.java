package com.ke.nhservice.aimianshi.wrapper.llm;

import java.util.List;

/**
 * 大模型客户端。业务代码只依赖这个接口，
 * 换厂商 = 换实现类，不动业务代码。
 */
public interface LlmClient {

    /**
     * 一次调用的完整结果：正文 + 为什么停下。
     *
     * ★ 为什么要多这一个返回值：改简历那条链的产物长（一整份改写稿），最容易撞输出长度上限，
     *   而「被截断」必须能报出来——否则现象是「参考稿戛然而止」，看着像模型笨。
     *   原先 chat() 只返回 String，这个信息在 OpenAiCompatibleClient 里就被丢掉了。
     */
    record Reply(String content, String finishReason) {

        /**
         * 撞到输出长度上限。
         * 网关不一定给 finish_reason，给 null 时按「没截断」处理——
         * 不能反过来假设被截断了，那会把正常的长回答也标成截断。
         */
        public boolean truncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }

    Reply chatDetailed(List<ChatMessage> messages);

    default String chat(List<ChatMessage> messages) {
        return chatDetailed(messages).content();
    }

    /** 单轮对话的便捷方法：把 prompt 当作一条 user 消息 */
    default String chat(String prompt) {
        return chat(List.of(ChatMessage.user(prompt)));
    }
}