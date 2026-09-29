package com.ke.nhservice.aimianshi.wrapper.asr;

/**
 * 语音识别客户端。业务代码只依赖这个接口，
 * 换引擎（Vosk、或改成 HTTP 调 Python 侧车）不动 controller。
 */
public interface AsrClient {

    /** samples 是 [-1,1] 的 float；sampleRate 必须是模型要的 16000 */
    String transcribe(float[] samples, int sampleRate);

    AsrStatus status();
}