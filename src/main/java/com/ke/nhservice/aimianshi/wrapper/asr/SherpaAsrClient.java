package com.ke.nhservice.aimianshi.wrapper.asr;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.VersionInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sherpa-onnx + SenseVoiceSmall 的实现。
 *
 * 三处写错了不好查的地方：
 *  1. **构造 recognizer 之前必须先 Files.isRegularFile 检查模型**。native 侧读不到模型
 *     不保证抛 Java 异常，可能直接把进程带走 —— 不能靠 try/catch 兜。
 *     所以可用性判断走的是「文件在不在」，不是「试着构造一下看看」。
 *  2. **每次转写完 stream.release()**。stream 是每次新建的，不释放就是一次漏一份
 *     native 内存。10 题看不出来，跑一天就明显了。
 *  3. **catch Throwable 而不是 Exception**。UnsatisfiedLinkError 是 Error 的子类，
 *     catch (Exception) 抓不到，会一路冒到最外层变成 500。
 */
public class SherpaAsrClient implements AsrClient {

    private static final Logger log = LoggerFactory.getLogger(SherpaAsrClient.class);

    private final AsrProperties props;

    /** 228 MB 的模型，启动时建会让启动慢十几秒，而大多数人不用语音 → 第一次用到才建 */
    private volatile OfflineRecognizer recognizer;

    public SherpaAsrClient(AsrProperties props) {
        this.props = props;
    }

    @Override
    public AsrStatus status() {
        // VersionInfo 会触发 native 库加载（LibraryLoader.maybeLoad）。
        // 它返回得了就说明 dll 真加载上了 —— 排 UnsatisfiedLinkError 最省事的一招，
        // 不用猜是「dll 没找到」还是「onnxruntime 冲突」还是「版本对不上」
        String version;
        try {
            version = VersionInfo.getVersion();
        } catch (Throwable t) {
            return new AsrStatus(false, "native 库没加载上：" + t, null, props.getMaxSeconds());
        }

        Path model = props.modelPath();
        if (model == null) {
            return new AsrStatus(false, "没有配置 app.asr.model-dir（环境变量 APP_ASR_MODEL_DIR）",
                    version, props.getMaxSeconds());
        }
        if (!Files.isRegularFile(model)) {
            return new AsrStatus(false, "模型文件不存在：" + model, version, props.getMaxSeconds());
        }
        Path tokens = props.tokensPath();
        if (!Files.isRegularFile(tokens)) {
            return new AsrStatus(false, "tokens.txt 不存在：" + tokens, version, props.getMaxSeconds());
        }
        return new AsrStatus(true, null, version, props.getMaxSeconds());
    }

    /**
     * 整个方法加锁，不是只锁 decode：native 侧不保证可重入，
     * 而单用户场景下并发转写本来也不发生，加锁零成本。
     * 副作用是「第一次请求要把模型加载串行化」——这本来就该串行。
     */
    @Override
    public synchronized String transcribe(float[] samples, int sampleRate) {
        OfflineRecognizer r = recognizer();
        OfflineStream stream = r.createStream();
        try {
            stream.acceptWaveform(samples, sampleRate);
            r.decode(stream);
            OfflineRecognizerResult result = r.getResult(stream);
            String text = result.getText().trim();
            // 打的是语言和字数，**不打文本本身** —— 回答是候选人的隐私内容，
            // RequestLogFilter 连请求体都不记，这里更不能记
            log.debug("ASR 转写 | 语言={} | {} 字符", result.getLang(), text.length());
            return text;
        } finally {
            stream.release();
        }
    }

    /** 双检锁。transcribe 已经 synchronized，这里只是别重复构造 */
    private OfflineRecognizer recognizer() {
        OfflineRecognizer r = recognizer;
        if (r == null) {
            r = build();
            recognizer = r;
        }
        return r;
    }

    private OfflineRecognizer build() {
        OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
                .setModel(props.modelPath().toString())
                .setLanguage(props.getLanguage())
                .setInverseTextNormalization(props.isInverseTextNormalization())
                .build();

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setSenseVoice(senseVoice)
                .setTokens(props.tokensPath().toString())
                .setNumThreads(props.getNumThreads())
                .setDebug(props.isDebug())
                .build();

        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                // greedy_search 是 sherpa-onnx 的默认解码方式，SenseVoice 用它就够了，
                // 配 beam search 只会更慢
                .setDecodingMethod("greedy_search")
                .build();

        long startedAt = System.currentTimeMillis();
        OfflineRecognizer r = new OfflineRecognizer(config);
        log.info("ASR 模型加载完成 | {} ms | 线程数={} | {}",
                System.currentTimeMillis() - startedAt, props.getNumThreads(), props.modelPath());
        return r;
    }

    @PreDestroy
    public void close() {
        OfflineRecognizer r = recognizer;
        if (r != null) {
            r.release();
            log.info("ASR 模型已释放");
        }
    }
}