package com.ke.nhservice.aimianshi.wrapper.asr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 语音识别配置。默认值都是「没配也能起，只是语音不可用」——
 * 模型是 228 MB，绝大多数环境不会预置，不能因为它缺失就让整个应用起不来。
 *
 * 模型目录从 application.yml 的 app.asr.model-dir 读，那一行本身又是
 * ${APP_ASR_MODEL_DIR:D:/models/sense-voice} 的形式，所以两种都能用：
 *   IDEA 直接 Run（yml 里的默认值生效）／ set APP_ASR_MODEL_DIR=... （环境变量赢过 yml）
 * 这里字段默认值留空是刻意的：yml 里那行才是「默认值」的唯一出处，
 * 两边都写一个真实路径的话，改一处忘一处就会出现「yml 明明改了却不生效」
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
    /**
     * 单次转写音频上限（秒）。前端录音到点自动停，服务端拿它当守卫拒超长请求。
     * 换算：16k 单声道 PCM16 是 32 KB/秒，所以上限秒数 × 32 ≈ 请求体 KB。
     * 这个值前端**不自己写**，/api/asr/status 会带过去（见 AsrApiCheck）。
     */
    private int maxSeconds = 240;
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

    /** 流式那条路的配置。整段嵌套，不和外层那几个共用 */
    private Stream stream = new Stream();

    public Stream getStream() { return stream; }

    public void setStream(Stream stream) { this.stream = stream; }

    /**
     * 流式识别的配置（zipformer transducer）。
     *
     * **文件名有默认值、目录没有** —— 这个分工是刻意的：
     *   文件名 跟**模型**绑定，对所有人一样，写在这儿（换了模型才需要动）
     *   目录   跟**机器**绑定，只能放 application.yml 的 ${环境变量:默认路径}，或者环境变量
     * 文件名本来也想放 yml 保持「默认值只有一个出处」，但 yml 里有刻意保留的本地改动不能提交，
     * 塞进去的后果是：换台机器的人把模型下了、环境变量也设了，还是 streamAvailable:false，
     * 而原因是几个文件名没跟着走 —— 这种坑不值得为对称性去踩。
     */
    public static class Stream {

        /** 模型目录，放仓库外。空 = 没配 → streamAvailable:false，页面不显示流式这个选项 */
        private String modelDir = "";
        /** zipformer 是三段式的：encoder / decoder / joiner。三件套统一 int8，合计 24 MB。
         *  识别不准时第一件事就是把 decoder 换成 fp32（decoder-epoch-99-avg-1.onnx），只改这一行 */
        private String encoderFile = "encoder-epoch-99-avg-1.int8.onnx";
        private String decoderFile = "decoder-epoch-99-avg-1.int8.onnx";
        private String joinerFile = "joiner-epoch-99-avg-1.int8.onnx";
        private String tokensFile = "tokens.txt";
        /** 解码线程数。流式是每 100ms 解一次，给 2 个够 */
        private int numThreads = 2;
        /** 会话空闲多久回收（秒）。不回收就是漏 native 内存 */
        private int idleSeconds = 120;
        /** 同时在跑的会话上限。单用户自用，给 4 个意思一下 */
        private int maxSessions = 4;

        public Path encoderPath() { return file(encoderFile); }

        public Path decoderPath() { return file(decoderFile); }

        public Path joinerPath() { return file(joinerFile); }

        public Path tokensPath() { return file(tokensFile); }

        private Path file(String name) {
            return modelDir == null || modelDir.isBlank() ? null : Path.of(modelDir.trim(), name);
        }

        public String getModelDir() { return modelDir; }

        public void setModelDir(String modelDir) { this.modelDir = modelDir; }

        public String getEncoderFile() { return encoderFile; }

        public void setEncoderFile(String encoderFile) { this.encoderFile = encoderFile; }

        public String getDecoderFile() { return decoderFile; }

        public void setDecoderFile(String decoderFile) { this.decoderFile = decoderFile; }

        public String getJoinerFile() { return joinerFile; }

        public void setJoinerFile(String joinerFile) { this.joinerFile = joinerFile; }

        public String getTokensFile() { return tokensFile; }

        public void setTokensFile(String tokensFile) { this.tokensFile = tokensFile; }

        public int getNumThreads() { return numThreads; }

        public void setNumThreads(int numThreads) { this.numThreads = numThreads; }

        public int getIdleSeconds() { return idleSeconds; }

        public void setIdleSeconds(int idleSeconds) { this.idleSeconds = idleSeconds; }

        public int getMaxSessions() { return maxSessions; }

        public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
    }
}