package com.ke.nhservice.aimianshi.wrapper.asr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 语音识别配置。默认值都是「没配也能起，只是语音不可用」——
 * 模型是 228 MB，绝大多数环境不会预置，不能因为它缺失就让整个应用起不来。
 *
 * 本地覆盖用环境变量，不动 application.yml：
 *   set APP_ASR_MODEL_DIR=D:/models/sense-voice
 */
@Component
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {

    /** 模型目录，放仓库外。空 = 没配 → /status 返 available:false，页面不显示麦克风 */
    private String modelDir = "";
    /** int8 量化版。fp32 的 model.onnx 是 894 MB，效果差异这个场景听不出来 */
    private String modelFile = "model.int8.onnx";
    private String tokensFile = "tokens.txt";
    /** 解码线程数。一次只解一段音频，给 4 个足够（60 秒音频远低于 1 秒解完） */
    private int numThreads = 4;
    /** 单次转写音频上限（秒）。16k 单声道 PCM16 是 32 KB/秒，120 秒 ≈ 3.8 MB */
    private int maxSeconds = 120;
    /** SenseVoice 支持 zh / en / ja / ko / yue。面试是中文，锁定 zh 免得短句被误判成英文 */
    private String language = "zh";
    /** 逆文本正则化：把「二零二五」写成「2025」。转写文本要喂给 LLM 评分，数字更好读 */
    private boolean inverseTextNormalization = true;
    /** sherpa-onnx 自己的调试输出（native 加载、模型解析都会打）。排 UnsatisfiedLinkError 时开 */
    private boolean debug = false;

    public String getModelDir() { return modelDir; }

    public void setModelDir(String modelDir) { this.modelDir = modelDir; }

    public String getModelFile() { return modelFile; }

    public void setModelFile(String modelFile) { this.modelFile = modelFile; }

    public String getTokensFile() { return tokensFile; }

    public void setTokensFile(String tokensFile) { this.tokensFile = tokensFile; }

    public int getNumThreads() { return numThreads; }

    public void setNumThreads(int numThreads) { this.numThreads = numThreads; }

    public int getMaxSeconds() { return maxSeconds; }

    public void setMaxSeconds(int maxSeconds) { this.maxSeconds = maxSeconds; }

    public String getLanguage() { return language; }

    public void setLanguage(String language) { this.language = language; }

    public boolean isInverseTextNormalization() { return inverseTextNormalization; }

    public void setInverseTextNormalization(boolean inverseTextNormalization) {
        this.inverseTextNormalization = inverseTextNormalization;
    }

    public boolean isDebug() { return debug; }

    public void setDebug(boolean debug) { this.debug = debug; }

    /** 模型文件路径。modelDir 没配时返回 null —— 调用方必须先看 status() */
    public Path modelPath() {
        return modelDir == null || modelDir.isBlank() ? null : Path.of(modelDir.trim(), modelFile);
    }

    /** tokens.txt 路径。同上 */
    public Path tokensPath() {
        return modelDir == null || modelDir.isBlank() ? null : Path.of(modelDir.trim(), tokensFile);
    }
}