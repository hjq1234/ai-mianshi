# 语音答题（本地语音识别）设计文档

> 日期：2026-09-29　分支：`feat/interview-phase1`
> 前置：一期（核心主流程）+ 二期（复盘页可视化）均已完成

## 一、背景与目标

现在答题只能打字。要加的是**本地部署的语音识别**：说一段话，转成文字，然后照常提交。

**目的决定了这个功能长什么样：练口语表达。** 不是「省打字」，也不是「做语音输入法」。这一个前提
推翻了两个本来很自然的设计：

- **转写结果不做成可编辑草稿**，而是只读记录。能改的话，人会一边说一边在脑子里改稿，
  录完再顺手修几个词——练的就不是表达了。要改就重录一遍。
- **不做流式（边说边出字）**。同理：实时出字会让你盯着屏幕看识别对不对，而不是把注意力放在
  怎么把话讲清楚。而且 SenseVoiceSmall 本身就是非流式模型，做流式要换模型，
  收益是负的。

**输入方式每题可选**：麦克风按钮和 textarea 并存，这题想打字就打字，想录音就录音。

## 二、范围

**做**：

1. `pom.xml` 加 sherpa-onnx 两个依赖
2. `wrapper/asr/` 三个类：配置、接口、sherpa-onnx 实现
3. `controller/AsrController.java`：`GET /api/asr/status`、`POST /api/asr/transcribe`
4. `static/js/asr.js`：页面内录音 + 重采样 + 上传 + 四态 UI
5. `interview.html` / `app.css`：麦克风按钮、只读转写块、录音状态
6. README 同步

**不做**（每条都有具体理由，不是偷懒）：

| 不做 | 为什么 |
|---|---|
| 流式识别 | 见第一节：与「练口语」的目的相反；且 SenseVoiceSmall 非流式 |
| `answer_source` 列 | `schema.sql` 只有 `CREATE TABLE IF NOT EXISTS`，**没有迁移机制**，加列对已有库不生效——加了也只有新库有，比不加更乱 |
| 说话人分离 / 情绪 / 热词 | sherpa-onnx 都支持，但这个场景用不上。热词尤其没用：面试答案是完整句子，不是「打开空调」 |
| 存音频 | 只存文字。存音频要处理格式、时长、清理策略，全是新增的维护面，而复盘页也不放录音 |
| 改 `graph/` 一行 | 语音只是「另一种往 textarea 填字」的方式，图引擎不该知道有语音这回事 |
| 改评分链路 / 数据库 | 同上。`/answer` 接口不知道答案是怎么来的 |
| 改 `application.yml` | 那里有未提交的网关配置。配置走 Java 默认值 + 环境变量 |

## 三、技术摸底（全部实测，不是照抄文档）

| 项 | 实测结果 |
|---|---|
| 机器 | i7-1360P，12 核 16 线程，32 GB |
| sherpa-onnx 发布在哪 | **JitPack**，不在 Maven 中央仓库 |
| 坐标 | `com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:v1.13.8` |
| 平台制品 | `...:sherpa-onnx-native-lib-win-x64:v1.13.8`，**存在**（官方文档举例只列了 `win-arm64`） |
| 版本字面量 | **必须带 `v`**。`v1.13.8` 实测 200；不带 `v` 的写法在官方文档和一篇 CSDN 文章里都出现过，是坑 |
| 两个 jar 大小 | `jvm` 0.2 MB + `native-lib-win-x64` 7.9 MB = 8.1 MB |
| pom 里的依赖 | 两个制品 deploy 到 JitPack 的 pom **一个 `<dependencies>` 都没有**（`<description>POM was created from install:install-file</description>`），所以 native 库**不会**被自动带进来，两条依赖都得显式写 |
| 本机 Maven 配置 | `~/.m2/settings.xml` **不存在**，`-s /d/apache-jmeter-5.4.3/settings.xml` 是唯一配置来源 |
| 那份 settings.xml 的镜像 | `nexus-aliyun` 的 `<mirrorOf>*,!lianjia-*</mirrorOf>` —— 这个 `*` 会把**任何**新加的 `<repository>` 改写到阿里云 |
| 阿里云有没有这两个包 | **404**（同刻 sqlite-jdbc 对照 200，镜像本身是活的） |
| GitHub release 可达性 | 两个 jar、模型，HEAD 全部 200 |
| 模型仓库 | `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` |
| 模型大小 | `model.int8.onnx` **228.2 MB**、`model.onnx` 894.2 MB（fp32，不用）、`tokens.txt` 0.3 MB |
| native 库怎么加载 | 从 jar 内嵌路径 `sherpa-onnx/native/<os-arch>/` 自动解压加载（三种机制里的第二种），**不需要配 `java.library.path`** |

**关于那篇 CSDN 文章**：用户提供，末尾自述「部分内容由AI辅助生成，仅供参考」。它给了一条我不知道的
关键信息（native 库的三种加载机制），也给了两条会误导的（版本是 `1.13.7` 且不带 `v`；本地坐标
`com.k2fsa.sherpa.onnx` 与 JitPack 坐标不同）。所以只当线索用，上表每一条都自己验过。

## 四、架构与数据流

语音在整条链上**只是一个「往 textarea 填字」的动作**：

```
浏览器录音 (MediaRecorder, webm/opus)
   ↓ 页内重采样：decodeAudioData → OfflineAudioContext(1, len, 16000) → Float32
   ↓ 转 Int16LE（裸字节，不 base64）
POST /api/asr/transcribe        ← 新增
   ↓ { text }
页面上一个只读的转写块（不是 textarea）
   ↓ 用户确认后提交（或自己改打字）
POST /api/interview/{id}/answer { answer }   ← 现有接口，一个字不改
```

`/answer` 收到的仍是一个字符串。**它不知道、也不需要知道这个字符串是录出来的还是打出来的。**

**音频为什么在浏览器里重采样**：`OfflineAudioContext` 就是浏览器自带的采样率转换器，用它把
44.1k/48k 的录音降成 16k 单声道，服务端就完全不需要碰音频——不引 FFmpeg、不引任何音频库，
服务端收到的就是 `int16` 的裸字节，除以 32768.0 就是 sherpa-onnx 要的 `float[]`。
16k 单声道 PCM16 是 32 KB/秒，两分钟的上限（`maxSeconds`）也就 3.8 MB，走 POST body
不 base64（base64 要 +33%，且解码多一步）。

## 五、后端

### 组件

| 文件 | 职责 |
|---|---|
| `wrapper/asr/AsrProperties.java` | `@ConfigurationProperties(prefix = "app.asr")`，照 `LlmProperties` 的形状 |
| `wrapper/asr/AsrClient.java` | 接口：`String transcribe(float[] samples, int sampleRate)` + `AsrStatus status()` |
| `wrapper/asr/SherpaAsrClient.java` | sherpa-onnx 实现 |
| `controller/AsrController.java` | 两个接口 |

`AsrClient` 做成接口是照 `wrapper/llm` 的先例（`LlmClient` 也是接口 + 一个实现）：将来换引擎
（Vosk、或换成 HTTP 调 Python 侧车）不用动 controller，验证时也能塞替身。

```java
public interface AsrClient {
    /** samples 是 [-1,1] 的 float；sampleRate 必须是模型要的 16000 */
    String transcribe(float[] samples, int sampleRate);

    AsrStatus status();
}

/** available=true 时 reason 为 null */
public record AsrStatus(boolean available, String reason, String nativeVersion) {}
```

controller 负责 `byte[]` → `float[]` 的转换：PCM16 **小端**，
`(short)((b[i + 1] << 8) | (b[i] & 0xFF)) / 32768.0f`。
**字节序写错的表现是「有声音但全是乱码」，不是抛异常**——所以这一段要单独验，
不能只看「没报错」就当过了。

### 模型加载与并发

- **懒加载、只建一次**。`OfflineRecognizer` 构造要读 228 MB 模型，启动时建会让启动慢十几秒，
  而大部分人不用语音。所以第一次转写请求时建，用 `volatile` + 双检锁。
- **解码 `synchronized`**。sherpa-onnx 的 `getConfig()` 文档明确写了未同步，且 native 侧不保证
  可重入；单用户场景下并发解码本来也不发生，加锁零成本。
- **每次 decode 后 `stream.release()`**。不 release 每答一题就漏一份 native 内存——
  10 题看不出来，跑一天就明显了。recognizer 是长生命周期复用的，stream是每次新建每次释放。
- **`num-threads` 默认 4**。i7-1360P 有 16 个逻辑核，但一次只解一段音频，给 4 个线程足够
  （SenseVoiceSmall int8 解 60 秒音频远低于 1 秒）。

### 模型没下载好时，应用必须正常启动

这是硬要求。`/status` 返回：

```json
{ "available": false, "reason": "模型目录里没有 model.int8.onnx", "nativeVersion": "1.13.8" }
```

前端拿到 `available: false` 就把麦克风按钮藏起来，答题区跟现在完全一样。**不能**因为没下模型
就让整个应用起不来——打字的路径必须永远可用。

`nativeVersion` 取自 `VersionInfo.getVersion()`（sherpa-onnx 自带的静态方法）。它返回得了
就说明 native 库真加载上了。这是排 `UnsatisfiedLinkError` 最省事的一招：不用猜是 dll 没找到、
还是版本对不上、还是 `onnxruntime` 冲突。

### 配置

Java 侧给默认值，yml / 环境变量覆盖，**`application.yml` 不动**：

```java
@Component
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {
    /** 模型目录。放仓库外——228 MB 不进 git */
    private String modelDir = "";
    /** int8 量化版。fp32 是 894 MB，没必要 */
    private String modelFile = "model.int8.onnx";
    private String tokensFile = "tokens.txt";
    private int numThreads = 4;
    /** 单次转写音频上限（秒）。16k 单声道 PCM16 是 32 KB/秒，120 秒 ≈ 3.8 MB */
    private int maxSeconds = 120;
}
```

本地用 `APP_ASR_MODEL_DIR` 环境变量指到模型目录（Spring Boot 的 relaxed binding，
`app.asr.model-dir` ↔ `APP_ASR_MODEL_DIR`）。

### 接口

```
GET  /api/asr/status       → { available, reason, nativeVersion }
POST /api/asr/transcribe   → body: 裸 PCM16LE 字节；query: ?sampleRate=16000 → { text }
```

`POST` 收裸字节而不是 JSON/base64：`Content-Type: application/octet-stream`，Spring 侧收
`@RequestBody byte[]`。少一层编解码，抓包看也直观。

`sampleRate` 走 query 而不是 body——body 已经是裸字节了，塞不进别的字段。
服务端**只接受 16000**（模型要的采样率），别的值直接 400。不放过是因为采样率错了不会报错，
只会转出一段看着像话、其实全错的文本——这种静默失败比一个 400 难查得多。

## 六、前端

### 四个状态

```
idle         [🎤 语音回答]
recording    [⏹ 停止]  ● 00:07  ▁▃▅▇▅▃  ← 计时 + 电平条
transcribing [转写中…]                     ← 按钮禁用
done         只读转写块 + [重录] [改用打字]
```

录音时给个**电平条**不只是好看：没有它，用户不知道麦克风到底有没有收到声音，
录完发现是空的才重来。用 `AnalyserNode` 取时域数据的 RMS，纯前端，不用新接口。

### 转写结果放只读块，不塞进 textarea

这是「练口语」那个前提的直接落点（见第一节）。只读块下面是两个出口：

- 「重录」→ 回到 `idle`，丢掉这次结果
- 「改用打字」→ 把 textarea 露出来，这次就用打的

### 错误文案分四种，各自说清楚

| 情况 | 文案 |
|---|---|
| 麦克风没权限 | 「没拿到麦克风权限。浏览器地址栏左边可以改回来，或者直接打字回答」 |
| 浏览器不支持（非 HTTPS/localhost、老浏览器） | 「这个浏览器不支持录音，用打字回答就行」 |
| `/status` 说 `available: false` | 按钮直接不显示（不用文案） |
| 转写结果为空 | 「没听到内容。可能是麦克风没收到声音，重录一次试试」 |

**每条都要给出路**（「或者直接打字回答」），不能让用户卡在一个坏掉的按钮上。

### 文件

`static/js/asr.js` 独立成文件，照 `charts.js` 的先例（`interview.html` 已经有个内联的大
`data()`/`methods`，再塞 200 行录音逻辑进去要破 500 行）。仍然是零构建，多一个 `<script>` 标签。

## 七、文件分工

| 文件 | 动作 | 职责 |
|---|---|---|
| `pom.xml` | 改 | 两条 dependency（坐标跟 JitPack 一致，见第八节） |
| `wrapper/asr/AsrProperties.java` | 新建 | 配置 |
| `wrapper/asr/AsrClient.java` | 新建 | 接口 |
| `wrapper/asr/SherpaAsrClient.java` | 新建 | sherpa-onnx 实现，懒加载 + 同步解码 + release |
| `controller/AsrController.java` | 新建 | `/status` + `/transcribe` |
| `static/js/asr.js` | 新建 | 录音、重采样、上传、四态 UI |
| `static/interview.html` | 改 | 麦克风按钮、只读转写块、引 `asr.js` |
| `static/css/app.css` | 改 | 录音态、电平条、只读块样式 |
| `README.md` | 改 | 一次性准备步骤 + 取舍 |

## 八、一次性准备（两件事，都要手工）

### 1. 两个 jar：手工下 + `install-file`

**不做成「`pom.xml` 加 `<repository>` 直接拉」**，尽管官方文档就是那么写的、JitPack 上东西也都在。
原因是本机那份 `settings.xml` 的 `<mirrorOf>*,!lianjia-*</mirrorOf>` 会把 jitpack.io 的请求
改写到阿里云，而阿里云没有这个包（实测 404）。要让 pom 直接拉，得先改 `settings.xml` 加
`,!jitpack.io`——而那份文件**不在仓库里、带公司 Nexus 凭据、不受我们控制**，哪天被重新拷一份
覆盖掉，构建就断，且断成一个看起来像「JitPack 上没有」的误导性报错。

```bash
# 下两个 jar（保持文件名不变，下面 install-file 要用）
#   sherpa-onnx-jvm-1.13.8.jar                          0.2 MB
#   sherpa-onnx-native-lib-win-x64-1.13.8.jar           7.9 MB
# https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/<文件名>

export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml install:install-file \
  -Dfile=sherpa-onnx-jvm-1.13.8.jar \
  -DgroupId=com.github.k2-fsa.sherpa-onnx \
  -DartifactId=sherpa-onnx-jvm \
  -Dversion=v1.13.8 -Dpackaging=jar

./mvnw -s /d/apache-jmeter-5.4.3/settings.xml install:install-file \
  -Dfile=sherpa-onnx-native-lib-win-x64-1.13.8.jar \
  -DgroupId=com.github.k2-fsa.sherpa-onnx \
  -DartifactId=sherpa-onnx-native-lib-win-x64 \
  -Dversion=v1.13.8 -Dpackaging=jar
```

**坐标刻意用 JitPack 那套 `com.github.k2-fsa.sherpa-onnx`，不用项目自己的
`com.k2fsa.sherpa.onnx`**（后者是照它自己的 pom 编译出来的坐标）。这样将来若真想改成
「settings.xml 加一行排除 + pom 加 repository」，`pom.xml` 一个字都不用动，只是换了个下载来源。

⚠️ 版本字面量里的 `v` 是**必须有**的（`v1.13.8`）。artifacts 装进去是什么版本，`pom.xml`
就得写什么版本，两边必须一模一样。

### 2. 模型（约 228 MB）

放**仓库外**一个目录（如 `D:/models/sense-voice/`），下两个文件：

```
model.int8.onnx    228.2 MB   ← 别下 model.onnx，那是 fp32 版，894.2 MB，没必要
tokens.txt           0.3 MB
```

来源：`https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/main`

然后：

```bash
export APP_ASR_MODEL_DIR=D:/models/sense-voice
```

没设这个变量时应用照常启动，`/api/asr/status` 返 `available: false`，页面不显示麦克风按钮。

## 九、验证策略

沿用一期/二期的方式：**仓库里不写测试类**，验证放 `%TEMP%`。

| 验什么 | 用什么 | 要点 |
|---|---|---|
| 转写真的出字 | 新建 `%TEMP%\AsrCheck.java` | 直接调 `SherpaAsrClient`，喂模型仓库里带的 `test_wavs/zh.wav`（0.2 MB），断言输出含预期关键词；再断言**第二次调用**仍正常（验 stream 释放和 recognizer 复用） |
| **字节序** | 新建 `%TEMP%\PcmCheck.java` | 单独验 `byte[]` → `float[]`：`{0x00,0x40}` 应得 `0.5f`，`{0xFF,0x7F}` 应得 `≈1.0`。小端写成大端不抛异常、只出乱码，所以这条不能靠「转写能出字」顺带验 |
| 内存不涨 | 同上，循环转 20 次 | 每次 `System.gc()` 后看 native 内存；没有 `release()` 时这里会明显增长 |
| 没下模型时不炸 | 新建 `%TEMP%\AsrNoModelCheck.java` | `modelDir` 指到空目录，断言应用能起、`/status` 返 `available:false` 且 `reason` 有话说 |
| 接口 + 鉴权 | 扩展 `%TEMP%\ApiCheck.java` | 真 HTTP：没带 token 要 401；带 token 传一段音频拿到 text |
| 页面不白屏 | 扩展 `%TEMP%\PageCheck.js` | 沙箱里没有 `MediaRecorder` / `OfflineAudioContext`，要注入替身；断言 `available:false` 时按钮不渲染、`available:true` 时渲染 |
| 录一段真的 | 人工，浏览器 | 唯一能验「录完真能出字」的方式。顺便验四种错误文案 |
| 静态资源进了 jar | `jar tf target/*.jar \| grep asr.js` | 漏了就是 404 白屏 |

## 十、沿用既有约束

- 仓库里**不写测试类**，验证程序一律放 `%TEMP%`
- **只 commit 不 push**
- 全程中文（代码注释、提交信息、文档）
- `src/main/resources/application.yml` **一动不动**（本地那处指向内部网关的
  `base-url` / `model` 是刻意保留的未提交改动）
- commit 用显式文件路径，不用 `git add -A`
- 截图不进仓库、不加 `.gitignore`

## 十一、已知取舍

| 取舍 | 说明 |
|---|---|
| **两个 jar 不在中央仓库** | `install-file` 这一步在仓库里没有任何记录，**换台机器、或本地仓库被清空，就得重做一次**（两条命令）。这是选「不改 settings.xml」的代价，是明知的 |
| **228 MB 模型不进仓库** | 事实上的「在我机器上能跑」。要真正可移植，得改成 Python 侧车 + HTTP，那就多一个进程和一套部署 |
| **只支持 Windows x64** | 依赖里写死了 `native-lib-win-x64`。换平台要换这一条（`linux-x64` / `osx-aarch64` 等，JitPack 上都有）。这个项目本来就是自用，不抽象 |
| **非流式** | 见第一节。SenseVoiceSmall 非流式，且流式与「练口语」的目的相反 |
| **识别结果不可编辑** | 见第一节。要改就重录。这条是设计意图，不是没做 |
| **中文为主** | SenseVoiceSmall 支持中英日韩粤，但输出不带标点分段（除非再挂标点模型）。面试答话不需要，挂了反而多 100 MB |
| **`available:false` 时静默隐藏按钮** | 不给「语音不可用」的提示：模型没配是部署方的事，不是答题人的事，弹个错只会让人困惑 |

## 十二、决策记录

| 决策 | 选了什么 | 放弃了什么 | 为什么 |
|---|---|---|---|
| jar 怎么引 | 手工下 + `install-file` | pom 加 `<repository>` 直拉 | 那份 `settings.xml` 的 `mirrorOf:*` 会吞掉 jitpack（阿里云实测 404），而改它动的是不在仓库里、带公司凭据的文件 |
| 装进本地仓库的坐标 | `com.github.k2-fsa.sherpa-onnx` | `com.k2fsa.sherpa.onnx`（项目自己的） | 跟 JitPack 一致，将来换下载来源时 pom 不用动 |
| 识别引擎 | sherpa-onnx + SenseVoiceSmall int8 | Vosk / FunASR Python 侧车 | 单 jar + 单文件模型，无 Python 进程；Vosk 中文模型效果差一档 |
| 音频在哪处理 | 浏览器重采样成 16k PCM16 | 传原始 webm 让服务端转 | 服务端零音频依赖（不要 FFmpeg），少一层编解码，少一份要维护的东西 |
| 结果落在哪 | 只读块 | textarea | 「练口语」：能改就会边想边改稿，练的不是表达 |
| 有没有流式 | 没有 | 边说边出字 | 同上；且 SenseVoiceSmall 非流式 |
| 记不记来源 | 不记 | 加 `answer_source` 列 | `schema.sql` 无迁移机制，加列对已有库不生效 |
| 没下模型时 | 正常启动 + 隐藏按钮 | 启动即报错 | 打字的路径必须永远可用 |
| native 库加载 | 靠 jar 内嵌自动解压 | 配 `java.library.path` | 它自己会从 `sherpa-onnx/native/win-x64/` 解压加载，配了是多余的 |