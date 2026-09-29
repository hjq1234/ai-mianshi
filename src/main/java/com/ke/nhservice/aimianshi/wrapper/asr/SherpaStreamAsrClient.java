package com.ke.nhservice.aimianshi.wrapper.asr;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.VersionInfo;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sherpa-onnx + streaming zipformer 的实现。
 *
 * 和 SherpaAsrClient（离线那条）是同一个 jar、同一套 native 库，
 * 那三条容易踩的坑这里一模一样：
 *  1. 构造 recognizer 之前先 Files.isRegularFile —— native 侧读不到模型不保证抛 Java 异常
 *  2. 会话结束必须 stream.release() —— 一次漏一份 native 内存
 *  3. catch Throwable 而不是 Exception —— UnsatisfiedLinkError 是 Error 的子类
 * 外加这条独有的两条：
 *  4. **会话要过期回收**。离线那条是「一个请求一个 stream，用完即弃」，
 *     流式是「一个会话跨几十个请求」，漏一个就是漏一份，所以有 sweepExpired()
 *  5. **识别器不是线程安全的**，整段 decode 加锁。单用户下并发本来也不发生，
 *     加锁零成本；不加的话两个请求同时 decode 是 native 层踩内存
 */
public class SherpaStreamAsrClient implements AsrStreamClient {

    private static final Logger log = LoggerFactory.getLogger(SherpaStreamAsrClient.class);

    private final AsrProperties props;

    /** 24 MB 的模型，但仍然懒加载：不用流式的人不该付这个启动时间 */
    private volatile OnlineRecognizer recognizer;

    /** sessionId → 会话。ConcurrentHashMap 是因为 sweep 和业务请求会并发碰它 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SherpaStreamAsrClient(AsrProperties props) {
        this.props = props;
    }

    @Override
    public AsrStatus status() {
        // VersionInfo 会触发 native 库加载，排 UnsatisfiedLinkError 最省事的一招
        String version;
        try {
            version = VersionInfo.getVersion();
        } catch (Throwable t) {
            return new AsrStatus(false, "native 库没加载上：" + t, null, 0);
        }

        AsrProperties.Stream s = props.getStream();
        if (s.getModelDir() == null || s.getModelDir().isBlank()) {
            return new AsrStatus(false, "没有配置 app.asr.stream.model-dir"
                    + "（环境变量 APP_ASR_STREAM_MODEL_DIR）", version, 0);
        }
        // 逐个文件说清楚缺哪个。笼统回一句「模型不存在」，遇到「decoder 忘了拷」时会白查半天
        Path[] files = {s.encoderPath(), s.decoderPath(), s.joinerPath(), s.tokensPath()};
        for (Path f : files) {
            if (f == null || !Files.isRegularFile(f)) {
                return new AsrStatus(false, "流式模型文件不存在：" + f, version, 0);
            }
        }
        return new AsrStatus(true, null, version, 0);
    }

    @Override
    public String start() {
        if (!status().available()) {
            // controller 会先看 status，正常到不了这儿；到了说明有人绕过它调
            return null;
        }
        sweepExpired();
        if (sessions.size() >= props.getStream().getMaxSessions()) {
            log.warn("流式会话数到上限（{}），拒绝新会话。多半是前端没发 stop，等过期回收",
                    props.getStream().getMaxSessions());
            return null;
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        sessions.put(id, new Session(recognizer().createStream()));
        log.info("流式会话开始 | {} | 当前会话数={}", id, sessions.size());
        return id;
    }

    @Override
    public boolean has(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * 整个方法 synchronized：native 侧不保证可重入（和 SherpaAsrClient.transcribe 同一个理由）。
     *
     * **顺序由调用方保证**：音频是时序数据，两个 chunk 乱序到达 = 音频被搅乱，
     * 转出来是一段流利但完全不对的中文 —— 不会报错。所以前端是「等上一个响应回来才发下一个」，
     * 服务端这边加锁只是别让两个请求同时进 native。
     */
    @Override
    public synchronized StreamChunk chunk(String sessionId, float[] samples, int sampleRate) {
        Session s = sessions.get(sessionId);
        if (s == null) {
            // 过期了。返回空而不是抛异常：前端那一刻可能正在写最后一片，
            // 抛出去会变成一个红条，而这其实只是「这次录音太久没人说话，重录一次」
            return StreamChunk.EMPTY;
        }
        s.touchedAt = System.currentTimeMillis();

        OnlineRecognizer r = recognizer();
        s.stream.acceptWaveform(samples, sampleRate);
        // isReady 为 false 说明这次没攒够一帧特征，直接返回等下一片 —— 这正是流式该有的行为
        while (r.isReady(s.stream)) {
            r.decode(s.stream);
        }

        String partial = r.getResult(s.stream).getText().trim();

        if (r.isEndpoint(s.stream)) {
            // ★ 先取结果再 reset，顺序反了拿到的是 reset 之后那个空串
            r.reset(s.stream);
            if (partial.isEmpty()) {
                return StreamChunk.EMPTY;
            }
            // 定稿这句不再有 partial：reset 之后 stream 是干净的，
            // 前端那边 partial 也该清空，否则灰字行会挂着上一句不放
            log.debug("流式定稿 | {} | {} 字符", sessionId, partial.length());
            return new StreamChunk("", partial);
        }
        return new StreamChunk(partial, "");
    }

    @Override
    public synchronized String stop(String sessionId) {
        Session s = sessions.remove(sessionId);
        if (s == null) {
            return "";
        }
        try {
            OnlineRecognizer r = recognizer();
            // 最后一片还没解完的部分：inputFinished 之后要把剩下的帧解掉，
            // 不然**最后几个字会丢**（丢的是半句的尾巴，看着像识别不准，其实是被截了）
            s.stream.inputFinished();
            while (r.isReady(s.stream)) {
                r.decode(s.stream);
            }
            String text = r.getResult(s.stream).getText().trim();
            log.info("流式会话结束 | {} | {} 字符", sessionId, text.length());
            return text;
        } finally {
            // finally 不是保险起见：上面任何一步抛了，不 release 就是永久漏一份 native 内存，
            // 而会话已经从 map 里摘掉了，再也没人会去 release 它
            s.stream.release();
        }
    }

    /** 过了 idleSeconds 没动过的会话回收掉。懒清理，不引 @Scheduled */
    private void sweepExpired() {
        long deadline = System.currentTimeMillis() - props.getStream().getIdleSeconds() * 1000L;
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue().touchedAt < deadline) {
                dead.add(e.getKey());
            }
        }
        for (String id : dead) {
            // 走 stop 而不是直接 release：stop 会把最后半句也解出来 —— 用户可能只是
            // 网络卡了一下，最后那句还是想要的。而且 remove + release 是一套的
            stop(id);
            log.info("流式会话过期回收 | {} | 空闲超过 {} 秒", id, props.getStream().getIdleSeconds());
        }
    }

    /** 双检锁。chunk/stop 已经 synchronized，这里只是别重复构造 */
    private OnlineRecognizer recognizer() {
        OnlineRecognizer r = recognizer;
        if (r == null) {
            r = build();
            recognizer = r;
        }
        return r;
    }

    private OnlineRecognizer build() {
        AsrProperties.Stream s = props.getStream();

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(s.encoderPath().toString())
                .setDecoder(s.decoderPath().toString())
                .setJoiner(s.joinerPath().toString())
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(s.tokensPath().toString())
                .setNumThreads(s.getNumThreads())
                .setProvider("cpu")
                // 刻意不设 setModelType：transducer 三段式的模型不写它也能起来，
                // 写错了反而会被当成另一种架构去解（报的错还很难懂）。
                // 真起不来的话第一件事就是试 setModelType("zipformer")
                .build();

        FeatureConfig feature = FeatureConfig.builder()
                .setSampleRate(16000)
                .setFeatureDim(80)
                .build();

        // 断句规则**显式写出来**，不吃库里的默认值：
        // 默认 rule1 是「停顿 2.4 秒才算一句」，面试答题时想词、组织语言很容易超过 ——
        // 那样就成了「说半天一个字都不进框」，看着像坏了。
        // 这里给 1.5 秒，而且要求「这段时间里确实说过话」，否则全程静音也会不停定稿空句子。
        // 这两个数是**实测调出来的**，别照抄别处的值
        EndpointRule rule1 = EndpointRule.builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(1.5f)
                .setMinUtteranceLength(0f)
                .build();
        // rule2 收紧到基本不触发：它是「更短的停顿也算一句」，对连续说话的场景只会把句子切碎。
        // 卡在 rule1 和 rule2 中间那档停顿（1.5~3 秒）会被判成「还在一句里」，
        // 表现是灰字行里那句话慢慢变长 —— 这是想要的行为
        EndpointRule rule2 = EndpointRule.builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(3.0f)
                .setMinUtteranceLength(0f)
                .build();

        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setFeatureConfig(feature)
                .setOnlineModelConfig(modelConfig)
                .setEnableEndpoint(true)
                .setEndpointConfig(EndpointConfig.builder().setRule1(rule1).setRule2(rule2).build())
                .setDecodingMethod("greedy_search")
                .build();

        long startedAt = System.currentTimeMillis();
        OnlineRecognizer r = new OnlineRecognizer(config);
        log.info("流式 ASR 模型加载完成 | {} ms | 线程数={} | {}",
                System.currentTimeMillis() - startedAt, s.getNumThreads(), s.getModelDir());
        return r;
    }

    @PreDestroy
    public void close() {
        // 先清会话再放识别器：反过来的话 stop() 会在已经 release 的 recognizer 上解最后一片
        for (String id : new ArrayList<>(sessions.keySet())) {
            stop(id);
        }
        OnlineRecognizer r = recognizer;
        if (r != null) {
            r.release();
            log.info("流式 ASR 模型已释放");
        }
    }

    /** 一个会话 = 一个 OnlineStream + 最后一次活动时间 */
    private static final class Session {
        final OnlineStream stream;
        volatile long touchedAt = System.currentTimeMillis();

        Session(OnlineStream stream) {
            this.stream = stream;
        }
    }
}