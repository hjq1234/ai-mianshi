# 流式语音识别设计（离线那条保留，两条并存）

## 要解决的事

现在的语音答题是「录完整段再转」：点麦克风 → 说 → 点停止（或到上限自动停）→ 等转写 → 文字落进答题框。
用户想要「边说边出字」。

**不替换，是并存。** 现有离线那条一行不改；新增流式那条，答题框里加一个切换。
理由不是偷懒：两条路的**文本质量**不一样，而且差距是模型决定的，不是代码能补的。

| | 离线（现有） | 流式（新增） |
|---|---|---|
| 模型 | SenseVoiceSmall int8，228 MB | streaming zipformer-zh-14M，int8 三件套合计 24.1 MB |
| 出字时机 | 录完一次性出 | 边说边出 |
| 标点 | **有**（模型自带） | **没有**，吐出来是裸字串 |
| ITN | 有（「二零二五」→「2025」） | 没有 |
| 准确率 | 高 | 低一档（14M 参数摆在那儿） |

所以要标点清楚、要准确 → 用离线；要边说边出 → 用流式。谁也不能替谁。

## 已验证的技术事实

不是查文档得来的，是拆本地那个 jar 和探 release 页面得来的：

```
sherpa-onnx-jvm-v1.13.8.jar 里已经有（**不需要新增任何依赖**）：
  OnlineRecognizer        createStream() / isReady() / decode() / isEndpoint() / reset() / getResult()
  OnlineStream            acceptWaveform(float[], int sampleRate)
  OnlineRecognizerConfig  setEnableEndpoint(boolean) / setEndpointConfig(EndpointConfig)
  OnlineTransducerModelConfig  setEncoder/setDecoder/setJoiner
  FeatureConfig           builder().setSampleRate(16000).setFeatureDim(80)
  OnlineRecognizerResult  getText()

模型（asr-models release tag 下）：
  sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2    74 MB 包，中文专用
      → 解开后 int8 三件套 24.1 MB（encoder 21.6 / decoder 1.89 / joiner 1.80）
      → 目录里还带 test_wavs/0.wav（5.6 秒中文），验证程序直接用它
  sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20  511 MB，中英双语（备选）
  silero_vad_v5.onnx                                          2.3 MB（本次不用）
```

`isEndpoint()` 存在是关键：**「说到停顿就定稿一句」是模型自带的**，不用自己做 VAD 断句。

## 为什么是分片 POST 而不是 WebSocket

每 100ms 发一个小 POST（约 3.2 KB），服务端返回当前这半句。

- **零新依赖**。WebSocket 要加 `spring-boot-starter-websocket`，本地仓库不一定有，
  离线构建（`-o`）会直接失败
- **鉴权和日志都是白捡的**。这条路是普通 HTTP，`AuthInterceptor` 和
  `RequestLogFilter` 照常生效；WebSocket 是另一条握手链路，两个都得手写一套
- 本机跑 10 次/秒无所谓，每次请求 300 字节的头 + 3.2 KB 的 body

代价是**会话要自己管**：超时要能回收 `OnlineStream`（不回收就是漏 native 内存，
和 `SherpaAsrClient.transcribe` 里那句 `stream.release()` 是同一个理由）。
做法是懒清理——每次 start/chunk 顺手扫一遍过期会话，不引 `@Scheduled`。

## 数据流

```
流式模式
  浏览器
    getUserMedia 的 stream
      → AudioContext({sampleRate: 16000})       ← 让浏览器替我们重采样
      → AudioWorkletNode (asr-pcm-worklet.js)   ← 独立线程，拿 Float32
      → 主线程每 100ms 攒一片 → POST /api/asr/stream/chunk
                              ← { partial: "我要用redis", final: "" }
    final 非空 → 追加进 textarea，灰字行清空
  → 点停止 → POST /api/asr/stream/stop → { final: "..." } → 追加 → 清会话

离线模式（一行不改）
  MediaRecorder → 整段 → OfflineAudioContext 重采样 → POST /api/asr/transcribe
```

### 为什么中间态不能直接写 textarea

流式过程中那些字**一直在被改写**：先说「我要用 redis」，后面出字变成「我要用 Redis 做缓存」。
直接写进 textarea 的话，用户打的草稿会被反复搅进去，光标位置和刚改的那个字都会被覆盖。

所以：**中间态显示在麦克风上方一行灰字里**（`asrPartial`），只有 `final`（说到停顿）才
**追加**进 textarea。「追加不覆盖」这条语义因此保住了 —— 和离线那条完全一致。

### 为什么用 AudioWorklet 而不是 MediaRecorder

`MediaRecorder` 吐的分片是**压缩容器碎片**（webm/opus），只有第一片带容器头，
单独丢给 `decodeAudioData` 解不开。所以它天然拿不到实时 PCM，流式这条路必须换采集方式。

`AudioContext({sampleRate: 16000})` 那句是这次最省事的一步：浏览器会**替我们把麦克风重采样到
16k**，`asrToPcm16k` 那套 `OfflineAudioContext` 手动重采样在流式这条路上完全不需要。
保险起见仍要判断 `ctx.sampleRate` 实际值：拿不到 16k 的浏览器上退回手工抽取（每 ratio 个取 1）。
采样率不对不会报错，只会转出一段看着像话其实全错的中文 —— 和 `/transcribe` 那条
`sampleRate != 16000` 守卫是同一个道理，宁可当众降级也不要静默出错的文本。

## 接口

```
GET  /api/asr/status
  → { available, reason, nativeVersion, maxSeconds,      ← 离线，字段没动
      streamAvailable, streamReason }                    ← 新增，**平铺不嵌套**

POST /api/asr/stream/start
  → { sessionId }

POST /api/asr/stream/chunk?sessionId=7     body: 裸 PCM16 小端
  → { partial, final }

POST /api/asr/stream/stop?sessionId=7
  → { final }
```

**为什么 `streamAvailable` 平铺，不写成 `stream: {available, reason}`**：
`AsrApiCheck` 里的 `extract(json, key)` 取的是**第一个**同名 key。嵌套对象里也有 `available`，
一旦哪天字段顺序变了，`extract(statusBody, "available")` 就会取到流式那个，
断言开始测另一个东西 —— 而且**可能是绿的**。平铺从根上避掉这个坑。

`stop` 之后会话销毁；`chunk` 的 `final` 非空只表示「检测到一个停顿」，
**会话继续活着**（用户还在说下一句）—— 这是和 `stop` 的关键区别，写错了会变成说一句就断。

## 前端

- `static/js/asr-pcm-worklet.js`（新）：AudioWorklet 处理器，把 `Float32` 分片 postMessage 出来。
  **必须是独立文件**，AudioWorklet 用 `addModule(url)` 加载，不能内联
- `static/js/asr-stream.js`（新）：流式客户端（`asrStreamData()` + `ASR_STREAM_METHODS`），
  和 `asr.js` 平级，互不 import
- `interview.html`：答题框底部那排加一个模式选择（`<select class="asr-mode">`）+ 灰字行
- `app.css`：`.asr-mode`、`.asr-partial`

**模式选择记 localStorage**：第一版砍掉的「语音/打字」模式切换，被嫌弃的原因是
**每题都要选一次**。这次的选择是「用哪个引擎」，属于设一次就完了的事，所以持久化。
下次打开还是上次选的，不用每题重选。

默认**流式**（新做的东西要能一眼看见）。

## 代价与已知不做的

- **流式没有标点**。要标点得再挂一个标点模型（`OnlinePunctuation`，另加一个模型文件）。
  不做：先把主路径跑通，标点可以自己在框里补，何况框本来就能改
- **不做 VAD 静音自动停**。`Vad` + `SileroVadModelConfig` 也在这个 jar 里（只要一个 2.3 MB 的
  `silero_vad_v5.onnx`），但现在有 endpoint 检测就够用了：说到停顿会自动定稿，
  用户不需要点任何东西就能继续下一句
- **不做多用户并发**。会话上限给个小的（4 个），超了拒绝。单用户自用
- **不动离线那条**。`AsrClient` / `SherpaAsrClient` / `/api/asr/transcribe` 一行不改

## 验证

- `src/test/java/.../checks/AsrStreamCheck.java`（能直接 main 跑，不是 JUnit）：
  真起服务，把 `zh.wav` 按 100ms 切片喂给 `/stream/chunk`。
  **核心断言是「喂到一半就已经有字了」** —— 这一条才能证明它真是流式；
  只断言「最后能转出正确文本」的话，把整段攒起来一次性解码也照样绿
- `src/test/js/AsrStreamPageCheck.js`：假 AudioWorklet / 假 fetch，验
  分片攒批、final 追加进框、partial 不动框、切模式、停止后清会话
- `node src/test/js/AsrPageCheck.js` 和 `%TEMP%\PageCheck.js` 不能回归