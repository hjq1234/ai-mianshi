package com.ke.nhservice.aimianshi.wrapper.asr;

/**
 * 一次 chunk 的识别结果。
 *
 * partial 和 finalText 是两件事，不能合并：
 *   partial   —— 正在说的这半句，**下一个 chunk 会把它改写掉**
 *                （「我要用 redis」→「我要用 Redis 做缓存」）
 *   finalText —— 检测到说话停顿，这句定稿了，不会再变，前端可以追加进答题框
 *
 * 合并成一个字段的话，前端只能猜「这个字数是变多了还是说完了」——
 * 猜错的后果是把半句追加进框，然后下一个 chunk 又追加一次，框里出现两遍。
 *
 * finalText 非空**不代表会话结束**：用户还在说下一句，会话继续活着。
 * 结束是 stop()，那时才销毁会话。
 */
public record StreamChunk(String partial, String finalText) {

    /** 两边都空：这段还没识别出字（刚开口、或者全是静音） */
    public static final StreamChunk EMPTY = new StreamChunk("", "");
}