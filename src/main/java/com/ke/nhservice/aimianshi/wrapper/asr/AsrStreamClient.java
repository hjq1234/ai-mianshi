package com.ke.nhservice.aimianshi.wrapper.asr;

/**
 * 流式语音识别。和 AsrClient（离线整段）平级，互不依赖 ——
 * 换引擎（换模型、改成 HTTP 调 Python 侧车）不动 controller。
 *
 * 返回值刻意不用异常表达「用户能看懂的失败」：
 *   start() 返回 null  = 会话数到上限了
 *   has(id) 返回 false = 这个会话不存在或者已经过期
 * 具体回什么话由 controller 决定 —— 那层才该知道「怎么跟用户说」。
 * 这和 AsrController 里那几句 BizException 是同一个分工。
 */
public interface AsrStreamClient {

    /** 开会话。返回 null 表示到上限了（调用方要回一句人话，不能返 null 给前端） */
    String start();

    /** 这个会话还在不在。过期和不存在都算 false */
    boolean has(String sessionId);

    /** 喂一片音频，拿回当前的半句和刚定稿的那句 */
    StreamChunk chunk(String sessionId, float[] samples, int sampleRate);

    /** 结束会话并释放 native 资源，返回最后这句。会话随即销毁 */
    String stop(String sessionId);

    AsrStatus status();
}