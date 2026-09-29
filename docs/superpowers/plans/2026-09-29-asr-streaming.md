# 流式语音识别实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留现有离线语音答题的前提下，新增一条流式识别路（边说边出字），页面可切换。

**Architecture:** 服务端加一个 `AsrStreamClient`（和 `AsrClient` 平级，不动后者）+ 会话管理，
三个分片 POST 接口；前端加一个 AudioWorklet 采集实时 PCM，和现有 `MediaRecorder` 那条并存。
分片通信而不是 WebSocket —— 零新依赖、鉴权和日志白捡。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / sherpa-onnx v1.13.8（**不新增任何依赖**）/ Vue 3 CDN 版

**设计依据：** `docs/superpowers/specs/2026-09-29-asr-streaming-design.md`

**用户已拍板的四件事：**
1. 传输用**分片 POST**（不用 WebSocket）
2. 模型用 **zipformer-zh-14M**
3. 流式中间态显示在**麦克风上方一行灰字**，停顿定稿才追加进框
4. **默认流式**

**既有约束（仍然有效）：**
- 只 commit，**不 push**
- `git add` 只写显式路径，**永不** `git add -A`
- `src/main/resources/application.yml` **不能提交**（里面有刻意保留的 `base-url` / `model` 本地改动）
- 不写 JUnit。验证程序放 `src/test/java/.../checks/`，能直接 `main` 跑
- 每条构建命令都要 `export JAVA_HOME="/c/Program Files/Java/jdk-21"`
- 全程中文

---

## 前置：模型文件（已就位）

**实测确认，已经就位**：

```
D:/models/streaming-zh-14M/
├── encoder-epoch-99-avg-1.int8.onnx    21.6 MB
├── decoder-epoch-99-avg-1.int8.onnx     1.89 MB
├── joiner-epoch-99-avg-1.int8.onnx      1.80 MB
├── tokens.txt                           48.7 KB
└── test_wavs/{0.wav,1.wav}                       ← 从解压目录拷来的，验证程序用它
```

合计 **24.1 MB**（SenseVoice 那个是 228 MB，不是一个量级）。
上游的 tarball 原样解在 `D:/models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/`，
里面还有 fp32 的三个 `.onnx`（合计 55 MB，用不上）和 `export-onnx-zh-14M.sh`；
`streaming-zh-14M/` 是从那儿挑出来的 int8 那套 + 测试音频。那个长名字的目录删掉也不影响。

两处和最初设想不一样的地方：

1. **decoder 也有 int8 版**（1.89 MB，原以为没有）。三件套统一用 int8。
   觉得识别不准时第一件事就是把 decoder 换成 fp32 那个（拷贝 + 改一行配置，7.5 MB）
2. **模型文件名的默认值放 Java 字段，不放 application.yml**。文件名是跟模型绑定的、
   对所有人一样；只有**目录**是跟机器绑定的。而 `application.yml` 因为里面那两行本地改动
   不能提交（见下面的约束）—— 文件名也塞进去的话，换台机器的人光设环境变量还是跑不起来

---

## Task 1: 流式识别客户端（wrapper 层）

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrStreamClient.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/StreamChunk.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/SherpaStreamAsrClient.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrProperties.java`

- [ ] **Step 1: 确认模型文件在位**

```bash
ls -la /d/models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/
```

已经确认过了（见开头「前置」）。这一步只是每次换机器时重新核对一眼 ——
文件名不对的表现是 `status()` 返回 `available:false` + 一句「流式模型文件不存在：<完整路径>」，
**不会崩**，所以很容易被当成「模型没下」而白查。

- [ ] **Step 2: `StreamChunk`**

一次 chunk 的返回。**两个字段都要有**：`partial` 是「正在说的这半句」（会被后续 chunk 改写），
`finalText` 是「刚检测到一个停顿、这句定稿了」。
混成一个字段的话，前端没法区分「这句话说完了」和「这句话还在变」。

```java
package com.ke.nhservice.aimianshi.wrapper.asr;

/**
 * 一次 chunk 的识别结果。
 *
 * partial 和 finalText 是两件事，不能合并：
 *   partial   —— 正在说的这半句，**下一个 chunk 会把它改写掉**（「我要用 redis」→「我要用 Redis 做缓存」）
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
```

- [ ] **Step 3: `AsrStreamClient` 接口**

```java
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

    /** 这个会话还在不在。过期/不存在都算 false */
    boolean has(String sessionId);

    /** 喂一片音频，拿回当前的半句和刚定稿的那句 */
    StreamChunk chunk(String sessionId, float[] samples, int sampleRate);

    /** 结束会话并释放 native 资源，返回最后这句。会话随即销毁 */
    String stop(String sessionId);

    AsrStatus status();
}
```

- [ ] **Step 4: `AsrProperties` 加嵌套的 stream 段**

`app.asr.stream.*`。嵌套而不是把字段摊平在 `AsrProperties` 上：流式那套字段
（encoder/decoder/joiner 三个文件名、会话上限）和离线那套（modelFile、language、
inverseTextNormalization）没有一个是共用的，摊平会让人以为改一个会影响另一个。

```java
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
     * 文件名本来也想放 yml 保持「默认值只有一个出处」，但 yml 因为那两行本地改动不能提交
     * （见计划开头的约束），塞进去的后果是：换台机器的人把模型下了、环境变量也设了，
     * 还是 `streamAvailable:false`，而原因是几个文件名没跟着走 —— 这种坑不值得为对称性去踩。
     */
    public static class Stream {

        /** 模型目录，放仓库外。空 = 没配 → streamAvailable:false，页面不显示流式这个选项 */
        private String modelDir = "";
        /** zipformer 是三段式的：encoder / decoder / joiner。三件套统一 int8，合计 24 MB。
         *  识别不准时第一件事就是把 decoder 换成 fp32（decoder-...onnx），只改这一行 */
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
        public Path joinerPath()  { return file(joinerFile); }
        public Path tokensPath()  { return file(tokensFile); }

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
```

- [ ] **Step 5: `SherpaStreamAsrClient`**

```java
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
 * 所有容易踩的坑也都一样：
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

    /** 30 MB 级的模型，但仍然懒加载：不用流式的人不该付这个启动时间 */
    private volatile OnlineRecognizer recognizer;

    /** sessionId → 会话。ConcurrentHashMap 是因为 sweep 和业务请求会并发碰它 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SherpaStreamAsrClient(AsrProperties props) {
        this.props = props;
    }

    @Override
    public AsrStatus status() {
        String version;
        try {
            version = VersionInfo.getVersion();
        } catch (Throwable t) {
            return new AsrStatus(false, "native 库没加载上：" + t, null, 0);
        }

        AsrProperties.Stream s = props.getStream();
        if (s.getModelDir() == null || s.getModelDir().isBlank()) {
            return new AsrStatus(false, "没有配置 app.asr.stream.model-dir（环境变量 APP_ASR_STREAM_MODEL_DIR）",
                    version, 0);
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
            // 最后一个字符是空格（sherpa 的中文结果常带尾空格），不去掉的话
            // 前端拼出来是「我要用 Redis 做缓存 下一个问题」，多一个空格看不出来但很难受
            String finalized = partial.trim();
            log.debug("流式定稿 | {} | {} 字符", sessionId, finalized.length());
            return new StreamChunk("", finalized);
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
            // 不然**最后几个字会丢**（实测丢的是半句里的最后两三个字，看着像识别不准，其实是被截了）
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
            // 网络卡了一下，最后那句还是想要。而且 stop 里 remove + release 是一套的
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
        // 这里给 1.5 秒，而且要求「这段时间里确实说过话」，否则全程不说话也会不停定稿空句子。
        // 这两个数是**实测调出来的**，别照抄别处的值
        EndpointRule rule1 = EndpointRule.builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(1.5f)
                .setMinUtteranceLength(0f)
                .build();
        // rule2 收紧到基本不触发：它是「短停顿也算一句」，对连续说话的场景只会把句子切碎
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
```

- [ ] **Step 6: 往本地 `application.yml` 加流式的 model-dir（**不提交这个文件**）**

在已有的 `asr:` 段里，`model-dir` 那一行下面加：

```yaml
  asr:
    model-dir: ${APP_ASR_MODEL_DIR:D:/models/sense-voice}
    stream:
      # 流式那条路的模型目录。解压在哪儿就指哪儿 —— 目录里有 fp32 的版本和 test_wavs，
      # 不影响，文件名在 AsrProperties.Stream 里挑好了
      #
      # 和上面一样是 ${环境变量:默认值}：不设变量用这个默认值，设了环境变量赢
      model-dir: ${APP_ASR_STREAM_MODEL_DIR:D:/models/streaming-zh-14M}
```

> ⚠️ **这个文件不能 commit**（里面有刻意保留的 `app.llm.base-url` / `model` 本地改动）。
> 所以流式的目录默认值**不在仓库里** —— README 的「语音答题」一节要把
> `APP_ASR_STREAM_MODEL_DIR` 写清楚，否则换台机器的人会卡在一个没有报错的
> `streamAvailable:false` 上。

- [ ] **Step 7: 编译**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
cd /d/ideaProjects/ai-mianshi
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q clean compile
```

预期：无输出。

---

## Task 2: 三个接口 + `/status` 扩展

**Files:**
- Modify: `src/main/java/com/ke/nhservice/aimianshi/controller/AsrController.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/dto/AsrStatusVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/AsrStreamVO.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrConfig.java`

- [ ] **Step 1: `AsrStatusVO` 加两个**平铺**字段**

```java
/**
 * 流式那两个字段**平铺**，不写成 stream: {available, reason} 嵌套对象。
 *
 * 原因很具体：AsrApiCheck 里的 extract(json, key) 取的是**第一个**同名 key。
 * 嵌套对象里也有 available，一旦哪天字段顺序变了，extract(statusBody, "available")
 * 就会取到流式那个 —— 断言开始测另一个东西，而且**它是绿的**。
 * 平铺从根上避掉这种「假绿」。
 */
public record AsrStatusVO(boolean available, String reason, String nativeVersion, int maxSeconds,
                          boolean streamAvailable, String streamReason) {

    public static AsrStatusVO of(AsrStatus offline, AsrStatus stream) {
        return new AsrStatusVO(offline.available(), offline.reason(), offline.nativeVersion(),
                offline.maxSeconds(), stream.available(), stream.reason());
    }
}
```

- [ ] **Step 2: `AsrStreamVO`**

```java
package com.ke.nhservice.aimianshi.common.dto;

/**
 * 流式一次 chunk 的返回，以及 start / stop。
 * 三个接口共用一个 VO：start 只填 sessionId，chunk 填 partial/final，
 * stop 只填 final —— 分成三个 record 会多出两个只有一个字段的类型。
 * 前端那边三个响应处理的是同一套字段名，也省事。
 */
public record AsrStreamVO(String sessionId, String partial, String finalText) {

    public static AsrStreamVO started(String sessionId) {
        return new AsrStreamVO(sessionId, "", "");
    }

    public static AsrStreamVO chunk(String partial, String finalText) {
        return new AsrStreamVO(null, partial, finalText);
    }

    public static AsrStreamVO finished(String finalText) {
        return new AsrStreamVO(null, "", finalText);
    }
}
```

> **注意**：JSON 里会是 `finalText`。Java 的 record 组件名不能叫 `final`，前端读的也是 `finalText` ——
> 两边必须一致，写 `final` 的话 Jackson 的字段名是 `final`，前端读 `r.final` 永远 undefined，
> 而且**不报错**（就是框里一直不进字）。

- [ ] **Step 3: Controller 加三个方法**

```java
    private final AsrStreamClient asrStreamClient;

    public AsrController(AsrClient asrClient, AsrStreamClient asrStreamClient, AsrProperties props) {
        this.asrClient = asrClient;
        this.asrStreamClient = asrStreamClient;
        this.props = props;
    }

    @GetMapping("/status")
    public ApiResponse<AsrStatusVO> status() {
        // 两条路各报各的。**不合成一个 available**：模型是两个不同的目录，
        // 只下了一个是很正常的状态，合成一个布尔就说不清到底是缺哪个
        return ApiResponse.ok(AsrStatusVO.of(asrClient.status(), asrStreamClient.status()));
    }

    /** 开一次流式会话。前端拿到 sessionId 后每 100ms 往 /chunk 送一片 */
    @PostMapping("/stream/start")
    public ApiResponse<AsrStreamVO> streamStart() {
        AsrStatus st = asrStreamClient.status();
        if (!st.available()) {
            throw new BizException("流式语音识别不可用：" + st.reason());
        }
        String id = asrStreamClient.start();
        if (id == null) {
            // 和 AsrController 里其他几句一样，「怎么跟用户说」是这一层的事
            throw new BizException("同时进行的语音会话太多了，等一下再试（上一次录音可能没正常结束）");
        }
        return ApiResponse.ok(AsrStreamVO.started(id));
    }

    /**
     * 一片音频。body 和 /transcribe 一样是裸 PCM16 小端。
     *
     * 单片长度**不设上限**（只校验采样率）：这里没有「整段多长」的概念，
     * 会话总长由前端的录音计时器管。真要防的是有人往这里灌超大 body，
     * 那是 Tomcat 的 max-swallow-size 该管的事，不是业务判断
     */
    @PostMapping("/stream/chunk")
    public ApiResponse<AsrStreamVO> streamChunk(@RequestBody(required = false) byte[] body,
                                                @RequestParam String sessionId,
                                                @RequestParam(defaultValue = "16000") int sampleRate) {
        if (sampleRate != SAMPLE_RATE) {
            throw new BizException("采样率只支持 " + SAMPLE_RATE + "，收到 " + sampleRate);
        }
        if (body == null || body.length == 0) {
            throw new BizException("没有收到音频数据");
        }
        if (!asrStreamClient.has(sessionId)) {
            // 过期和不存在都走这句。分开说对用户没意义，她要做的事都是「重录一次」
            throw new BizException("这次录音的会话已经过期了，重新点一次麦克风");
        }
        StreamChunk c = asrStreamClient.chunk(sessionId, PcmCodec.toFloats(body), sampleRate);
        return ApiResponse.ok(AsrStreamVO.chunk(c.partial(), c.finalText()));
    }

    /** 结束会话。返回的 final 是最后没定稿的那半句，前端要追加进答题框 */
    @PostMapping("/stream/stop")
    public ApiResponse<AsrStreamVO> streamStop(@RequestParam String sessionId) {
        if (!asrStreamClient.has(sessionId)) {
            // 不抛异常：会话过期之后前端可能还在收尾，抛出去是白给用户一个红条。
            // 回空文本，前端那边是「什么都没追加」，和正常结束看起来一样
            return ApiResponse.ok(AsrStreamVO.finished(""));
        }
        long startedAt = System.currentTimeMillis();
        String text = asrStreamClient.stop(sessionId);
        // 打耗时和字数，**不打文本本身** —— 回答是候选人的隐私内容（和 /transcribe 一致）
        log.info("ASR 流式结束 | {} ms | 结果 {} 字符", System.currentTimeMillis() - startedAt, text.length());
        return ApiResponse.ok(AsrStreamVO.finished(text));
    }
```

import 要加：`AsrStreamClient`、`AsrStreamVO`、`StreamChunk`。

- [ ] **Step 4: `AsrConfig` 注册流式客户端 + 启动诊断打两行**

```java
    @Bean
    public AsrStreamClient asrStreamClient(AsrProperties props) {
        AsrStreamClient client = new SherpaStreamAsrClient(props);

        // 和离线那条一样，启动时打一行。两条路各自报各自的 ——
        // 「只下了离线模型」是很正常的状态，合成一行就说不清缺哪个
        AsrStatus status = client.status();
        if (status.available()) {
            log.info("流式语音识别已启用 | 模型目录={}", props.getStream().getModelDir());
        } else {
            log.info("流式语音识别未启用，答题区只有离线那一个语音选项 | {}", status.reason());
        }
        return client;
    }
```

> 流式不可用打 `info` 不是 `warn`：**只下离线模型是完全正常的状态**，
> 而离线那条不可用时打 `warn` 是因为那意味着「语音整个没了」。两个的严重程度不一样。

- [ ] **Step 5: 编译 + 起服务看日志**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q clean compile
```

起服务（用户自己的实例在 8080，用别的端口）：

```bash
cp -r src/main/resources/*.yml target/classes/ 2>/dev/null
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "target/classes;$(cat $TEMP/cp.txt)" \
  com.ke.nhservice.aimianshi.AiMianshiApplication --server.port=18096 > $TEMP/stream-run.log 2>&1 &
```

预期日志里有两行：

```
流式 ASR 模型加载完成 ...      ← 只有真转了才会打，启动时不打
语音识别已启用 | native=... | 模型=... | 单次上限=...
流式语音识别已启用 | 模型目录=D:/models/streaming-zh-14M
```

（离线那行是 `AsrConfig` 里已有的。）`available:false` 的话先回去看 Step 1 的文件名。

---

## Task 3: 验证程序（服务端）

**Files:**
- Create: `src/test/java/com/ke/nhservice/aimianshi/checks/AsrStreamCheck.java`

- [ ] **Step 1: 写 `AsrStreamCheck`**

核心断言是**「喂到一半就已经有字了」** —— 这一条才能证明它真是流式。
只断言「最后能转出正确文本」的话，把整段攒起来一次性解码也照样绿。

```java
package com.ke.nhservice.aimianshi.checks;

import com.ke.nhservice.aimianshi.AiMianshiApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * /api/asr/stream/* 的验证程序（不是 JUnit，是能直接 main 跑的）。
 *
 * 为什么必须真起服务 + 真喂音频：这条路上容易错的没有一处是 Java 逻辑 ——
 * 「native 库里那个 OnlineRecognizer 到底认不认这个模型」「isEndpoint 什么时候触发」
 * 「stop 时最后那片解没解」，全是 native 行为，只有真跑一遍才知道。
 *
 * 跑法（仓库根目录，先 test-compile）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.AsrStreamCheck
 *
 * 两个参数都可以省。默认用**这个模型自带的 test_wavs/0.wav**，不蹭 SenseVoice 那个：
 * 流式模型的测试音频是配套的，出问题时不用先怀疑「是不是音频本来就不对」
 */
public class AsrStreamCheck {

    static int pass = 0, fail = 0;
    static final String BASE = "http://127.0.0.1:18092";

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.printf("%-4s | %s | %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        String modelDir = args.length > 0 ? args[0]
                : "D:/models/streaming-zh-14M";
        // 模型自带的测试音频。5.6 秒的中文，切成 100ms 一片正好 56 片
        String wav = args.length > 1 ? args[1]
                : "D:/models/streaming-zh-14M/test_wavs/0.wav";

        Path db = Path.of(System.getProperty("java.io.tmpdir"), "asrstream-" + System.nanoTime() + ".db");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18092",
                        "--spring.datasource.url=jdbc:sqlite:" + db.toAbsolutePath(),
                        "--app.asr.model-dir=",
                        "--app.asr.stream.model-dir=" + modelDir);

        try {
            HttpClient http = HttpClient.newHttpClient();
            String token = extract(post(BASE, http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}",
                    "application/json"), "token");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("登录失败");
            }

            String status = get(http, "/api/asr/status", token);
            check("★ /status 里 streamAvailable=true（流式模型目录指对了）",
                    "true".equals(extract(status, "streamAvailable")), status);
            check("★ 离线那条报 available=false（本次没配它）—— 两条路各报各的，没被互相带偏",
                    "false".equals(extract(status, "available")), status);

            byte[] pcm = wavDataChunk(Path.of(wav));
            int chunkBytes = 16000 * 2 / 10;    // 100ms 一片
            int total = pcm.length / chunkBytes;
            System.out.println("0.wav: " + pcm.length + " 字节 = "
                    + (pcm.length / 2 / 16000.0) + " 秒，切成 " + total + " 片");

            String started = post(BASE, http, "/api/asr/stream/start", token, null, "application/json");
            String sid = extract(started, "sessionId");
            check("★ /stream/start 拿到 sessionId", sid != null && !sid.isBlank(), started);
            if (sid == null || sid.isBlank()) {
                throw new IllegalStateException("没拿到会话，后面没法继续: " + started);
            }

            // ── 核心：一片一片喂，看第几片开始出字 ──
            // 这条断言是整个检查的意义所在：断「最后能转出正确文本」是没用的 ——
            // 把整段攒起来一次性解码也照样绿。只有「喂到一半就有字」能证明它真是流式
            int firstTextAt = -1;
            String sawFinal = "";
            StringBuilder partials = new StringBuilder();
            for (int i = 0; i < total; i++) {
                byte[] slice = Arrays.copyOfRange(pcm, i * chunkBytes, (i + 1) * chunkBytes);
                String r = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid
                        + "&sampleRate=16000", token, slice);
                String partial = extract(r, "partial");
                String fin = extract(r, "finalText");
                if (firstTextAt < 0 && !partial.isBlank()) {
                    firstTextAt = i;
                }
                if (!fin.isBlank()) {
                    sawFinal = fin;
                    System.out.println("  第 " + i + " 片定稿: [" + fin + "]");
                }
                if (partials.length() < 200) {
                    partials.append(partial).append('|');
                }
            }
            System.out.println("半句的演变: " + partials);
            check("★ **喂到一半（第 " + firstTextAt + " 片 / 共 " + total + " 片）就已经出字了**"
                            + " —— 这才叫流式。攒够整段再解码的话这里必然是 -1",
                    firstTextAt > 0 && firstTextAt < total / 2, "firstTextAt=" + firstTextAt);

            String stopped = post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid,
                    token, null, "application/json");
            String finalText = extract(stopped, "finalText");
            System.out.println("stop 返回: [" + finalText + "]");
            check("★ /stream/stop 返回最后那半句（不是空）", !finalText.isBlank(), finalText);
            check("★ 拼起来是中文",
                    (sawFinal + finalText).codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF),
                    sawFinal + " + " + finalText);

            // ── 会话生命周期 ──
            String again = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid
                    + "&sampleRate=16000", token, new byte[chunkBytes]);
            check("★ stop 之后再喂同一会话 → 过期了，不是 500",
                    again.contains("会话已经过期"), again);

            String lateStop = post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid,
                    token, null, "application/json");
            check("★ 对已经结束的会话再 stop → 回空文本，不给用户一个红条",
                    "0".equals(extract(lateStop, "code")) && extract(lateStop, "finalText").isBlank(),
                    lateStop);

            // ── 守卫 ──
            String noSession = post(BASE, http, "/api/asr/stream/start", token, null, "application/json");
            String sid2 = extract(noSession, "sessionId");
            String wrongRate = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid2
                    + "&sampleRate=8000", token, new byte[chunkBytes]);
            check("★ ?sampleRate=8000 → 被拒（静默按 16k 解只会转出一段乱码）",
                    wrongRate.contains("只支持 16000"), wrongRate);
            String empty = postRaw(http, "/api/asr/stream/chunk?sessionId=" + sid2
                    + "&sampleRate=16000", token, new byte[0]);
            check("★ 空 body 回的是我们那句「没有收到音频数据」，不是 500、不是框架英文",
                    empty.contains("没有收到音频数据") && !empty.contains("服务器内部错误"), empty);
            post(BASE, http, "/api/asr/stream/stop?sessionId=" + sid2, token, null, "application/json");

            // ── 没配流式模型那一场 ──
            String noToken = get(http, "/api/asr/status", null);
            check("无 token 访问 /api/asr/stream/start → 401",
                    statusOf(http, raw("/api/asr/stream/start", null, new byte[0]).POST(
                            HttpRequest.BodyPublishers.noBody()).build()) == 401, "");

        } finally {
            ctx.close();
        }

        // ── 流式模型没配时：要回一句人话，且不影响离线那条 ──
        Path db2 = Path.of(System.getProperty("java.io.tmpdir"), "asrstream-no-" + System.nanoTime() + ".db");
        Path emptyDir = Files.createTempDirectory("asrstream-empty-");
        ConfigurableApplicationContext ctx2 = new SpringApplicationBuilder(AiMianshiApplication.class)
                .run("--server.port=18093",
                        "--spring.datasource.url=jdbc:sqlite:" + db2.toAbsolutePath(),
                        "--app.asr.model-dir=D:/models/sense-voice",
                        "--app.asr.stream.model-dir=" + emptyDir.toAbsolutePath());
        try {
            HttpClient http = HttpClient.newHttpClient();
            String token = extract(post("http://127.0.0.1:18093", http, "/api/auth/login", null,
                    "{\"username\":\"admin\",\"password\":\"admin123\"}",
                    "application/json"), "token");

            String s = get("http://127.0.0.1:18093", http, "/api/asr/status", token);
            check("★ 流式模型目录为空 → streamAvailable=false 且 streamReason 是句人话",
                    "false".equals(extract(s, "streamAvailable"))
                            && !extract(s, "streamReason").isBlank(),
                    extract(s, "streamReason"));
            check("★ 但同一次请求里 available 仍是 true —— 流式没配不该把离线也拖下水",
                    "true".equals(extract(s, "available")), s);

            String st = post("http://127.0.0.1:18093", http, "/api/asr/stream/start", token,
                    null, "application/json");
            check("★ 没配流式时 start 回「流式语音识别不可用」+ 原因，不是 500",
                    st.contains("流式语音识别不可用"), st);
        } finally {
            ctx2.close();
        }

        System.out.println("RESULT: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ── HTTP 工具：直接抄 AsrApiCheck 里那几段（同一个项目的另一份检查，形状保持一致） ──

    static HttpRequest.Builder raw(String path, String token, byte[] body) {
        return raw(BASE, path, token, body);
    }

    static HttpRequest.Builder raw(String base, String path, String token, byte[] body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/octet-stream");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    static String postRaw(String path, String token, byte[] body) throws Exception {
        return postRaw(BASE, null, path, token, body);
    }

    static String postRaw(String base, HttpClient http, String path, String token, byte[] body)
            throws Exception {
        return http.send(raw(base, path, token, body).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String post(String base, HttpClient http, String path, String token, String body,
                       String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", contentType);
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(String base, HttpClient http, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String get(HttpClient http, String path, String token) throws Exception {
        return get(BASE, http, path, token);
    }

    static String post(String base, HttpClient http, String path, String token, String body)
            throws Exception {
        return post(base, http, path, token, body, "application/json");
    }

    static int statusOf(HttpClient http, HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    static byte[] wavDataChunk(Path wav) throws Exception {
        byte[] all = Files.readAllBytes(wav);
        int i = 12;
        while (i + 8 <= all.length) {
            String id = new String(all, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = (all[i + 4] & 0xFF) | ((all[i + 5] & 0xFF) << 8)
                    | ((all[i + 6] & 0xFF) << 16) | ((all[i + 7] & 0xFF) << 24);
            if ("data".equals(id)) {
                return Arrays.copyOfRange(all, i + 8, Math.min(all.length, i + 8 + size));
            }
            i += 8 + size + (size % 2);
        }
        throw new IllegalStateException("这个 wav 里找不到 data chunk：" + wav);
    }

    /** 从 JSON 里取字段原始值。只用于断言，方法名冲突时取先出现的那个 */
    static String extract(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) {
            return "";
        }
        int start = i + needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length()) {
                char c = json.charAt(end);
                if (c == '\\' && end + 1 < json.length()) {
                    sb.append(json.charAt(end + 1));
                    end += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                sb.append(c);
                end++;
            }
            return sb.toString();
        }
        int end = start;
        while (end < json.length() && ",}\n".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(start, end).trim();
    }
}
```

- [ ] **Step 2: 编译并跑**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q test-compile
CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.AsrStreamCheck
```

预期：全 PASS，且中间会打出「半句的演变」，能看到字是**一截一截长出来**的。

**如果 `firstTextAt` 是 -1**（喂完了都没出字）：那不是测试写错了，是这条根本不是流式在跑 ——
先看 `status()` 报的模型目录，再看 native 日志（`--app.asr.stream.debug=true`）。

---

## Task 4: 前端 —— AudioWorklet 采集 + 流式客户端

**Files:**
- Create: `src/main/resources/static/js/asr-pcm-worklet.js`
- Create: `src/main/resources/static/js/asr-stream.js`
- Modify: `src/main/resources/static/js/asr.js`

- [ ] **Step 1: `asr-pcm-worklet.js`**

**必须是独立文件**：AudioWorklet 用 `addModule(url)` 加载，不能内联。

```js
/*
 * AudioWorklet 处理器：把麦克风的 Float32 分片丢回主线程。
 *
 * 为什么不能继续用 MediaRecorder：它吐的是**压缩容器的碎片**（webm/opus），
 * 只有第一片带容器头，单独丢给 decodeAudioData 解不开。
 * 所以流式这条路必须直接拿裸 PCM —— 这是 AudioWorklet 唯一的存在理由。
 *
 * 这个文件必须能被浏览器单独 fetch 到（addModule 的参数是个 URL），
 * 所以不能内联进 asr-stream.js，也不能被任何打包步骤合并。
 *
 * 注意 AudioWorkletGlobalScope 里**没有 window / document / console.log 之外的东西**，
 * 也没有 Vue。这里只能做最纯粹的攒帧 + postMessage。
 */

class AsrPcmProcessor extends AudioWorkletProcessor {

  constructor(options) {
    super();
    // 1600 帧 @16k = 100ms。太小会把 HTTP 请求数撑上去（每片都要带一份 300 字节的头），
    // 太大则出字一顿一顿的。100ms 是这两头中间最舒服的位置
    const opt = (options && options.processorOptions) || {};
    this.frameSize = opt.frameSize || 1600;
    this.buf = new Float32Array(this.frameSize);
    this.n = 0;
  }

  process(inputs) {
    const input = inputs[0];
    const ch = input && input[0];
    // 麦克风还没接上、或者这一帧没人拉数据时 ch 是空的。
    // 返回 true 表示「这个处理器还活着」—— 返回 false 会被浏览器回收掉，声音就永远断了
    if (!ch) {
      return true;
    }
    for (let i = 0; i < ch.length; i++) {
      this.buf[this.n++] = ch[i];
      if (this.n === this.frameSize) {
        // slice() 是必须的：不复制的话 postMessage 传的是同一个 buffer，
        // 下一帧会把它写花，主线程拿到的是「正在被改的那块内存」
        this.port.postMessage(this.buf.slice(0));
        this.n = 0;
      }
    }
    return true;
  }
}

registerProcessor('asr-pcm', AsrPcmProcessor);
```

- [ ] **Step 2: `asr-stream.js`**

```js
/*
 * 流式语音识别：AudioWorklet 实时取 PCM → 每 100ms POST 一片 → 边说边出字。
 *
 * 和 asr.js 平级、互不引用。两条路的采集方式根本不同（那边 MediaRecorder 录整段，
 * 这边 AudioWorklet 实时拿裸 PCM），硬合成一个文件只会让两边都难读。
 * 页面（interview.html）负责按 asrMode 分发，见那边的 micStart / micStop。
 *
 * 三个容易踩的地方：
 *  1. **不能直接写 textarea**。partial 一直在被改写（「我要用 redis」→「我要用 Redis 做缓存」），
 *     写进框里会把用户打的草稿反复搅乱，光标位置也没了。所以 partial 只进 asrPartial
 *     （麦克风上方那行灰字），只有 finalText 才追加进框
 *  2. **分片必须按顺序发**。音频是时序数据，两片乱序 = 音频被搅乱，
 *     转出来是一段流利但完全不对的中文，**不会报错**。所以是「等上一个响应回来才发下一个」
 *  3. **AudioContext 要 {sampleRate: 16000}**。浏览器会替我们把麦克风重采样到 16k，
 *     asr.js 那套 OfflineAudioContext 手动重采样在这条路上完全不需要
 */

const ASR_STREAM_MODES = { offline: '离线', stream: '流式' };
const ASR_MODE_KEY = 'asrMode';

/** 挂进页面的 data。都由 interview.html 展开一次 */
function asrStreamData() {
  return {
    asrStreamAvailable: false,  // /status 说的，决定「流式」这个选项能不能选
    asrStreamReason: '',        // 不可用时的一句人话（要进 title，不然用户不知道为什么是灰的）
    asrMode: 'stream',          // offline | stream。默认流式
    asrPartial: ''              // 正在说的这半句，一直在变，**不进 textarea**
  };
}

const ASR_STREAM_METHODS = {

  /** 读回上次选的模式。localStorage 挂了（隐私模式）就用默认值，不能让页面起不来 */
  asrModeInit() {
    try {
      const saved = localStorage.getItem(ASR_MODE_KEY);
      if (saved === 'offline' || saved === 'stream') this.asrMode = saved;
    } catch (e) { /* 隐私模式下 localStorage 会抛，忽略 */ }
  },

  /** 切模式。录音中要先收掉这次录音 —— 不然麦克风还开着，模式已经变了 */
  asrModeSet(mode) {
    if (mode !== 'offline' && mode !== 'stream') return;
    if (this.asrState === 'recording') this.micStop();
    this.asrMode = mode;
    try {
      localStorage.setItem(ASR_MODE_KEY, mode);
    } catch (e) { /* 同上 */ }
    this.asrError = '';
  },

  /**
   * 当前模式下麦克风该不该显示。
   *
   * 流式不可用（没下那个模型）时那个选项是灰的，但 asrMode 可能还存着 'stream' ——
   * 所以这里要判 asrStreamAvailable，不能只看 asrMode
   */
  micAvailable() {
    return this.asrMode === 'stream' ? this.asrStreamAvailable : this.asrAvailable;
  },

  async asrStreamStart() {
    this.asrCancel();
    this.asrError = '';
    this.asrPartial = '';
    this.asrState = 'idle';

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia
        || typeof AudioWorkletNode === 'undefined') {
      this.asrError = '这个浏览器不支持流式录音（要 AudioWorklet），切到「离线」就能用';
      return;
    }

    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      this.asrError = '没拿到麦克风权限。浏览器地址栏左边可以改回来，或者直接打字回答';
      return;
    }
    this._asrStream = stream;

    try {
      const s = await api('/api/asr/stream/start', { method: 'POST' });
      this._asrSessionId = s.sessionId;
    } catch (e) {
      this.asrReleaseMic();
      this.asrError = e.message || '开不了语音会话，重试一次';
      return;
    }

    try {
      // ★ {sampleRate: 16000}：浏览器替我们把麦克风重采样到 16k。
      // asr.js 那条路要手动跑一遍 OfflineAudioContext，这里不用 —— 这是流式最省事的一步
      const ctx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
      this._asrCtx = ctx;
      await ctx.audioWorklet.addModule('js/asr-pcm-worklet.js');

      // ctx.sampleRate 拿不到 16000 时（老浏览器会忽略这个参数）**不静默继续**：
      // 采样率不对不会报错，只会转出一段看着像话其实全错的中文。
      // 和服务端那句 "采样率只支持 16000" 是同一个判断，只不过这里只能降级不能拒
      if (ctx.sampleRate !== ASR_TARGET_RATE) {
        throw new Error('浏览器不给 16000 Hz 的音频上下文（拿到 '
          + ctx.sampleRate + '），切到「离线」就能用');
      }

      const node = new AudioWorkletNode(ctx, 'asr-pcm');
      node.port.onmessage = (e) => this.asrStreamFeed(e.data);
      const src = ctx.createMediaStreamSource(stream);
      src.connect(node);

      // 空 gain 接到 destination：AudioWorkletNode 得挂在图上才会被拉数据，
      // 直接连 destination 就是把麦克风原声放出来（啸叫）。
      // gain 0 让它照样被拉，但一个字节都不出声
      const mute = ctx.createGain();
      mute.gain.value = 0;
      node.connect(mute);
      mute.connect(ctx.destination);

      // 电平条复用 asr.js 那套：形状装成一样的，asrRms 和 asrTick 就不用改
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 1024;
      src.connect(analyser);
      this._asrAudio = { ctx, analyser, buf: new Uint8Array(analyser.fftSize) };

      this._asrQueue = [];
      this._asrPumping = false;
      this._asrStartedAt = Date.now();
      this.asrSeconds = 0;
      this.asrState = 'recording';
      this.asrStartTicker();
    } catch (e) {
      await this.asrStreamStop(true);
      this.asrError = e.message || '流式录音启动失败，切到「离线」还能用';
      this.asrState = 'idle';
    }
  },

  /** worklet 每 100ms 送一片 Float32 来 */
  asrStreamFeed(f32) {
    if (this.asrState !== 'recording') return;
    // Float32 → Int16：一半的带宽，而且和服务端 PcmCodec.toFloats 是同一套约定
    // （和离线那条路发的字节完全一样），服务端不用为流式多写一份解码
    const pcm = new Int16Array(f32.length);
    for (let i = 0; i < f32.length; i++) {
      const v = Math.max(-1, Math.min(1, f32[i]));
      pcm[i] = v < 0 ? v * 0x8000 : v * 0x7FFF;
    }
    this._asrQueue.push(pcm);
    this.asrStreamPump();
  },

  /**
   * 一片一片按顺序发。**不并发**：音频是时序数据，两片乱序到达，
   * 转出来是一段流利但完全不对的中文，而且不会报错。
   */
  async asrStreamPump() {
    if (this._asrPumping) return;
    this._asrPumping = true;
    try {
      while (this._asrQueue.length && this.asrState === 'recording') {
        const pcm = this._asrQueue.shift();
        const r = await api('/api/asr/stream/chunk?sessionId=' + this._asrSessionId, {
          method: 'POST', body: pcm, contentType: 'application/octet-stream'
        });
        // 定稿的先追加，再更新灰字 —— 顺序反了的话，句子进框之后灰字还挂着上一句，
        // 看着像重复识别了一遍
        if (r.finalText) this.asrAppendText(r.finalText);
        this.asrPartial = r.partial || '';
      }
    } catch (e) {
      this.asrError = e.message || '流式识别中断了，切到「离线」可以接着说';
      await this.asrStreamStop(true);
      this.asrState = 'idle';
    } finally {
      this._asrPumping = false;
    }
  },

  async asrStreamStop(discard) {
    this.asrStopTicker();
    this.asrLevel = 0;
    this.asrReleaseMic();
    const sid = this._asrSessionId;
    this._asrSessionId = null;
    this._asrQueue = [];
    this.asrPartial = '';
    if (!sid) return;

    try {
      const r = await api('/api/asr/stream/stop?sessionId=' + sid, { method: 'POST' });
      // discard：用户提交了/切模式了，这次录音就该丢掉。
      // 这里仍然发 stop 而**不复用离线的 asrCancel**：两边代价不一样 ——
      // 离线那边 stop 会触发整段解码（白发一次重活），流式这边 stop 只是把最后一片解完，
      // 本来就该发给服务端让它回收会话。所以没必要再单开一个 cancel 接口
      if (!discard && r.finalText) this.asrAppendText(r.finalText);
    } catch (e) {
      if (!discard) this.asrError = e.message || '收尾失败，最后那几个字可能没进去';
    }
  },

  /** 定稿的文字进答题框。追加不覆盖 —— 和 asr.js 里 asrTranscribe 是同一套语义 */
  asrAppendText(text) {
    const t = (text || '').trim();
    if (!t) return;
    const typed = (this.answer || '').trim();
    this.answer = typed ? typed + ' ' + t : t;
    this.asrState = 'done';
  }
};
```

- [ ] **Step 3: `asr.js` 抽出 ticker，让两条路共用**

现在 `asrStart` 里那段 `setInterval`（秒数 + 电平 + 到上限自动停）是内联的。
流式那条需要一模一样的行为，**复制一份的话「到上限自动停」这条规则就有两处实现，
改一处忘一处**。抽成两个方法，两条路共用：

```js
  /** 秒数 + 电平 + 到上限自动停。两条路（离线 / 流式）共用这一份 */
  asrStartTicker() {
    clearInterval(this._asrTimer);
    // 100ms 一跳：电平条要顺，秒数要准（按 Date.now 算，不靠累加，累加会被 setInterval 的漂移带偏）
    this._asrTimer = setInterval(() => {
      this.asrSeconds = Math.floor((Date.now() - this._asrStartedAt) / 1000);
      this.asrLevel = asrRms(this._asrAudio.analyser, this._asrAudio.buf);
      // 到上限自动停。等用户自己发现「已经说了几分钟」不如替她停掉 ——
      // 停了还能转写；超了服务端会直接拒掉，那段话就白说了
      if (this.asrSeconds >= this.asrMaxSeconds) this.micStop();
    }, 100);
  },

  asrStopTicker() {
    clearInterval(this._asrTimer);
    this._asrTimer = null;
  },
```

`asrStart` 里原来那段 `setInterval` 整块删掉，换成 `this.asrStartTicker();`。
注意 `this.asrStop()` 改成 `this.micStop()` —— 计时器不该知道「现在是哪个模式」，
分发是页面的活（见 Task 5）。

- [ ] **Step 4: `asrInit` 也把流式的可用性读回来**

```js
  async asrInit() {
    try {
      const s = await api('/api/asr/status');
      this.asrAvailable = !!s.available;
      this.asrStreamAvailable = !!s.streamAvailable;
      this.asrStreamReason = s.streamReason || '';
      if (s.maxSeconds) this.asrMaxSeconds = s.maxSeconds;
    } catch (e) {
      this.asrAvailable = false;
      this.asrStreamAvailable = false;
    }
    // 存着「流式」但流式没配（换机器了、模型删了）→ 落回离线，
    // 否则页面进来就是「录音按钮不见了」，而离线明明是能用的
    if (this.asrMode === 'stream' && !this.asrStreamAvailable) {
      this.asrMode = this.asrAvailable ? 'offline' : 'stream';
    }
  },
```

---

## Task 5: 页面 —— 模式选择 + 灰字行

**Files:**
- Modify: `src/main/resources/static/interview.html`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: 答题框里加灰字行和模式选择**

`.answer-bar` 上方插一行灰字；`.answer-bar` 里麦克风**左边**插模式选择。

```html
            <div class="answer-bar">
              <!-- 左边这半是录音状态的提示。说话时不看着点什么，就不知道麦克风有没有在收 -->
              <span class="asr-slot">
                <template v-if="asrState === 'recording'">
                  <span class="asr-meter"><i :style="{ width: (asrLevel * 100) + '%' }"></i></span>
                  <span class="asr-hint">{{ asrSeconds }}s / 最长 {{ asrMaxSeconds }}s，到点自动停</span>
                </template>
                <span class="asr-hint" v-else-if="asrState === 'transcribing'">
                  <span class="spinner dark"></span> 转写中…
                </span>
                <span class="asr-hint" v-else-if="asrState === 'done'">已转写，可以直接改</span>
              </span>

              <!-- 模式选择。放在麦克风左边是因为它管的是「这个麦克风怎么工作」 -->
              <select class="asr-mode" v-if="asrAvailable || asrStreamAvailable"
                      :value="asrMode" @change="asrModeSet($event.target.value)">
                <option value="stream" :disabled="!asrStreamAvailable"
                        :title="asrStreamReason">⚡ 流式</option>
                <option value="offline" :disabled="!asrAvailable">🎙 离线</option>
              </select>

              <button type="button" class="icon-btn recording"
                      v-if="micAvailable && asrState === 'recording'"
                      title="停止并转写" @click="micStop">⏹</button>
              <button type="button" class="icon-btn" v-else-if="micAvailable"
                      :disabled="submitting || asrState === 'transcribing'"
                      :title="asrState === 'transcribing' ? '转写中…' : '录音回答'"
                      @click="micStart">🎤</button>

              <button :disabled="!canSubmit" @click="submit">
                <span v-if="submitting" class="spinner"></span>
                {{ submitting ? '面试官正在评估…' : '提交' }}
              </button>
            </div>
```

灰字行插在 `<textarea>` 和 `.answer-bar` **中间**：

```html
            <!-- 流式正在说的那半句。**不放 textarea 里** —— 它一直在被改写
                 （「我要用 redis」→「我要用 Redis 做缓存」），写进框里会把用户打的草稿
                 反复搅乱、光标也没了。只有说到停顿定稿了，才追加进框（见 asr-stream.js） -->
            <p class="asr-partial" v-if="asrState === 'recording' && asrPartial">{{ asrPartial }}</p>
```

- [ ] **Step 2: data / computed / methods 接上**

`data()` 加 `...asrStreamData()`；`methods` 加 `...ASR_STREAM_METHODS,`。

新增分发（**放在页面里而不是 asr.js 里**：让 asr.js 反过来引用 asr-stream.js 的方法名，
两个文件就绑死了，少加载一个就是运行时 `undefined`；放页面里一眼能看出有两条路）：

```js
      /** 麦克风按钮的分发点。模板只认 micStart / micStop，不关心有几个模式 */
      micStart() {
        return this.asrMode === 'stream' ? this.asrStreamStart() : this.asrStart();
      },
      micStop() {
        return this.asrMode === 'stream' ? this.asrStreamStop() : this.asrStop();
      },
```

`mounted()` 里 `this.asrInit();` **之前**加 `this.asrModeInit();`（要先知道模式，
再决定拉回来的可用性要不要把模式落回离线）。

`canSubmit` 要跟着改 —— 判断依据从 `asrState` 变成「麦克风这活儿干完了没」：

```js
      canSubmit() {
        return !this.submitting && !!this.submitText
          && this.asrState !== 'recording' && this.asrState !== 'transcribing';
      },
```

（这条**不用改**：流式那条也走同一组 asrState 值。）

`submit()` 里 `this.asrReset();` 换成：

```js
          // 提交后清干净。流式那条要是还有会话在跑，这里得把服务端会话也收掉，
          // 否则它挂在服务端等过期（maxSessions 就 4 个，试几次就满了）
          if (this.asrMode === 'stream') {
            await this.asrStreamStop(true);
          } else {
            this.asrReset();
          }
```

- [ ] **Step 3: `app.css` 加两段**

```css
/* 正在说的那半句。斜体 + 更淡，一眼看出它「还不是框里的内容」——
   和 textarea 里那些字长得一样的话，用户会以为已经进去了、跑去改它，然后下一片一到就被改掉 */
.asr-partial {
  margin: 6px 0 0;
  padding-top: 6px;
  border-top: 1px dashed var(--border);
  color: var(--muted);
  font-style: italic;
  font-size: 13px;
  /* 半句长了要能换行，不能撑破框 */
  word-break: break-word;
}

/* 模式选择。窄，别抢麦克风和提交的视觉位置 */
select.asr-mode {
  flex: none;
  width: auto;
  padding: 4px 6px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--muted);
}
```

> 注意 `input, select, textarea` 那条全局规则给了 `width: 100%`，所以必须显式
> `width: auto` —— 不然这个 select 会把整条 `.answer-bar` 撑满，麦克风和提交被挤到下一行。

- [ ] **Step 4: 起服务，人工看一眼**

浏览器打开面试页，确认：
- 底部那排是 `[⚡流式▾] [🎤] [提交]`
- 点麦克风说话 → **灰字行实时冒字**
- 停顿一下 → 灰字行清空、字进了答题框，继续说话灰字行重新开始
- 点停止 → 最后那半句也进框
- 切到「离线」→ 恢复成录完整段再转的样子

---

## Task 6: 前端验证程序 + 文档

**Files:**
- Create: `src/test/js/AsrStreamPageCheck.js`
- Modify: `README.md`
- Modify: `src/test/README.md`

- [ ] **Step 1: `AsrStreamPageCheck.js`**

盯住四件事（都是「写错了不报错，只是行为不对」的那种）：

1. `partial` **不进** `answer`；`finalText` **追加**进 `answer`
2. 分片**串行**发（并发的话音频乱序，转出来是流利但错误的中文）
3. 切模式 / 提交时**不追加**（discard），但**仍然发 stop**（否则服务端会话泄漏）
4. `micAvailable` 只看当前模式对应的那个 available

要装的替身：`AudioWorkletNode`、`ctx.audioWorklet.addModule`、`ctx.sampleRate`、
假 `navigator.mediaDevices`、假 `fetch` 路由（记住每个请求的顺序和时间点）。

跑法：

```bash
node src/test/js/AsrStreamPageCheck.js
```

- [ ] **Step 2: 回归**

```bash
node src/test/js/AsrPageCheck.js                      # 离线那条，36 条
node /c/Users/huangjinqing001/AppData/Local/Temp/PageCheck.js   # 六页面，177 条
```

两条都要全绿。`asr.js` 抽了 ticker，`AsrPageCheck` 里 `asrStop` 改名 `micStop`
相关的断言要跟着看 —— 那个文件调的是 `ASR_METHODS` 里的方法，改名会影响它。

- [ ] **Step 3: README 同步**

- 「语音答题」那节：加一段「两种模式」的表格（离线 / 流式的模型、出字时机、标点、准确率）
- 「为什么不是流式」那几段**要重写** —— 现在做了，那段是讲「为什么当时没做」
- 「接口」加三个 `stream/*`
- 「已知取舍」加：流式没标点、会话上限 4、`maxSessions` 泄漏的形态
- **模型目录配置在未提交的 `application.yml` 里**这件事要写明：
  换机器的人要么设 `APP_ASR_STREAM_MODEL_DIR`，要么自己往 yml 里加 `app.asr.stream.model-dir`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrStreamClient.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/StreamChunk.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/SherpaStreamAsrClient.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrProperties.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/asr/AsrConfig.java \
        src/main/java/com/ke/nhservice/aimianshi/controller/AsrController.java \
        src/main/java/com/ke/nhservice/aimianshi/common/dto/AsrStatusVO.java \
        src/main/java/com/ke/nhservice/aimianshi/common/dto/AsrStreamVO.java
git commit -m "feat(asr): 流式识别客户端与分片接口"

git add src/main/resources/static/js/asr-pcm-worklet.js \
        src/main/resources/static/js/asr-stream.js \
        src/main/resources/static/js/asr.js \
        src/main/resources/static/interview.html \
        src/main/resources/static/css/app.css
git commit -m "feat(web): 答题区支持流式语音（边说边出字），可切回离线"

git add src/test/java/com/ke/nhservice/aimianshi/checks/AsrStreamCheck.java \
        src/test/js/AsrStreamPageCheck.js src/test/README.md README.md \
        docs/superpowers/specs/2026-09-29-asr-streaming-design.md \
        docs/superpowers/plans/2026-09-29-asr-streaming.md
git commit -m "docs+check: 流式识别的验证程序与文档"
```

**不要** `git add` 这三样：`src/main/resources/application.yml`、`img.png`、`img_1.png`。

---

## 验证清单

| 检查 | 怎么验 | 期望 |
|---|---|---|
| 流式真是流式 | `AsrStreamCheck` | `firstTextAt > 0 && < total/2`，半句是**一截截长出来**的 |
| final 不结束会话 | 上面 | 定稿那句之后继续喂，还能出字 |
| 会话回收 | `stop` 之后再 `chunk` | 「会话已经过期」，不是 500 |
| 两条路互不影响 | `streamAvailable=false` 那一场 | `available` 仍是 true |
| 灰字不进框 | 浏览器 | 说话时灰字行变，框里不动 |
| 停顿进框 | 浏览器 | 停顿后灰字清空、字进框、**不重复** |
| 串行发送 | `AsrStreamPageCheck` | 第二个 chunk 在第一个响应之后才发出 |
| 切模式 | 浏览器 + check | 切换后麦克风行为立刻变，不残留上一个模式的会话 |
| 不回归 | `AsrPageCheck.js` / `%TEMP%\PageCheck.js` | 36/36、177/0 |

## 明确不做的事

- **不做 WebSocket**：要新依赖，且鉴权/日志要手写一套。分片 POST 在本机完全够用
- **不挂标点模型**：`OnlinePunctuation` 也在同一个 jar 里，但那是另一个模型文件。
  先把主路径跑通；框本来就能改，标点自己补
- **不做 VAD 静音自动停**：endpoint 检测已经能自动定稿，用户不需要点任何东西就能接着说
- **不动离线那条**：`AsrClient` / `SherpaAsrClient` / `/api/asr/transcribe` 一行不改
- **不把两条路合并成一个抽象**：采集方式根本不同（MediaRecorder 整段 vs AudioWorklet 实时），
  合并出来的抽象会比两份实现加起来还难读