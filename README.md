# AI 模拟面试系统

用 **Spring Boot 4 + 手写泛型图引擎** 做的多轮 AI 模拟面试系统。
面试走向由实时评分驱动，不是固定题库。

## 这是什么

一场面试的流程被表达成一张图：

```
start → question → wait_answer → evaluate → [分支决策] ─┬→ deepen   ┐
                                                        ├→ continue │
                                                        ├→ lower    ├→ question（循环）
                                                        ├→ switch   ┘
                                                        └→ end_loop → 生成报告
```

每答完一题，LLM 给一个 0-10 的评分和五个维度的细分，同时判断下一步该
「往深里追问 / 换个角度 / 降难度 / 换话题」。图按这个判断路由到对应节点，
再回到出题节点形成循环。答满 10 题（或用户主动结束）就走到 `end_loop` 生成综合报告。

## 三个关键设计

**1. 挂起 = 存现场然后退出，不是停在那儿等**

`wait_answer` 是全图唯一的挂起点。它看 `answer` 是否为空来决定返回 `Suspend` 还是 `Next`。
返回 `Suspend` 时引擎把状态序列化成 JSON 存进 `t_interview_record.state_json`，
把游标存进 `cursor`，然后**立刻退出**——请求线程释放，没有线程被占住，
也不需要心跳、session 管理器、并发锁。

**2. 断点续传是免费的**

因为现场全在库里，「继续面试」不需要任何恢复逻辑：读一条记录，从 `cursor` 接着跑就行。
关掉浏览器、服务重启、LLM 挂了——重新打开页面都在原地。

**3. 图引擎不认识「面试」**

`graph/` 包只认识泛型 `S`。节点需要的服务（LLM 客户端、DAO）通过 `NodeContext`
这个「类型 → 实例」注册表拿，引擎侧完全不出现业务类型。
跨切面需求（记录执行轨迹）通过 `GraphListener` 接口反转依赖。

所以这个引擎可以直接拿去做别的流程，比如简历预测题。

## 快速开始

### 1. 配置模型

只支持 OpenAI 兼容协议的模型。默认用 DeepSeek：

```bash
export DEEPSEEK_API_KEY=sk-你的key
```

换通义 / 豆包 / Kimi：改 `src/main/resources/application.yml` 里的
`app.llm.base-url` 和 `app.llm.model` 两行即可。

### 2. 启动

```bash
./mvnw -Dmaven.repo.local=D:/repository spring-boot:run
```

> **本地仓库要显式指到 `D:/repository`。** 这台机器上 IDEA 的 Maven 配置
> （`.idea/workspace.xml` 里的 `localRepository`）用的是它，而命令行不指的话默认落在
> `C:\Users\<你>\.m2\repository`——两个**不是同一个仓库**（`~/.m2/settings.xml` 不存在，
> `-s` 指的那个 `settings.xml` 也没写 `localRepository`）。
> 不指就会出现「`mvnw compile` 明明过了，IDEA 报找不到包」这种两边不一致的怪事：
> 手工 `install:install-file` 装进一个仓库，另一个自然看不见。
>
> 如果你的 Maven 用了公司私服，再加上 `-s 你的settings.xml`。

### 3. 打开

浏览器访问 <http://localhost:8080/login.html>

默认账号：`admin` / `admin123`（首次启动自动创建）

### 4. 走一遍

1. 「我的简历」上传一份 PDF（可选，但传了出题会更贴合你的经历）
2. 「开始面试」选岗位、方向、难度 → 点开始
3. 等 5-15 秒出第一题，回答后提交。上一轮的题目、你的回答、评分、评语会收进
   题目上方一个默认折叠的「上一轮回顾 ▸」，点开才展开
4. 中途可以直接关掉页面 —— 回「面试记录」点「继续面试」能接着答
5. 答完 10 题或点「结束面试」→ 自动跳复盘页：四张图（能力雷达 / 分数趋势 /
   图执行路径 / 话题覆盖度）+ 决策链表 + 逐题详情（默认收起，点题号展开看
   问答原文和五维），右上角可以导出 Markdown

### 5. 看日志

每一步都有日志可查，不用靠数据库反推：

```
HTTP POST /api/interview/1/answer → 200 | 8421 ms | user=1
第 3 题出题完成 | 话题=并发编程 难度=中等 | 3120 ms | 58 字符 | 题目：……
话题「并发编程」已问满 3 轮，把 LLM 给的 CONTINUE 改判为 SWITCH
第 3 题评分 | 话题=并发编程 难度=中等 | 5210 ms | 总分=6.5 | LLM 建议=CONTINUE 实际分支=switch
图分支决策 | 面试=1 第 3 题 | evaluate → switch
图执行结束 | 面试=1 状态=SUSPENDED 停在=wait_answer 游标=wait_answer | 8340 ms
```

请求日志走 `RequestLogFilter`（挂在 `/api/*` 上），只记方法和耗时这类元数据，
不记请求体——回答和简历是候选人的隐私内容。出题日志打题目全文但不打 prompt，
因为简历摘要就拼在 prompt 里。语音转写同样只记耗时和字数，不记转出来的文本。

### 语音答题（可选，本地模型）

答题可以用语音说，识别在本机跑，不联网、音频不上传。

**这一步是可选的**：没配模型的话应用照常启动，只是答题区不显示麦克风按钮，打字照旧。

**1. 装两个 jar（约 8 MB）**

sherpa-onnx 没发 Maven 中央仓库，发在 JitPack；而本机 `settings.xml` 的
`<mirrorOf>*,!lianjia-*</mirrorOf>` 会把 jitpack.io 的请求改写到阿里云（阿里云没这个包），
所以改成手工下载 + 装进本地仓库：

```bash
# 从 GitHub release 下这两个（版本字面量里的 v 是必须的）
#   sherpa-onnx-jvm-1.13.8.jar
#   sherpa-onnx-native-lib-win-x64-1.13.8.jar
# https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/<文件名>

./mvnw install:install-file -Dfile=<下载路径>/sherpa-onnx-jvm-1.13.8.jar \
  -DgroupId=com.github.k2-fsa.sherpa.onnx -DartifactId=sherpa-onnx-jvm \
  -Dversion=v1.13.8 -Dpackaging=jar \
  -DlocalRepositoryPath=D:/repository

./mvnw install:install-file -Dfile=<下载路径>/sherpa-onnx-native-lib-win-x64-1.13.8.jar \
  -DgroupId=com.github.k2-fsa.sherpa.onnx -DartifactId=sherpa-onnx-native-lib-win-x64 \
  -Dversion=v1.13.8 -Dpackaging=jar \
  -DlocalRepositoryPath=D:/repository
```

`-DlocalRepositoryPath` 这一条**不能省**：`install:install-file` 默认装进
`C:\Users\<你>\.m2\repository`，而 IDEA 读的是 `D:/repository`（见上面「启动」那段的说明）。
装错了地方的表现是「命令行 build 过、IDEA 里 `程序包com.k2fsa.sherpa.onnx不存在`」，
换个仓库再装一次才对得上。

换台机器、或清了本地仓库，这两条要重做一次。

**2. 下模型（两套，各下不下都行）**

两套模型在**同一个 sherpa-onnx 的 release tag 下**（`asr-models`，不是版本号那个 tag）：

| | 离线（录完再转） | 流式（边说边出字） |
|---|---|---|
| 模型 | SenseVoiceSmall int8 | streaming zipformer **zh-int8-2025-06-30** |
| 大小 | **228 MB**（`model.int8.onnx` + `tokens.txt`） | **161 MB**（encoder / decoder / joiner 三个 `.onnx` + `tokens.txt`） |
| 出字时机 | 停止录音后一次性出 | 每 100ms 一片，边说边出 |
| 标点 / 数字规整 | 有（词表里就有 `，。？！`，且自带 ITN） | **没有**，且换流式模型也没有（见下面「已知不完美」） |
| 识别准确率 | 更好 | 差一档。但流式这边已经换成 icefall 的 **large** 模型（多中文数据集训练），比早先那个 14M 的强 |
| 用途 | 想一次拿到干净的整段 | 想看见字在长、长回答不用等 |

```bash
# 离线（约 228 MB）。别下 model.onnx，那是 fp32 版 894 MB，没必要
# https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2
→ 只要 model.int8.onnx（228.2 MB）+ tokens.txt（0.3 MB）

# 流式（约 127 MB）。整个解压，里面四个文件都要
# https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2
→ encoder.int8.onnx（153.7 MB）
  decoder.onnx      （  4.9 MB，发布包里这个本来就是 fp32）
  joiner.int8.onnx  （  1.8 MB）
  tokens.txt        （ 2002 个 token，字节级 BPE）
```

早先用的是 `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（24 MB，
`*-epoch-99-avg-1.int8.onnx` 那套文件名）。**两个发布包的文件名规则完全不同**，
换模型时 `AsrProperties.Stream` 里那四个文件名要一起动。

**3. 指过去**

```bash
export APP_ASR_MODEL_DIR=D:/models/sense-voice                                          # 离线
export APP_ASR_STREAM_MODEL_DIR=D:/models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30  # 流式
```

两个变量相互独立，**只下一个是很正常的状态**：另一个对应的选项在页面里是灰的，
答题照常。两个都没设时 `/api/asr/status` 返 `available:false` + `streamAvailable:false`，
麦克风按钮和模式下拉都不显示，只剩打字。

> 文件名（`encoder.int8.onnx` 这几个）**不在 `application.yml` 里**，
> 是 `AsrProperties.Stream` 的字段默认值。目录跟机器绑定、文件名跟模型绑定 ——
> 而 yml 因为里面有本地的 `base-url` / `model` 改动**没有提交**，文件名塞进去的话，
> 换台机器的人下了模型、环境变量也设了，还是会看到「流式模型文件不存在」。

**它是怎么工作的**

- **离线**：浏览器录音 → 页内用 `OfflineAudioContext` 重采样成 16k 单声道 PCM16
  → `POST /api/asr/transcribe` → 结果落进答题框
- **流式**：`AudioWorklet` 每 100ms 交一片裸 PCM → `POST /api/asr/stream/chunk` 一片一片发
  → 正在说的那半句显示在**麦克风上方一行灰字**里，说到停顿定稿才追加进答题框

两条路的服务端都不碰音频格式（不引 FFmpeg），`graph/` 包和评分链路一行没动。

**语音和打字不是两个模式**：就一个答题框，麦克风是它右下角常驻的图标。转写回来是
**追加**进去，不覆盖你已经打的字。第一版做成了互斥的模式切换（语音模式下把输入框藏起来、
转写结果只读），实测太别扭 —— 说错一个字就得整段重录。砍了。

**「流式 / 离线」那个下拉选的是采集方式，不是「语音还是打字」**，答题框始终只有一个。
选择记在 `localStorage`，默认流式。

**半句为什么不直接写进答题框**：因为 `partial` 一直在被改写（「我要用 redis」→
「我要用 Redis 做缓存」），写进框里会把你打的草稿反复搅乱、光标也没了。所以它只进那行灰字，
**只有定稿的 `finalText` 才追加进框**。灰字行做成斜体浅色，一眼能看出「这还不是框里的内容」。

**为什么用 AudioWorklet 而不是继续用 MediaRecorder**：`MediaRecorder` 吐的是压缩容器的
**碎片**（webm/opus），只有第一片带容器头，单独丢给 `decodeAudioData` 解不开。流式这条路
必须直接拿裸 PCM。这是 AudioWorklet 唯一的存在理由。

**为什么要分片而不是 WebSocket**：不用引任何依赖，鉴权（`Authorization` 头）和访问日志
白捡 —— 现有的 `RequestLogFilter` 直接就能看见每个分片。单用户下分片的开销可以忽略。

**已知不完美**：流式那条**没有标点**，也**不做数字规整**，识别准确率还比离线差一档。

没有标点不是「模型小、猜不准标点」，是**词表里压根没有标点这个字**。查过现在这个模型的
`tokens.txt`：2002 个 token，是字节级 BPE（`<blk>` / `<sos/eos>` / `<unk>` 加
`<0x00>`…`<0xFF>` 那些字节），`，。？！、；：` 和 `,.?;:` **一个都没有**。
模型的输出是「从词表里挑 token」，词表里没有的东西它**结构上就吐不出来**。
（离线那套的 `tokens.txt` 有 25055 个 token，`、。「」！，：；？` 都在里面 —— 所以它有标点。）

而且**换流式模型也解决不了**：顺手查了另外两个流式模型的词表，
`sherpa-onnx-streaming-zipformer-zh-2025-06-30`（2025 年最新的中文流式）2002 个 token
零标点，`streaming-paraformer-bilingual-zh-en` 8404 个 token 里只有一个 `.`（英文缩写点）。
原因是标点要判断「这句话说完了」，必须**往右看**，而流式模型被设计成只看左边加很短的
右侧上下文 —— 它连句号该放哪都无从判断。流式 ASR 吐标点这件事本来就得靠后处理。

所以补标点的路只有一条：在**定稿的整句**上再挂一个 `OfflinePunctuation` 模型
（jar 里有这个类，流式那条已经有「定稿」这个时机了，正好接）。那是另一件事。

## 技术栈

| 层 | 选型 | 为什么 |
|---|---|---|
| 语言 | Java 21 | record / sealed / switch 表达式用得很顺手 |
| 框架 | Spring Boot 4.1.1 | `spring-boot-starter-webmvc`，自带 Jackson 3 |
| 数据库 | SQLite | 单文件、零部署。开了 WAL + `busy_timeout` |
| 数据访问 | JdbcTemplate | 零魔法，每条 SQL 都看得见 |
| 大模型 | DeepSeek（OpenAI 兼容） | 国内直连、便宜 |
| PDF | PDFBox 3.0.4 | 简历解析 |
| 密码 | `spring-security-crypto` | 只要 BCrypt 一个类，不引 Spring Security 全家桶 |
| 前端 | Vue 3 CDN 版 | 无 node/npm 构建链，一个 `mvn package` 出一个 jar |
| 图表 | ECharts 5.6.0（CDN） | 复盘页三张坐标图。锁 5.6.0 而不是 6.x：只用 radar/line/bar，要的是 API 稳定 |
| 语音识别 | sherpa-onnx + SenseVoiceSmall int8（离线）/ streaming zipformer zh-int8-2025-06-30（流式） | 本地跑，不联网、音频不出机器；一个 jar 带两套 API（`OfflineRecognizer` / `OnlineRecognizer`），不需要 Python 进程 |

## 目录结构

```
src/main/java/com/ke/nhservice/aimianshi/
├── graph/          图引擎（纯通用，不 import biz/controller/wrapper）
├── wrapper/        第三方封装（llm / pdf / asr）
├── biz/            业务（interview / resume / user / knowledge）
├── controller/     REST 接口
└── common/         工具、异常、常量、配置、DTO

src/main/resources/
├── application.yml 配置（含话题池）
├── schema.sql      建表脚本，启动自动执行
├── prompts/        9 个提示词（改提示词不用改 Java）
└── static/         6 个页面
    ├── js/app.js     全局工具 + 复盘导出
    ├── js/charts.js  复盘页四张图（三张 ECharts + 泳道图拼 HTML）
    └── css/app.css
```

## 数据库

五张表，都在 `schema.sql` 里：

| 表 | 作用 |
|---|---|
| `t_user` | 用户。密码 BCrypt |
| `t_resume` | 简历。PDF 解析出的纯文本 |
| `t_interview_record` | 面试记录。**`state_json` + `cursor` 是断点续传的关键** |
| `t_interview_dialogue` | 逐题问答 + 评分 + **图的分支决策** |
| `t_graph_trace` | 节点级执行轨迹，用来还原图实际走的路径 |

数据文件默认在 `./data/interview.db`，删掉即可重置。

### 删除是软删

`t_interview_record` 上有个 `deleted` 列（软删标记）。删除面试记录是**标记隐藏，不是真删**：
数据留着，只是所有查询都带 `AND deleted = 0`。这么做是因为 `/stats` 的历史均分按场次聚合，
真删会让「删掉一场答砸的面试」顺手把「历史水平（N 场）」也改了。

需要过滤的读查询恰好四处（`findRecord` / `listByUser` / `countByUser` / `listFinishedIdsWithAnswers`），
都在 `InterviewDao`，类注释里列着——**加新查询时要回去补**，漏一处不报错，
只是那一处还看得见已删的记录。子表（`t_interview_dialogue` / `t_graph_trace`）不用过滤：
它们的查询收的都是 record id，而那些 id 全出自上面这四处之一，record 这层挡住了子表就查不出来。

老库（已经建过表的）靠 `SqliteInitializer` 在启动时探测缺列 + `ALTER` 自动补上，
因为 `CREATE TABLE IF NOT EXISTS` 对已存在的表是整条跳过的。要真删就手工
`DELETE FROM` 三张表（软删的意义就是数据都留着，不做回收站界面）。

### 出题从哪来

`app.interview.topics` 是话题池，`switch` 分支从这里挑还没聊过的话题。
每个方向的池子第一个都是「项目经历」，命中它时出题会带上 `hint_practice`：

- 简历摘要里有具体项目 → 点名那个项目问他的取舍和踩过的坑
- 简历摘要为空 → 假设一个真实业务场景来问（「线上接口 P99 从 200ms 涨到 2s，你从哪一步开始查」）

所以**不传简历也能问出场景题**，不会退化成纯八股。这么设计是为了让 `practice`
这个维度有依据——话题池全是纯概念时，LLM 只能凭空凑一个中间分。

同一个话题最多问 `max-follow-up`（默认 3）题，问满强制换话题，不完全听 LLM 的。

## 接口

```
POST   /api/auth/login              {username, password} → {token, nickname}
POST   /api/auth/logout
GET    /api/auth/me

POST   /api/resume/upload           multipart/form-data
GET    /api/resume/list
POST   /api/resume/{id}/default
DELETE /api/resume/{id}

POST   /api/interview/start         {resumeId, position, company, domain, difficulty}
POST   /api/interview/{id}/answer   {answer}
POST   /api/interview/{id}/finish   用户主动结束
POST   /api/interview/{id}/resume   从游标继续，不需要新答案
DELETE /api/interview/{id}          软删一条面试记录（标记隐藏，数据留着）
GET    /api/interview/{id}/state    刷新页面用，不推进图
GET    /api/interview/list
GET    /api/interview/{id}/detail   复盘详情（含 dimensionAverages 本场五维均分）
GET    /api/interview/{id}/trace    节点轨迹
GET    /api/interview/stats         历史五维均分，给雷达图做对比

GET    /api/asr/status              两条语音路各自的可用性（平铺的 available / streamAvailable）
POST   /api/asr/transcribe          离线：body 是裸 PCM16 小端字节（整段），返回 {text}
POST   /api/asr/stream/start        流式：开会话 → {sessionId}
POST   /api/asr/stream/chunk        流式：一片音频（裸 PCM16，约 100ms）→ {partial, finalText}
POST   /api/asr/stream/stop         流式：结束会话 → {finalText}（最后没定稿的那半句）
```

`/status` 里那两组字段是**平铺**的（`available` / `streamAvailable`），不是
`stream: {available}` 嵌套。原因很具体：验证程序 `AsrApiCheck` 取 JSON 字段时取的是
**第一个**同名 key，嵌套之后 `extract(status, "available")` 哪天就会取到流式那个，
断言开始测另一个东西 —— 而且**它是绿的**。

`/stream/chunk` **不接受并发**：音频是时序数据，两片乱序到达不会报错，只会转出一段
流利但完全不对的中文。前端是「等上一个响应回来才发下一个」。会话空闲 2 分钟
（`app.asr.stream.idle-seconds`）自动回收，同时最多 4 个会话。

除 `/api/auth/login` 外全部要 `Authorization: Bearer <token>`。

`/stats` 刻意**不带 `{id}`**：它是「所有已结束场次」的聚合，不属于任何一场。
它只统计**真答过题**的场次（`EXISTS` 那道判断），否则一次启动就失败的空场次会
把「历史均分（N 场）」的 N 撑大。聚合规则（某个维度全场都是 null 就不进分母）
只有 `InterviewStats` 一份实现，`/stats` 和 `detail.dimensionAverages` 共用，
前端不自己算。

复盘页右上角的「导出 Markdown」是纯前端 Blob 下载，**没有新增接口**：
`detail` 已经返回了题目、回答、评语、五维、分支决策和综合报告。
不用 `<a href>` 直接指向接口，是因为那样带不上 `Authorization` 头。

### 复盘页的四张图

| 图 | 画什么 | 为什么这么画 |
|---|---|---|
| 能力雷达图 | 本场五维均分 + 历史均分 | 两条多边形才看得出「这场比平时强还是弱」。本场没有的维度（纯概念题的 `practice`）按 0 画并另起一行小字说明，不假装有分 |
| 分数趋势 | 逐题得分折线 + 本场均分虚线 + 话题色带 | 色带按「连续同话题」分段，「第 3~5 题都在 JVM 上、第 6 题开始聊并发」一眼可见。末题得分和均分差不到 1 分时刻意**不画末题那个数值标签**：均分虚线的标签锚在网格右边缘、线的上方，末题的标签也在右边缘、点的上方，同高就叠成一团（实测那场 10 题均分 7.5、末题也正好 7.5）。点本身的数值 tooltip 里还有 |
| 图执行路径 | 每轮一行的泳道时间线 | 引擎跑 10 题有 60+ 条 trace，画成节点图没法看（大多是一条 10 次的循环）。泳道色块宽度 = 该节点耗时，**不含你作答的等待时间**；所以「引擎耗时」= Σ`cost_ms` = 色块宽度之和，而「从开始到结束」另算记录的时间跨度。**两个都要给**：只看跨度的话，隔天续答的那场会显示成 14.8 小时 |
| 话题覆盖度 | 每个话题的**均分**（0~10），题数写在标签里 | 原本画的是题数，但 `max-follow-up` 生效后每话题最多 3 题，10 题凑成「5 话题 × 2 题」是常态——画出来是五根一样长的柱子，视觉权重最大的那根承载的是恒定值。改成柱高给均分、最弱的排最上面 |

泳道图**故意不用 ECharts**：它本质是「表格 + 色块」，用 ECharts 的 custom series
要写一整套 `renderItem` 坐标数学，而 flex + 百分比宽度二十行就够了。

泳道色块的宽度按耗时归一化到「最慢的一轮」，所以 `出题`/`开场` 这些快节点一定被压到
宽度下限上——`.swim-block` 的 `min-width` 和 `charts.js` 里 `NODE_LABELS` 的最长字数
是一对必须同步的耦合，标签超长会被 `text-overflow` 截成「等待作…」（第一版就是）。
变化最大的几个色块宽度差 3 倍以上时，看宽度比看数字快。

话题图**不截断坐标轴**：均分 7.2 和 8.0 的柱子只差 10%，看着像一样——这是因为这场
每个话题水平确实差不多，不是图画错了。把轴从 6 起能立刻拉开差距，但那是靠视觉骗人。

逐题详情默认全部收起：一题一行（题号 / 话题 / 难度 / 题面 / 得分），点开才展开问、答、
评语、五维。平铺的那版一张卡片一千多 px，想翻到底下的综合报告得滚很久。

三张 ECharts 图各自 `try/catch`，任何一张挂了只把容器换成一行说明，不牵连整页；
`echarts` 本身没加载出来（CDN 打不开）同理。`/stats` 取不到时只降级雷达图的历史那条线。

## 几个已知的取舍

| 取舍 | 说明 |
|---|---|
| LLM 失败重试 4 次，间隔 5s/10s/20s/40s | 最坏情况光等待 75 秒。前端 loading 文案已写明 |
| 评分失败不中断面试 | 兜底为 `CONTINUE`（本话题已问满时仍会被守卫改判 `SWITCH`），本题按 0 分记，已答的题都保住 |
| 实践维度可能是空的 | 纯概念题没有项目背景就不打分（页面显示 `-`），不凑一个中间分出来 |
| 同一话题最多问 3 题 | `app.interview.max-follow-up`。问满强制换话题，不再「一直换个角度」换到题目重复 |
| 出题失败 → 游标停在 `question` | 状态已落库，修好后调 `/resume` 就能接着跑 |
| 图表依赖 CDN | ECharts 从 jsdelivr 引，离线环境下三张图会显示「图表库没加载出来」，页面其余部分照常 |
| 泳道图在 40 题以上会变长 | 每轮一行、不做虚拟滚动。10 题的设计上限下没问题 |
| 无流式输出 | 事件驱动架构的必然结果。要加就让 `/answer` 单独返回 SSE |
| 语音依赖两个不在中央仓库的 jar | 换机器 / 清了本地仓库要重跑两次 `install:install-file`，仓库里没有东西记录这一步 |
| 两套模型都不进仓库（228 MB + 161 MB） | 事实上是「在我机器上能跑」。要真可移植得改成 Python 侧车 + HTTP |
| 流式那条**没有标点** | 不是模型不准，是词表里没有标点这类 token（换哪个流式模型都一样）。补的话要在定稿句子上再挂一个 `OfflinePunctuation` 模型，是另一件事 |
| 流式识别准确率比离线差一档 | 流式模型看不到右侧上下文，这是它换不掉的代价。encoder 已经从 21 MB 换到 154 MB（large 模型），实时系数 0.11，还有很大余量可再换更大的 |
| 流式会话最多同时 4 个，空闲 2 分钟回收 | `app.asr.stream.max-sessions` / `idle-seconds`。前端不正常退出（关标签页）时那次会话会挂到过期才回收，表现是「试了几次之后点麦克风说会话太多」——等两分钟，或者重启 |
| `partial` 不进答题框，只在麦克风上方那行灰字里 | 它一直在被改写，写进框里会把你打的草稿反复搅乱、光标也没了 |
| 只支持 Windows x64 | `pom.xml` 里写死了 `native-lib-win-x64`。换平台改那一行 artifactId 即可 |
| 转写结果可以直接改 | 第一版是只读的，理由是「能改就会边想边改稿，练的就不是口语表达了」。真用起来太别扭：说错一个字就得整段重录。现在当草稿用 |
| 语音上限 `app.asr.max-seconds` | 默认见 `AsrProperties`。到点自动停并转写（等用户自己发现「已经说了几分钟」不如替她停掉——停了还能转写，超了服务端直接拒，那段话就白说了） |
| token 无法主动失效 | 登出只是前端删 token。单用户自用够用 |
| 删除面试记录是软删（标记隐藏） | 数据留着，`t_interview_record.deleted = 1`。真删要手工 `DELETE FROM` 三张表；不做回收站界面，要找回来 `UPDATE … SET deleted = 0` |
| 知识库只留接口不实现 | 一期 YAGNI，见文末「还没做的」 |
| 无注册流程 | 账号直接建库 |
| 部署前必须改 `app.auth.secret` | 默认值是 `change-me-before-deploy-please`，不改等于谁都能签 token |

## 二期（已完成）

复盘页的雷达图、分数趋势图、图路径可视化（泳道时间线）、话题覆盖度。

数据（五维评分、`t_graph_trace`）一期就已经采全了，所以二期**只加了一个接口**
（`/api/interview/stats`，历史均分）和一个前端文件（`static/js/charts.js`），
`graph/` 包一行没动。原设计里的「力导向节点图」换成泳道时间线，理由是拿真数据
比过：一场 10 题就是 60+ 条 trace 的一条循环，力导向画出来是一团糊。

## 还没做的

- 嵌入知识库检索（`biz/knowledge` 只留了接口）
- 流式输出（出题要等 5-15 秒，期间前端只能转圈）
- 给流式的定稿句子补标点和数字规整（挂 `OfflinePunctuation`，见「语音答题」那节末尾）
- 用 VAD 让离线那条也「按停顿切段」（现在的折中是流式那条承担了这件事）

claude --resume 8f9b2c6a-f53f-4029-8b0f-b5d1a0456b24