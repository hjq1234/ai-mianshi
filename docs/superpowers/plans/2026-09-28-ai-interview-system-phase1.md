# AI 面试系统 · 一期实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Spring Boot 4 + 手写泛型图引擎实现一个可真实完成 10 题多轮模拟面试的系统，支持中途退出后继续、逐题评分、决策链复盘。

**Architecture:** 事件驱动 + 状态落库。图引擎（`graph/` 包）只认识泛型 `S`，通过 `NodeContext` 类型注册表拿到业务依赖；`wait_answer` 节点是全图唯一挂起点，挂起时把 `state_json` + `cursor` 存库后立即释放请求线程；下一次 HTTP 请求从 `cursor` 续跑。前端 Vue 3 CDN 版 + 静态 HTML，无 node 构建链。

**Tech Stack:** Java 21 · Spring Boot 4.1.1 · Spring Framework 7.0.9 · Jackson 3.1.5（`tools.jackson`）· `spring-boot-starter-webmvc` · `spring-boot-starter-jdbc` + `sqlite-jdbc` · `spring-security-crypto`（仅 BCrypt）· PDFBox 3.0.4 · Vue 3 CDN

**参考文档：** `docs/superpowers/specs/2026-09-28-ai-interview-system-design.md`

---

## 环境与命令速查

| 项 | 值 |
|---|---|
| Maven 本地仓库 | `C:\Users\huangjinqing001\.m2\repository`（默认路径，**不是** `D:\repository`） |
| Maven settings | `D:\apache-jmeter-5.4.3\settings.xml` |
| **JAVA_HOME** | `C:\Program Files\Java\jdk-21`（**每条命令都要设，见下**） |
| 构建命令 | `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml clean package` |
| 编译命令 | `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml compile` |
| 启动命令 | `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run` |
| 基础包名 | `com.ke.nhservice.aimianshi` |

> **★ JAVA_HOME 必须显式设，否则编译必挂**
>
> 这台机器 `JAVA_HOME` 是空的，PATH 上的 `java` 解析到
> `C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java` —— 那是 **Java 8 的 JRE**，
> 不带 `javac`。不设 JAVA_HOME 会报：
> `No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?`
>
> 每条 Bash 调用前都要加（**shell 状态不跨调用保留，不能只设一次**）：
> ```bash
> export JAVA_HOME="/c/Program Files/Java/jdk-21" && ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml compile
> ```
> 迷惑点：`dependency:tree` / `dependency:get` 不需要 javac，不设也能过，
> 会让人误以为环境正常——只有 `compile` 才暴露。

> **注意**：`-o`（离线）只在依赖**和构建插件**都已下载后可用。
> 判据不是「业务依赖齐了」，而是「完整跑过一次在线构建」。
> 典型翻车：`dependency:tree` 全绿，但 `compile` 报
> `spring-boot-maven-plugin:4.1.1 ... has not been downloaded from it before`。
> 另有一种离线报错 `present in the local repository, but cached from a remote repository ID
> that is unavailable`，是本地仓库 `_remote.repositories` 元数据绑在私服 ID 上导致的，
> `-Dmaven.legacyLocalRepo=true` 无效，去掉 `-o` 即可。
>
> `compile` 能离线跑通**不代表** `spring-boot:run` 能：后者的 `requiresDependencyResolution`
> 覆盖 runtime/test 作用域，`sqlite-jdbc`（runtime）与 `spring-boot-starter-test`（test）
> 的 jar 在 `compile` 阶段用不到，所以第一次 `spring-boot:run` 必须联网。

> **★ 本地仓库别搞错**
>
> `D:\repository` 确实存在、也确实有 `sqlite-jdbc`，但里面是**旧版本**
> （pdfbox 2.0.x、spring-boot 2.1.x），是历史遗留仓库。settings.xml 里
> `localRepository` 那一行是**注释掉的**，所以生效的是 Maven 默认的
> `C:\Users\huangjinqing001\.m2\repository`（pdfbox 3.0.4、boot 4.1.1 在这里）。
> 判断实际仓库以 `dependency:build-classpath -Dmdep.outputFile=...` 的输出为准，
> 不要用 `find` 在磁盘上找同名 jar 来推断。

> **★ 停应用要按端口杀进程**
>
> `spring-boot:run` 会 fork 一个 `java.exe`，停掉 Maven 包装进程（含 harness 的 TaskStop）
> **不会**带走它，8080 继续被占，下次启动报 `Port 8080 was already in use`。做法：
> ```bash
> netstat -ano | grep LISTENING | grep ":8080"   # 取 PID，对照日志里的 "INFO <pid>" 确认是自己
> tasklist //FI "PID eq <pid>" //FO CSV //NH
> taskkill //F //PID <pid>
> ```

> **★ 验证 HTTP 接口不要用 curl**
>
> 本环境 `curl` 被权限规则拒绝。改用单文件 Java 程序：
> `"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 /path/Xxx.java`，
> 断言在进程内做（如 `body.contains(NICKNAME)`），输出全 ASCII。
> **不要用 `.jsh`**：jshell 文件模式会把跨行语句拆成多条 snippet 并报语法错误，
> 错误行带 `|` 前缀又容易在 `grep -v '^|'` 时被静默丢掉，产生"看起来通过"的假验证。

## 关于测试

**本计划不写任何测试用例**（用户明确要求）。每个任务的验证方式是：编译通过 → 启动应用 → 用 curl / 浏览器手工验证行为。设计文档第十章「测试策略」已在 Task 0 中同步删除，避免文档与实现对不上。

## 关于模型 API Key（重要，影响验证方式）

**执行本计划时 `DEEPSEEK_API_KEY` 尚未配置**，用户后续自己配。因此分成两类步骤：

**必须真跑通的**（不依赖模型，执行时不能跳过）：
- Task 1 依赖解析、Task 2/3/4 引擎与分支的临时 main 验证、Task 7 建表、Task 11/12 登录鉴权
- Task 13 简历上传解析（PDFBox 纯本地，不调模型）
- 所有 `./mvnw compile` / `package`
- Task 29 的分层约束检查、密钥检查、独立 jar 启动

**需要模型、暂时挂起的**（标了 `[需 Key]` 的步骤）：
- Task 15 之后的出题/评分/报告实测：Task 17–23、26–27 的 curl 与浏览器端到端验证

这类步骤执行时**写到「待用户配好 Key 后验证」清单里跳过**，不要为了让它通过而改动代码逻辑，
也不要因为调用失败就以为实现有 bug —— 没有 Key 时 `OpenAiCompatibleClient` 拿到的是 401，
按设计会被归类成 `NonRetryableException` 直接抛出，这是**预期行为**。

配好 Key 后，按 Task 29 Step 7 的三条验收标准一次性补齐验证：
能完成一场 10 题面试、中途关页面能继续、结束后能看到逐题评分与决策链。

---

## 文件结构总览

```
src/main/java/com/ke/nhservice/aimianshi/
├── AiMianshiApplication.java                    [改] main 里建 data 目录
├── graph/                                       图引擎（纯通用，禁止 import biz/controller/wrapper）
│   ├── Node.java           节点接口
│   ├── NodeResult.java     密封接口 Next / Suspend
│   ├── BranchCondition.java 分支条件
│   ├── Branch.java         分支定义（包级私有 record）
│   ├── NodeContext.java    类型注册表：节点按类型取依赖
│   ├── GraphListener.java  轨迹钩子（默认空实现）
│   ├── Execution.java      state + cursor
│   ├── RunStatus.java      4 种结束状态
│   ├── RunResult.java      执行结果
│   ├── GraphException.java 图配置/执行异常
│   ├── Graph.java          图构建器 + compile()
│   └── CompiledGraph.java  主循环
├── common/
│   ├── config/
│   │   ├── AppProperties.java        auth 配置
│   │   ├── InterviewProperties.java  max-questions / max-follow-up / max-steps / topics 话题池
│   │   ├── WebConfig.java            注册拦截器
│   │   └── SqliteInitializer.java    启动时开 WAL
│   ├── constant/
│   │   ├── Difficulty.java   简单/中等/困难 + shift 夹紧
│   │   ├── NextAction.java   DEEPEN/CONTINUE/LOWER/SWITCH + 分数兜底
│   │   └── RecordStatus.java in_progress / finished
│   ├── exception/
│   │   ├── BizException.java
│   │   ├── RetryableException.java
│   │   ├── NonRetryableException.java
│   │   └── GlobalExceptionHandler.java
│   ├── auth/
│   │   ├── UserContext.java      ThreadLocal 当前用户
│   │   ├── TokenUtil.java        HMAC 自签 token
│   │   └── AuthInterceptor.java
│   ├── util/
│   │   └── JsonUtil.java
│   └── dto/
│       ├── ApiResponse.java
│       ├── LoginRequest.java / LoginVO.java / MeVO.java
│       ├── StartInterviewRequest.java / AnswerRequest.java
│       ├── InterviewTurnVO.java
│       ├── RecordListItemVO.java / InterviewDetailVO.java
│       │   └── DialogueVO.java / TraceVO.java
│       └── ResumeVO.java
├── wrapper/                                     第三方封装
│   ├── llm/
│   │   ├── ChatMessage.java
│   │   ├── LlmClient.java              接口
│   │   ├── LlmProperties.java          配置
│   │   ├── OpenAiCompatibleClient.java JDK HttpClient 实现
│   │   ├── ChatCompletionResponse.java 响应 DTO
│   │   ├── RetryableLlmClient.java     重试装饰器 5s/10s/20s/40s
│   │   └── LlmConfig.java              @Bean
│   └── pdf/
│       └── PdfTextExtractor.java
├── biz/
│   ├── interview/
│   │   ├── InterviewState.java      业务状态（序列化进 state_json）
│   │   ├── Dialogue.java
│   │   ├── HistoryItem.java
│   │   ├── ScoreHistory.java
│   │   ├── TopicTracker.java
│   │   ├── EvalResult.java
│   │   ├── EvalResultParser.java
│   │   ├── InterviewDao.java        记录/对话/轨迹 全部 SQL
│   │   ├── InterviewEngine.java     载入 → 跑图 → 落库
│   │   ├── node/
│   │   │   ├── StartNode.java
│   │   │   ├── QuestionNode.java
│   │   │   ├── WaitAnswerNode.java  ★ 唯一挂起点
│   │   │   ├── EvaluateNode.java
│   │   │   ├── SetHintNode.java     deepen/continue/lower/switch 合并为一个类
│   │   │   └── EndNode.java
│   │   ├── flow/
│   │   │   ├── InterviewRouting.java   全图唯一的决策纯函数
│   │   │   ├── EvaluateBranch.java     薄适配器
│   │   │   └── InterviewGraphFactory.java
│   │   ├── prompt/
│   │   │   └── PromptLoader.java
│   │   └── trace/
│   │       └── TraceRecorder.java
│   ├── resume/
│   │   ├── Resume.java / ResumeDao.java / ResumeService.java
│   ├── knowledge/
│   │   ├── Chunk.java / Retriever.java / EmptyRetriever.java
│   └── user/
│       ├── User.java / UserDao.java / UserService.java
└── controller/
    ├── AuthController.java
    ├── ResumeController.java
    └── InterviewController.java

src/main/resources/
├── application.yml          [新] 替代 application.properties
├── schema.sql               [新] 5 张表
├── prompts/                 [新] 8 个提示词文件
│   ├── question_first.md / question_followup.md
│   ├── hint_deepen.md / hint_continue.md / hint_lower.md / hint_switch.md
│   ├── evaluate.md / report.md
└── static/                  [新] 前端
    ├── login.html / index.html / interview.html
    ├── history.html / report.html / resume.html
    ├── css/app.css
    └── js/app.js
```

---

## 相对设计文档的五处刻意调整

实现时发现以下五点按文档字面写会出问题，做等价替换（均已在 Task 0 同步回文档）：

| # | 文档写法 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | 话题池放 `resources/topics.yml` | 合并进 `application.yml` 的 `app.interview.topics` | 读独立 yml 要么加 `jackson-dataformat-yaml` 依赖、要么写 `EnvironmentPostProcessor`；合并后零依赖、同样免编译可改。绑定类型 `Map<String, List<String>>` |
| 2 | 分支判断写在 `EvaluateBranch` 里 | 抽成 `InterviewRouting.decide()` 纯静态函数，`EvaluateBranch` 只做转发 | `EvaluateNode` 落库 `next_action` 时要用同一套判断。若各写一份，DB 里记的分支和实际走的分支可能不一致。抽出来后「一处判断」的语义反而更强 |
| 3 | `RunResult(status, stoppedAt, state)` | 多一个 `Throwable error` 分量 | `RunStatus.FAILED` 时调用方需要看异常才知道发生了什么。用静态工厂 `RunResult.failed(...)` 生成，调用方基本不碰规范构造器 |
| 4 | 「LLM 失败 → 状态未落库」 | 失败时同样 `persist()`，把游标推进到失败节点 | 文档这句话与「面试中失败了可以重新唤起」自相矛盾：不落库，用户就只能从头开一场。落库后修好模型调 `/resume` 即可接着跑。见 Task 0 Step 5 |
| 5 | 前端先 GET state、按分支决定要不要 resume | 进页面无条件调一次 `/resume` | `/resume` 本身幂等（游标在 `wait_answer` 且无答案时立刻挂起），刷新页面 / 断线重连 / 失败重试三种情况合并成一条代码路径。见 Task 0 Step 6 |

另外两处新增（文档未提但必需）：

- `JsonUtil`：`common/util` 下的静态 Jackson 3 封装。DAO / 引擎 / 解析器都要序列化，做成静态工具比到处注入 `ObjectMapper` 省事。
- `InterviewState` 增加 `report` / `totalScore` / `lastRouting` / `position` / `company` / `domain` / `resumeSummary` 字段。前三个是 `EndNode` 和接口返回值需要，后四个是 `StartNode` 从记录表带进来的会话配置，供出题提示词使用。

---

## Task 0: 同步设计文档（删测试章节 + 记录上面五处调整）

**Files:**
- Modify: `docs/superpowers/specs/2026-09-28-ai-interview-system-design.md`

- [ ] **Step 1: 删除第十章「测试策略」并重排后续章节号**

把第十章整节（从 `## 十、测试策略` 到 `## 十一、实施分期` 之前）替换为：

```markdown
## 十、测试策略

**一期不写自动化测试**（使用者明确要求）。验证方式是编译通过 + 启动应用 + 手工走完一场面试。

图引擎本身是纯逻辑、无外部依赖，是最适合补测试的部分。若将来要补，优先级为：图引擎主循环（挂起/恢复/超步数）> 分支条件 > 各节点（Mock `LlmClient`）。
```

然后 `## 十一、实施分期` → `## 十一、实施分期`（不变），`## 十二、明确不做的事` 保持不变。同时把第十一章第 1 项 `图引擎（graph/ 包）+ 单元测试` 改成 `图引擎（graph/ 包）`。

- [ ] **Step 2: 修正话题池位置**

把「话题池从哪来」一节里的

```yaml
# src/main/resources/topics.yml
Java:
  - JVM 内存模型
```

改为 `application.yml` 中的片段：

```yaml
# src/main/resources/application.yml
app:
  interview:
    topics:
      Java:
        - JVM 内存模型
        - 并发编程
        - 集合框架
        - Spring 原理
        - MySQL
        - Redis
      Go:
        - GMP 调度模型
        - 内存管理与 GC
        - channel 与并发
        - 运行时与逃逸分析
```

并在该节末尾补一句：*实现时把话题池合并进了 `application.yml`，避免为读一个独立 yml 引入额外依赖。*

- [ ] **Step 3: 修正分支条件位置**

在 `### 分支条件（全图仅一处）` 一节，把 `EvaluateBranch` 代码块替换为：

```java
// flow/InterviewRouting.java —— 全图唯一的决策纯函数
public final class InterviewRouting {
    public static final String END = "end_loop";

    public static String decide(InterviewState s) {
        if (s.isShouldStop())                            return END;
        if (s.getQuestionIndex() >= s.getMaxQuestions()) return END;
        EvalResult r = s.getEvalResult();
        if (r == null || r.getNextAction() == null)      return "continue";
        return switch (r.getNextAction()) {
            case DEEPEN   -> "deepen";
            case LOWER    -> "lower";
            case SWITCH   -> "switch";
            case CONTINUE -> "continue";
        };
    }
}

// flow/EvaluateBranch.java —— 只是转发，图引擎只认 BranchCondition
public class EvaluateBranch implements BranchCondition<InterviewState> {
    @Override
    public String decide(InterviewState state) { return InterviewRouting.decide(state); }
}
```

并补一句：*抽成独立纯函数是因为 `EvaluateNode` 落库 `next_action` 时要用同一套判断——各写一份会导致 DB 记录与实际分支不一致。*

- [ ] **Step 4: 修正 RunResult 定义**

把第四章的 `public record RunResult<S>(RunStatus status, String stoppedAt, S state) {}` 替换为：

```java
public record RunResult<S>(RunStatus status, String stoppedAt, S state, Throwable error) {
    public static <S> RunResult<S> finished(String at, S state) { ... }
    public static <S> RunResult<S> suspended(String at, S state) { ... }
    public static <S> RunResult<S> failed(String at, S state, Throwable e) { ... }
    public static <S> RunResult<S> stepLimit(String at, S state) { ... }
}
```

- [ ] **Step 5: 修正「失败时状态是否落库」的说法**

第九章的表格里有一行：

```
| LLM 挂 / 请求失败 | 状态未落库，停在上次挂起点 | in_progress，可继续 |
```

「状态未落库」与实现不符：`InterviewEngine.run()` 无论 `RunStatus` 是什么都会调 `persist()`，
失败时同样把 `state_json` 和 `cursor` 写回。改成：

```markdown
| LLM 挂 / 请求失败 | 游标推进到失败节点并落库，状态完好 | `in_progress`，可继续 |
```

这行改动让「面试中失败了可以重新唤起」这条需求在文档里也成立——游标在库里，
修好模型服务后调 `/resume` 就能从失败的那个节点接着跑。

- [ ] **Step 6: 修正前端的恢复路径**

第八章的这段判断逻辑：

```javascript
const st = await getState(recordId);
if (st.status === 'finished')   → 跳复盘页
else if (st.currentQuestion)    → 直接渲染答题界面
else                            → 调 /resume 把面试推起来，再渲染
```

改成：

```javascript
// 进面试页无条件调一次 /resume，它本身是幂等的：
//   游标在 wait_answer 且没答案 → 立刻挂起，原样返回当前题目（刷新页面走这条）
//   游标在 question / evaluate  → 真的往下推一步（上次 LLM 失败后重试走这条）
//   已结束                      → 返回 finished，前端跳复盘页
const turn = await resume(recordId);
if (turn.finished) → 跳复盘页
else               → 渲染答题界面
```

这样「刷新页面」「关掉再回来」「失败后重试」三种情况走同一条代码路径，
不需要先 GET 一次 state 再决定要不要 resume。

- [ ] **Step 7: 提交**

```bash
git add docs/superpowers/specs/2026-09-28-ai-interview-system-design.md
git commit -m "docs: 设计文档与实现对齐（去掉测试章节、修正话题池/分支判断/RunResult/失败落库/恢复路径）"
```

---

## Task 1: pom.xml 补依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 加入 3 个新依赖**

在 `</dependencies>` 之前、`sqlite-jdbc` 之后插入：

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-crypto</artifactId>
    </dependency>

    <dependency>
      <groupId>org.apache.pdfbox</groupId>
      <artifactId>pdfbox</artifactId>
      <version>3.0.4</version>
    </dependency>
```

说明：
- `spring-boot-starter-jdbc` 提供 `JdbcTemplate` + HikariCP。版本由父 BOM 管（4.1.1），不写版本号。
- `spring-security-crypto` 提供 `BCryptPasswordEncoder`，版本由 BOM 管（7.1.1）。只引这一个 jar，不引 Spring Security 全家桶。
- PDFBox 不在 BOM 里，必须显式写 `3.0.4`（本地仓库已有）。

- [ ] **Step 2: 验证依赖能解析**

Run:
```bash
./mvnw -B -s /d/apache-jmeter-5.4.3/settings.xml -o dependency:tree -DoutputFile=/tmp/tree2.txt
grep -E "starter-jdbc|security-crypto|pdfbox|HikariCP" /tmp/tree2.txt
```
Expected: 四行都能命中，`BUILD SUCCESS`。若报「present, but unavailable」，去掉 `-o` 再跑一次联网解析。

- [ ] **Step 3: 提交**

```bash
git add pom.xml
git commit -m "build: 引入 JdbcTemplate、BCrypt、PDFBox 依赖"
```

---

## Task 2: 图引擎 —— 基础类型

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/Node.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/NodeResult.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/BranchCondition.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/Branch.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/NodeContext.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/GraphListener.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/GraphException.java`

- [ ] **Step 1: 写 `Node.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/**
 * 图节点：吃进业务状态，吐出「继续」或「挂起」。
 * 引擎不认识 S 是什么，所以节点可以承载任意业务流程。
 */
@FunctionalInterface
public interface Node<S> {
    NodeResult execute(NodeContext ctx, S state);
}
```

- [ ] **Step 2: 写 `NodeResult.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/**
 * 节点返回给引擎的指令，只有两种。
 * 用 sealed 限制住，将来加第三种时编译器会逼着所有 switch 补分支。
 */
public sealed interface NodeResult {

    /** 继续沿出边往下走 */
    record Next() implements NodeResult {}

    /** 挂起：保存现场后退出，不占用线程 */
    record Suspend() implements NodeResult {}

    NodeResult NEXT = new Next();
    NodeResult SUSPEND = new Suspend();
}
```

- [ ] **Step 3: 写 `BranchCondition.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/** 条件分支：给定状态，返回下一个节点名 */
@FunctionalInterface
public interface BranchCondition<S> {
    String decide(S state);
}
```

- [ ] **Step 4: 写 `Branch.java`**

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.Set;

/**
 * 分支定义：从一个节点出发，按条件路由到一组候选目标。
 * 包级私有——只给 Graph / CompiledGraph 用，不对外暴露。
 */
record Branch<S>(BranchCondition<S> condition, Set<String> targets) {
}
```

- [ ] **Step 5: 写 `NodeContext.java`**

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.HashMap;
import java.util.Map;

/**
 * 执行期上下文：携带节点需要的服务依赖（LLM 客户端、DAO、提示词加载器…）。
 *
 * 引擎不允许认识任何业务类型，所以这里不写具体字段，改用「类型 -> 实例」注册表，
 * 节点自己按类型取。这样 graph 包可以完全脱离 biz / wrapper 编译。
 */
public class NodeContext {

    private final Map<Class<?>, Object> components = new HashMap<>();

    public <T> NodeContext put(Class<T> type, T instance) {
        components.put(type, instance);
        return this;
    }

    /** type.cast 而不是强制转型，避免 @SuppressWarnings 满天飞 */
    public <T> T get(Class<T> type) {
        Object value = components.get(type);
        if (value == null) {
            throw new GraphException("NodeContext 中未注册组件: " + type.getName());
        }
        return type.cast(value);
    }
}
```

- [ ] **Step 6: 写 `GraphListener.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/**
 * 执行轨迹钩子。全部 default 空实现，业务层只覆写关心的那几个。
 * 用接口反转依赖：引擎不需要知道「记录轨迹」这件事存在。
 */
public interface GraphListener<S> {

    default void onNodeEnter(String node, S state) {}

    /** result 为 null 表示节点抛了异常 */
    default void onNodeExit(String node, NodeResult result, long costMs, S state) {}

    default void onBranchDecided(String from, String decided, S state) {}

    default void onSuspend(String node, S state) {}
}
```

- [ ] **Step 7: 写 `GraphException.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/** 图配置错误（编译期校验）或执行期错误（游标指向不存在的节点等） */
public class GraphException extends RuntimeException {

    public GraphException(String message) {
        super(message);
    }

    public GraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 8: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出（`-q` 下成功即静默）。

- [ ] **Step 9: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/graph/
git commit -m "feat(graph): 图引擎基础类型（Node/NodeResult/Branch/Context/Listener）"
```

---

## Task 3: 图引擎 —— 主循环

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/Execution.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/RunStatus.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/RunResult.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/CompiledGraph.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/graph/Graph.java`

- [ ] **Step 1: 写 `Execution.java`**

```java
package com.ke.nhservice.aimianshi.graph;

/**
 * 执行现场：业务状态 + 引擎游标。
 * 两个字段分开存是因为它们的生命周期不同——state 归业务，cursor 归引擎。
 */
public class Execution<S> {

    private S state;
    private String cursor;

    public Execution() {
    }

    public Execution(S state, String cursor) {
        this.state = state;
        this.cursor = cursor;
    }

    public S getState() { return state; }

    public void setState(S state) { this.state = state; }

    public String getCursor() { return cursor; }

    public void setCursor(String cursor) { this.cursor = cursor; }
}
```

- [ ] **Step 2: 写 `RunStatus.java`**

```java
package com.ke.nhservice.aimianshi.graph;

public enum RunStatus {

    /** 跑到结束节点，正常收尾 */
    FINISHED,

    /** 有节点挂了 Suspend，现场已保存在 Execution 里，调用方负责落库 */
    SUSPENDED,

    /** 节点抛异常。游标停在出错的那个节点上，修好后可以原地重跑 */
    FAILED,

    /** 步数跑满仍未到终点，疑似死循环。不抛异常，交给调用方判断 */
    STEP_LIMIT
}
```

- [ ] **Step 3: 写 `RunResult.java`**

```java
package com.ke.nhservice.aimianshi.graph;

public record RunResult<S>(RunStatus status, String stoppedAt, S state, Throwable error) {

    public static <S> RunResult<S> finished(String at, S state) {
        return new RunResult<>(RunStatus.FINISHED, at, state, null);
    }

    public static <S> RunResult<S> suspended(String at, S state) {
        return new RunResult<>(RunStatus.SUSPENDED, at, state, null);
    }

    public static <S> RunResult<S> failed(String at, S state, Throwable error) {
        return new RunResult<>(RunStatus.FAILED, at, state, error);
    }

    public static <S> RunResult<S> stepLimit(String at, S state) {
        return new RunResult<>(RunStatus.STEP_LIMIT, at, state, null);
    }

    public boolean isFinished() {
        return status == RunStatus.FINISHED;
    }

    public boolean isSuspended() {
        return status == RunStatus.SUSPENDED;
    }
}
```

- [ ] **Step 4: 写 `CompiledGraph.java`**

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.List;
import java.util.Map;

/**
 * 编译后的图。不可变，可以安全地被多线程共享。
 * 注意：节点实现本身也必须是无状态的。
 */
public class CompiledGraph<S> {

    private final Map<String, Node<S>> nodes;
    private final Map<String, String> edges;
    private final Map<String, Branch<S>> branches;
    private final List<GraphListener<S>> listeners;
    private final NodeContext context;
    private final String start;
    private final String end;
    private final int maxSteps;

    CompiledGraph(Map<String, Node<S>> nodes,
                  Map<String, String> edges,
                  Map<String, Branch<S>> branches,
                  List<GraphListener<S>> listeners,
                  NodeContext context,
                  String start,
                  String end,
                  int maxSteps) {
        this.nodes = Map.copyOf(nodes);
        this.edges = Map.copyOf(edges);
        this.branches = Map.copyOf(branches);
        this.listeners = List.copyOf(listeners);
        this.context = context;
        this.start = start;
        this.end = end;
        this.maxSteps = maxSteps;
    }

    public String getStart() { return start; }

    public String getEnd() { return end; }

    /**
     * 跑图。从 execution.cursor 开始（为 null 则从起始节点），
     * 遇到 Suspend / 终点 / 异常 / 超步数就返回，并把最新游标写回 execution。
     */
    public RunResult<S> run(Execution<S> execution) {
        S state = execution.getState();
        String cursor = execution.getCursor() != null ? execution.getCursor() : start;

        for (int step = 0; step < maxSteps; step++) {
            Node<S> node = nodes.get(cursor);
            if (node == null) {
                return RunResult.failed(cursor, state,
                        new GraphException("游标指向不存在的节点: " + cursor));
            }
            boolean terminal = cursor.equals(end);

            for (GraphListener<S> l : listeners) {
                l.onNodeEnter(cursor, state);
            }

            long startedAt = System.currentTimeMillis();
            NodeResult result;
            try {
                result = node.execute(context, state);
            } catch (Exception e) {
                long cost = System.currentTimeMillis() - startedAt;
                for (GraphListener<S> l : listeners) {
                    l.onNodeExit(cursor, null, cost, state);
                }
                execution.setCursor(cursor);
                return RunResult.failed(cursor, state, e);
            }
            long cost = System.currentTimeMillis() - startedAt;

            for (GraphListener<S> l : listeners) {
                l.onNodeExit(cursor, result, cost, state);
            }

            if (result instanceof NodeResult.Suspend) {
                for (GraphListener<S> l : listeners) {
                    l.onSuspend(cursor, state);
                }
                execution.setCursor(cursor);
                return RunResult.suspended(cursor, state);
            }

            // 终点节点也要执行（它负责生成报告），执行完才算结束
            if (terminal) {
                execution.setCursor(cursor);
                return RunResult.finished(cursor, state);
            }

            String next = resolveNext(cursor, state);
            if (branches.containsKey(cursor)) {
                for (GraphListener<S> l : listeners) {
                    l.onBranchDecided(cursor, next, state);
                }
            }
            cursor = next;
            execution.setCursor(cursor);
        }

        return RunResult.stepLimit(cursor, state);
    }

    private String resolveNext(String from, S state) {
        Branch<S> branch = branches.get(from);
        if (branch != null) {
            String decided = branch.condition().decide(state);
            if (!branch.targets().contains(decided)) {
                throw new GraphException(
                        "分支 " + from + " 返回了未声明的目标: " + decided
                                + "，已声明的目标为 " + branch.targets());
            }
            return decided;
        }
        String to = edges.get(from);
        if (to == null) {
            throw new GraphException("节点 " + from + " 没有出边也没有分支");
        }
        return to;
    }
}
```

- [ ] **Step 5: 写 `Graph.java`**

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图构建器。链式调用，compile() 时做一次完整校验。
 */
public class Graph<S> {

    private final Map<String, Node<S>> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new LinkedHashMap<>();
    private final Map<String, Branch<S>> branches = new LinkedHashMap<>();
    private final List<GraphListener<S>> listeners = new ArrayList<>();
    private NodeContext context = new NodeContext();
    private String start;
    private String end;

    public Graph<S> addNode(String name, Node<S> node) {
        if (nodes.putIfAbsent(name, node) != null) {
            throw new GraphException("节点名重复: " + name);
        }
        return this;
    }

    /** 无条件边 */
    public Graph<S> addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    /** 条件分支：从 from 出发，可路由到 targets 中的任意一个 */
    public Graph<S> addBranch(String from, BranchCondition<S> condition, Set<String> targets) {
        branches.put(from, new Branch<>(condition, Set.copyOf(targets)));
        return this;
    }

    public Graph<S> startAt(String name) {
        this.start = name;
        return this;
    }

    /** 结束节点：执行完它之后图返回 FINISHED */
    public Graph<S> endAt(String name) {
        this.end = name;
        return this;
    }

    public Graph<S> context(NodeContext context) {
        this.context = context;
        return this;
    }

    public Graph<S> listener(GraphListener<S> listener) {
        this.listeners.add(listener);
        return this;
    }

    /**
     * @param maxSteps 单次 run 的最大步数，防死循环
     */
    public CompiledGraph<S> compile(int maxSteps) {
        if (start == null) {
            throw new GraphException("未指定起始节点，请调用 startAt()");
        }
        if (end == null) {
            throw new GraphException("未指定结束节点，请调用 endAt()");
        }
        if (!nodes.containsKey(start)) {
            throw new GraphException("起始节点不存在: " + start);
        }
        if (!nodes.containsKey(end)) {
            throw new GraphException("结束节点不存在: " + end);
        }

        for (String name : nodes.keySet()) {
            boolean hasEdge = edges.containsKey(name);
            boolean hasBranch = branches.containsKey(name);
            if (hasEdge && hasBranch) {
                throw new GraphException("节点 " + name + " 同时定义了边和分支，只能二选一");
            }
            if (name.equals(end)) {
                continue;   // 终点，允许没有出边
            }
            if (!hasEdge && !hasBranch) {
                throw new GraphException("节点 " + name + " 既没有出边也没有分支，会成为死胡同");
            }
        }

        edges.forEach((from, to) -> {
            if (!nodes.containsKey(to)) {
                throw new GraphException("边 " + from + " -> " + to + " 的目标节点不存在");
            }
        });

        branches.forEach((from, branch) -> branch.targets().forEach(target -> {
            if (!nodes.containsKey(target)) {
                throw new GraphException("分支 " + from + " 的目标节点不存在: " + target);
            }
        }));

        return new CompiledGraph<>(nodes, edges, branches, listeners, context, start, end, maxSteps);
    }
}
```

- [ ] **Step 6: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

- [ ] **Step 7: 用临时 main 验证挂起与恢复**

在 `src/main/java/com/ke/nhservice/aimianshi/graph/GraphSmokeTest.java` 临时创建：

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.List;
import java.util.Set;

/** 临时手工验证用，验证完即删 */
public class GraphSmokeTest {

    record State(String text, int steps) {}

    public static void main(String[] args) {
        Graph<State> g = new Graph<>();
        g.addNode("a", (ctx, s) -> {
            System.out.println("  [a] steps=" + s.steps());
            return NodeResult.NEXT;
        });
        g.addNode("wait", (ctx, s) ->
                s.text().contains("答案") ? NodeResult.NEXT : NodeResult.SUSPEND);
        g.addNode("b", (ctx, s) -> {
            System.out.println("  [b] 收到答案: " + s.text());
            return NodeResult.NEXT;
        });
        g.startAt("a");
        g.addEdge("a", "wait");
        g.addEdge("wait", "b");
        g.endAt("b");

        CompiledGraph<State> graph = g.compile(20);

        Execution<State> first = new Execution<>(new State("", 0), null);
        System.out.println("第一次 run: " + graph.run(first).status() + " cursor=" + first.getCursor());

        first.setState(new State("这是我的答案", 0));
        System.out.println("第二次 run: " + graph.run(first).status() + " cursor=" + first.getCursor());

        // 超步数保护：自环 + 一个独立的终点，保证永远到不了终点。
        // ★ 别写成 startAt("x") + endAt("x")：那样首次进入 x 就是 terminal，
        //   引擎执行完直接 FINISHED，根本进不了循环，等于没验证到 STEP_LIMIT。
        Graph<State> loop = new Graph<>();
        loop.addNode("x", (ctx, s) -> NodeResult.NEXT);
        loop.addNode("fin", (ctx, s) -> NodeResult.NEXT);
        loop.startAt("x");
        loop.addEdge("x", "x");
        loop.endAt("fin");
        Execution<State> e = new Execution<>(new State("", 0), null);
        System.out.println("死循环 run: " + loop.compile(5).run(e).status());
    }
}
```

Run（**JAVA_HOME 必须设，`java` 必须用 JDK 的**：PATH 上那个是 Java 8，
跑 Java 21 的 class 会 `UnsupportedClassVersionError`；`-Dstdout.encoding=UTF-8` 是为了中文不乱码）：
```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -q compile
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp target/classes \
    com.ke.nhservice.aimianshi.graph.GraphSmokeTest
```
Expected 输出（**注意顺序**：`[a]` 是第一次 run 打印的，必然在「第一次 run」那行之前）：
```
  [a] steps=0
第一次 run: SUSPENDED cursor=wait
  [b] 收到答案: 这是我的答案
第二次 run: FINISHED cursor=b
死循环 run: STEP_LIMIT
```

关键在于两点：
1. 第一次是 `SUSPENDED cursor=wait`、第二次是 `FINISHED`，且第二次**没有重跑 `[a]`**
   —— 这就是「关掉页面再回来能继续」的底层机制：现场就是 `cursor` 一个字符串。
2. 第四行必须是 `STEP_LIMIT`。若跑出 `FINISHED`，八成是把环的终点写成了 `x` 自己
   （见上面代码里的 ★ 注释），而不是引擎有问题。

- [ ] **Step 8: 删掉临时验证类并提交**

```bash
rm src/main/java/com/ke/nhservice/aimianshi/graph/GraphSmokeTest.java
git add src/main/java/com/ke/nhservice/aimianshi/graph/
git commit -m "feat(graph): 图引擎主循环、编译期校验、挂起恢复与超步数保护"
```

---

## Task 4: 图引擎 —— 端到端小图验证（分支路由）

**Files:**
- Modify: 无（纯验证任务，验证完不提交任何东西）

- [ ] **Step 1: 临时验证分支路由**

创建 `src/main/java/com/ke/nhservice/aimianshi/graph/BranchSmokeTest.java`：

```java
package com.ke.nhservice.aimianshi.graph;

import java.util.Set;

/** 临时手工验证用，验证完即删 */
public class BranchSmokeTest {

    record State(int score) {}

    public static void main(String[] args) {
        Graph<State> g = new Graph<>();
        g.addNode("eval", (ctx, s) -> NodeResult.NEXT);
        g.addNode("deepen", (ctx, s) -> {
            System.out.println("  -> deepen");
            return NodeResult.NEXT;
        });
        g.addNode("continue", (ctx, s) -> {
            System.out.println("  -> continue");
            return NodeResult.NEXT;
        });
        g.addNode("stop", (ctx, s) -> {
            System.out.println("  -> stop");
            return NodeResult.NEXT;
        });

        g.startAt("eval");
        g.addBranch("eval", s -> s.score() >= 8 ? "deepen" : s.score() < 4 ? "missing" : "continue",
                Set.of("deepen", "continue", "stop"));
        g.addEdge("deepen", "stop");
        g.addEdge("continue", "stop");
        g.endAt("stop");

        CompiledGraph<State> graph = g.compile(20);

        System.out.println("score=9:");
        System.out.println("  status=" + graph.run(new Execution<>(new State(9), null)).status());

        System.out.println("score=5:");
        System.out.println("  status=" + graph.run(new Execution<>(new State(5), null)).status());

        System.out.println("score=1（分支路由到未声明的 missing）:");
        // 注意：这里必须用 try/catch。resolveNext 在 try 块外面，
        // 分支返回未声明目标时 GraphException 会直接抛出 run()，
        // 不会变成 RunResult.failed(...)
        try {
            RunResult<State> r = graph.run(new Execution<>(new State(1), null));
            System.out.println("  返回了 RunResult: status=" + r.status()
                    + " error=" + (r.error() == null ? "null" : r.error().getMessage()));
        } catch (GraphException ex) {
            System.out.println("  抛出了 GraphException: " + ex.getMessage());
        }
    }
}
```

- [ ] **Step 2: 运行并核对**

Run:
```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
java -cp target/classes com.ke.nhservice.aimianshi.graph.BranchSmokeTest
```
Expected（已实测核对）：
```
score=9:
  -> deepen
  -> stop
  status=FINISHED
score=5:
  -> continue
  -> stop
  status=FINISHED
score=1（分支路由到未声明的 missing）:
  抛出了 GraphException: 分支 eval 返回了未声明的目标: missing，已声明的目标为 [stop, deepen, continue]
```

> **最后一条为什么是抛异常而不是 `RunStatus.FAILED`**
>
> 设计文档的错误处理表只规定了「节点抛异常 → FAILED」和「超 maxSteps → STEP_LIMIT」，
> 没规定「分支返回未声明的目标」。这是实现定的，选择**快速失败**：
>
> `resolveNext()` 在 `run()` 的 try 块**外面**，所以 `GraphException` 直接往上抛。
> 理由是这样属于**代码 bug**——分支条件和 `addBranch` 声明的目标集不同步，
> `compile()` 校验不了（它无法预知条件会返回什么）。若返回 `FAILED`，
> 接口层会告诉用户「进度已保存，可稍后继续」，但重试多少次都是同一个 bug，
> 等于误导。抛出去变成 500 + 明确的错误信息，更好排查。
>
> 顺带：`[stop, deepen, continue]` 的顺序**不要断言**。`Set.copyOf` 的迭代顺序未指定，
> 换个 JDK 版本可能就变了。要断言就只断言前缀 `分支 eval 返回了未声明的目标: missing`。

- [ ] **Step 3: 删除临时类**

```bash
rm src/main/java/com/ke/nhservice/aimianshi/graph/BranchSmokeTest.java
```

不提交（纯验证）。

---

## Task 5: common —— 常量枚举

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/constant/Difficulty.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/constant/NextAction.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/constant/RecordStatus.java`

- [ ] **Step 1: 写 `Difficulty.java`**

```java
package com.ke.nhservice.aimianshi.common.constant;

/**
 * 难度三档。档位是有序的，shift() 靠 ordinal 升降并夹紧边界。
 */
public enum Difficulty {

    EASY("简单"),
    MEDIUM("中等"),
    HARD("困难");

    private final String label;

    Difficulty(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 从中文标签解析，认不出来就当「中等」 */
    public static Difficulty fromLabel(String label) {
        if (label == null || label.isBlank()) {
            return MEDIUM;
        }
        String v = label.trim();
        for (Difficulty d : values()) {
            if (d.label.equals(v)) {
                return d;
            }
        }
        try {
            return valueOf(v.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEDIUM;
        }
    }

    /**
     * 升降档。困难再升仍是困难，简单再降仍是简单——不会越界。
     *
     * @param delta +1 升档 / -1 降档 / 0 不变
     */
    public static Difficulty shift(Difficulty current, int delta) {
        Difficulty cur = current == null ? MEDIUM : current;
        int index = cur.ordinal() + delta;
        return values()[Math.max(0, Math.min(values().length - 1, index))];
    }
}
```

- [ ] **Step 2: 写 `NextAction.java`**

```java
package com.ke.nhservice.aimianshi.common.constant;

/**
 * 图在评分之后要走的下一步。由 LLM 判断，判断不出来时按分数兜底。
 */
public enum NextAction {

    /** 答得好，往深里追 */
    DEEPEN,

    /** 中等，同话题换个角度 */
    CONTINUE,

    /** 答得差，降难度 */
    LOWER,

    /** 话题聊透了，换新话题 */
    SWITCH;

    /**
     * 解析 LLM 返回的 nextAction。
     * LLM 没给、给了错拼、给了别的词，一律按分数兜底，不抛异常——
     * 评分字段解析失败不该让整场面试挂掉。
     */
    public static NextAction from(String raw, double score) {
        if (raw != null && !raw.isBlank()) {
            String v = raw.trim().toUpperCase();
            for (NextAction a : values()) {
                if (a.name().equals(v)) {
                    return a;
                }
            }
        }
        return infer(score);
    }

    /** 分数兜底规则（设计文档 5.3） */
    public static NextAction infer(double score) {
        if (score >= 8.0) {
            return DEEPEN;
        }
        if (score < 4.0) {
            return LOWER;
        }
        return CONTINUE;
    }

    /** 落库用的小写形式：deepen / continue / lower / switch */
    public String code() {
        return name().toLowerCase();
    }
}
```

- [ ] **Step 3: 写 `RecordStatus.java`**

```java
package com.ke.nhservice.aimianshi.common.constant;

public enum RecordStatus {

    /** 面试进行中。关页面、服务重启、LLM 挂了，都是这个状态，随时可继续 */
    IN_PROGRESS("in_progress"),

    /** 已结束，报告已生成 */
    FINISHED("finished");

    private final String code;

    RecordStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RecordStatus fromCode(String code) {
        for (RecordStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return IN_PROGRESS;
    }

    /** 落库用：t_interview_dialogue.next_action 里 "end" 不算 NextAction，单独定义 */
    public static final String NEXT_ACTION_END = "end";
}
```

- [ ] **Step 4: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/common/constant/
git commit -m "feat(common): 难度/下一步动作/记录状态 三个常量枚举"
```

---

## Task 6: common —— 异常、统一响应、JSON 工具

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/exception/BizException.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/exception/RetryableException.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/exception/NonRetryableException.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/util/JsonUtil.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ApiResponse.java`

- [ ] **Step 1: 写 `BizException.java`**

```java
package com.ke.nhservice.aimianshi.common.exception;

/** 业务异常：参数不对、没权限、状态不允许等，属于「预期内的失败」 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message);
    }
}
```

- [ ] **Step 2: 写 `RetryableException.java` 和 `NonRetryableException.java`**

`RetryableException.java`:

```java
package com.ke.nhservice.aimianshi.common.exception;

/**
 * 可以重试的错误：超时、连接失败、5xx、429。
 * RetryableLlmClient 会捕获它并按 5s/10s/20s/40s 退避重试。
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`NonRetryableException.java`:

```java
package com.ke.nhservice.aimianshi.common.exception;

/**
 * 重试没有意义的错误：4xx（参数错、鉴权错）。
 * 直接往上抛，不进重试循环——省掉 75 秒的无谓等待。
 */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 写 `JsonUtil.java`**

```java
package com.ke.nhservice.aimianshi.common.util;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 的静态封装。
 * 用静态工具而不是到处注入 ObjectMapper，是因为 DAO / 引擎 / 解析器都要用，
 * 注入会把构造器参数搞得很长。
 *
 * 注意：这里是 Jackson 3（tools.jackson 包），不是 Jackson 2（com.fasterxml.jackson）。
 * 唯一的例外是注解，Jackson 3 仍复用 com.fasterxml.jackson.annotation 包。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private JsonUtil() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + value.getClass().getName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败，目标类型 " + type.getName()
                    + "，原文: " + abbreviate(json), e);
        }
    }

    /** 日志里打超长 JSON 会刷屏 */
    public static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(" + text.length() + " 字符)";
    }
}
```

- [ ] **Step 4: 写 `ApiResponse.java`**

```java
package com.ke.nhservice.aimianshi.common.dto;

/**
 * 统一响应体。code == 0 表示成功，非 0 表示业务失败。
 * 业务失败用 HTTP 200 + code 传递，只有 401 会同时把 HTTP 状态设成 401，
 * 方便前端统一拦截跳登录页。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "ok", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

- [ ] **Step 5: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/common/
git commit -m "feat(common): 业务异常体系、统一响应体、Jackson 3 静态封装"
```

---

## Task 7: 配置 —— application.yml、schema.sql、数据源

**Files:**
- Delete: `src/main/resources/application.properties`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/schema.sql`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/config/InterviewProperties.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/config/AppProperties.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/config/SqliteInitializer.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/AiMianshiApplication.java`
- Modify: `.gitignore`

- [ ] **Step 1: 删除 application.properties，写 application.yml**

```bash
rm src/main/resources/application.properties
```

创建 `src/main/resources/application.yml`：

```yaml
server:
  port: 8080

spring:
  application:
    name: ai-mianshi
  datasource:
    url: jdbc:sqlite:./data/interview.db
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 5
      # busy_timeout 是「每连接」生效的，所以放在 connection-init-sql 里
      connection-init-sql: PRAGMA busy_timeout=5000
  sql:
    init:
      # 配合 schema.sql 里的 CREATE TABLE IF NOT EXISTS，每次启动都执行且幂等
      mode: always
      schema-locations: classpath:schema.sql
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 12MB

app:
  auth:
    # 部署时务必换掉，改成足够长的随机串
    secret: change-me-before-deploy-please
    ttl-hours: 72
  llm:
    # DeepSeek 的 OpenAI 兼容端点。换通义/豆包/Kimi 只改这三行
    base-url: https://api.deepseek.com
    api-key: ${DEEPSEEK_API_KEY:}
    model: deepseek-chat
    temperature: 0.7
    connect-timeout-seconds: 10
    read-timeout-seconds: 120
  interview:
    max-questions: 10
    # 同一话题连续追问到这个次数就强制换话题
    max-follow-up: 3
    max-steps: 200
    # 话题池：switch 节点从这里挑没聊过的话题
    topics:
      Java:
        - JVM 内存模型
        - 并发编程
        - 集合框架
        - Spring 原理
        - MySQL
        - Redis
      Go:
        - GMP 调度模型
        - 内存管理与 GC
        - channel 与并发
        - 运行时与逃逸分析
      Python:
        - GIL 与并发模型
        - 装饰器与元编程
        - 异步 asyncio
        - 内存管理与垃圾回收
    默认:
      - 基础知识
      - 项目经验
      - 系统设计
      - 排查与优化

logging:
  level:
    com.ke.nhservice.aimianshi: DEBUG
```

- [ ] **Step 2: 写 `schema.sql`**

```sql
-- AI 面试系统表结构。全部 IF NOT EXISTS，配合 spring.sql.init.mode=always 幂等执行。
-- 约定：时间存 epoch 毫秒（INTEGER），布尔存 0/1（SQLite 无布尔类型）。

CREATE TABLE IF NOT EXISTS t_user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,          -- BCrypt
    nickname      TEXT,
    created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS t_resume (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    filename   TEXT    NOT NULL,
    content    TEXT    NOT NULL,             -- PDF 解析出的纯文本
    is_default INTEGER NOT NULL DEFAULT 0,   -- 0/1
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS t_interview_record (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    resume_id   INTEGER,
    position    TEXT,                        -- 岗位，如「Java 后端」
    company     TEXT,                        -- 目标公司
    domain      TEXT,                        -- 技术方向，如「Java」
    difficulty  TEXT,                        -- 简单 / 中等 / 困难
    status      TEXT    NOT NULL,            -- in_progress | finished
    total_score REAL,
    report      TEXT,                        -- end 节点生成的综合报告

    -- ★ 图引擎的两个关键字段
    state_json  TEXT,                        -- InterviewState 序列化快照
    cursor      TEXT,                        -- 引擎游标：下次从哪个节点继续

    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS t_interview_dialogue (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id   INTEGER NOT NULL,
    seq         INTEGER NOT NULL,            -- 第几题
    topic       TEXT,
    difficulty  TEXT,                        -- 本题难度
    question    TEXT    NOT NULL,
    answer      TEXT,
    score       REAL,
    eval_json   TEXT,                        -- 五维明细 + 评语

    -- ★ 图的状态流转
    next_action TEXT,                        -- deepen|continue|lower|switch|end
    next_topic  TEXT,                        -- 走 switch 时换到的话题

    created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS t_graph_trace (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id  INTEGER NOT NULL,
    seq        INTEGER NOT NULL,             -- 全局递增，还原执行顺序
    round      INTEGER,                      -- 第几题
    node_name  TEXT    NOT NULL,
    node_type  TEXT,                         -- normal | suspend | branch
    from_node  TEXT,
    to_node    TEXT,                         -- 分支决策结果
    cost_ms    INTEGER,
    status     TEXT,                         -- ok | suspend | error
    error_msg  TEXT,
    created_at INTEGER NOT NULL
);

-- (record_id, seq) 唯一：evaluate 节点失败重跑时用 INSERT OR REPLACE 覆盖，
-- 避免同题写出两条对话记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_dialogue_record_seq ON t_interview_dialogue(record_id, seq);
CREATE INDEX IF NOT EXISTS idx_trace_record  ON t_graph_trace(record_id, seq);
CREATE INDEX IF NOT EXISTS idx_record_user   ON t_interview_record(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_user   ON t_resume(user_id, created_at DESC);
```

> **与文档的差异**：文档写的是 `idx_dialogue_record`（普通索引），这里改成 `UNIQUE`。因为 `evaluate` 节点可能因 LLM 失败被重跑，唯一索引配合 `INSERT OR REPLACE` 让写对话变成幂等操作。

- [ ] **Step 3: 写 `InterviewProperties.java`**

```java
package com.ke.nhservice.aimianshi.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewProperties {

    private int maxQuestions = 10;
    private int maxFollowUp = 3;
    private int maxSteps = 200;

    /** 话题池：技术方向 -> 话题列表。取自 application.yml 的 app.interview.topics */
    private Map<String, List<String>> topics = new LinkedHashMap<>();

    /** 取某个方向的话题池，取不到就退到「默认」 */
    public List<String> topicsOf(String domain) {
        if (domain != null) {
            List<String> hit = topics.get(domain.trim());
            if (hit != null && !hit.isEmpty()) {
                return hit;
            }
        }
        return topics.getOrDefault("默认", List.of("基础知识", "项目经验", "系统设计"));
    }

    public int getMaxQuestions() { return maxQuestions; }

    public void setMaxQuestions(int maxQuestions) { this.maxQuestions = maxQuestions; }

    public int getMaxFollowUp() { return maxFollowUp; }

    public void setMaxFollowUp(int maxFollowUp) { this.maxFollowUp = maxFollowUp; }

    public int getMaxSteps() { return maxSteps; }

    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }

    public Map<String, List<String>> getTopics() { return topics; }

    public void setTopics(Map<String, List<String>> topics) { this.topics = topics; }
}
```

- [ ] **Step 4: 写 `AppProperties.java`**

```java
package com.ke.nhservice.aimianshi.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.auth")
public class AppProperties {

    /** HMAC 签名密钥。服务重启后 token 仍有效，靠的就是它不变 */
    private String secret = "change-me-before-deploy-please";

    private int ttlHours = 72;

    public String getSecret() { return secret; }

    public void setSecret(String secret) { this.secret = secret; }

    public int getTtlHours() { return ttlHours; }

    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public long getTtlMillis() {
        return ttlHours * 3600_000L;
    }
}
```

- [ ] **Step 5: 写 `SqliteInitializer.java`**

```java
package com.ke.nhservice.aimianshi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 的 journal_mode 是写进数据库文件头的，设置一次永久生效，
 * 所以放在启动时执行一次就够（busy_timeout 则是每连接生效的，在 Hikari 的
 * connection-init-sql 里配）。
 *
 * 不开 WAL 的话，写操作会锁整库，读也被挡住。
 */
@Component
public class SqliteInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SqliteInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SqliteInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mode = jdbcTemplate.queryForObject("PRAGMA journal_mode=WAL", String.class);
        log.info("SQLite journal_mode = {}", mode);
        if (!"wal".equalsIgnoreCase(mode)) {
            log.warn("SQLite 未进入 WAL 模式，并发写入可能报 database is locked");
        }
    }
}
```

- [ ] **Step 6: 改 `AiMianshiApplication.java`**

```java
package com.ke.nhservice.aimianshi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;

@SpringBootApplication
public class AiMianshiApplication {

    public static void main(String[] args) {
        // SQLite 不会自动创建父目录，Datasource 初始化前得先把它建出来
        new File("data").mkdirs();
        SpringApplication.run(AiMianshiApplication.class, args);
    }
}
```

- [ ] **Step 7: `.gitignore` 加入 data 目录**

在 `.gitignore` 末尾追加：

```
### 本地数据 ###
data/
*.db
```

- [ ] **Step 8: 启动验证**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run`

Expected 日志：
```
SQLite journal_mode = wal
Tomcat started on port 8080
Started AiMianshiApplication in x.xxx seconds
```

然后在另一个终端：
```bash
ls -la data/
```
Expected: 看到 `interview.db`。

再用 sqlite 命令行（若有）或直接看文件大小确认非空：
```bash
ls -l data/interview.db
```
Expected: 文件大小 > 0。

按 `Ctrl+C` 停掉。

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "feat(config): SQLite 数据源（WAL + busy_timeout）、5 张表、面试与鉴权配置"
```

---

## Task 8: wrapper/llm —— OpenAI 兼容客户端

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/ChatMessage.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/ChatCompletionResponse.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmClient.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmProperties.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/OpenAiCompatibleClient.java`

- [ ] **Step 1: 写 `ChatMessage.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
```

- [ ] **Step 2: 写 `ChatCompletionResponse.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 兼容的 /chat/completions 响应。
 * 只声明用得到的字段，其余靠 @JsonIgnoreProperties 忽略——
 * 各家厂商都会塞一堆自己的扩展字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    /** 取第一个 choice 的正文；结构不对就抛 NonRetryableException */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            throw new com.ke.nhservice.aimianshi.common.exception.NonRetryableException(
                    "LLM 响应结构异常：没有 choices[0].message", null);
        }
        return choices.get(0).message().content();
    }
}
```

- [ ] **Step 3: 写 `LlmClient.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import java.util.List;

/**
 * 大模型客户端。业务代码只依赖这个接口，
 * 换厂商 = 换实现类，不动业务代码。
 */
public interface LlmClient {

    String chat(List<ChatMessage> messages);

    /** 单轮对话的便捷方法：把 prompt 当作一条 user 消息 */
    default String chat(String prompt) {
        return chat(List.of(ChatMessage.user(prompt)));
    }
}
```

- [ ] **Step 4: 写 `LlmProperties.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private double temperature = 0.7;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 120;

    public String getBaseUrl() { return baseUrl; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }

    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }

    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    /** 去掉末尾斜杠，拼 /chat/completions 时不会出现双斜杠 */
    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }
}
```

- [ ] **Step 5: 写 `OpenAiCompatibleClient.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import com.ke.nhservice.aimianshi.common.exception.NonRetryableException;
import com.ke.nhservice.aimianshi.common.exception.RetryableException;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 走 OpenAI 兼容协议的客户端，用 JDK 自带的 HttpClient 实现。
 *
 * 为什么不引第三方 SDK 或 Spring 的 RestClient：
 * 请求就一个 POST + 一个 Bearer 头，JDK HttpClient 零依赖、API 十年不变，
 * 而且 wrapper 包不依赖 Spring 也方便日后单独复用。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final LlmProperties props;
    private final HttpClient httpClient;

    public OpenAiCompatibleClient(LlmProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new NonRetryableException(
                    "未配置 LLM api-key，请设置环境变量 DEEPSEEK_API_KEY 或修改 application.yml 的 app.llm.api-key", null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModel());
        payload.put("messages", messages);
        payload.put("temperature", props.getTemperature());
        payload.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(props.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        long startedAt = System.currentTimeMillis();
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 连接失败 / 读超时 —— 可重试
            throw new RetryableException("调用 LLM 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("调用 LLM 被中断", e);
        }

        int status = response.statusCode();
        long cost = System.currentTimeMillis() - startedAt;

        if (status >= 500 || status == 429) {
            log.warn("LLM 返回 {}，耗时 {} ms，将进入重试", status, cost);
            throw new RetryableException("LLM 返回 " + status + ": " + JsonUtil.abbreviate(response.body()), null);
        }
        if (status >= 400) {
            // 参数错、鉴权错，重试多少次都一样
            throw new NonRetryableException("LLM 返回 " + status + ": " + JsonUtil.abbreviate(response.body()), null);
        }

        log.debug("LLM 调用成功，耗时 {} ms，prompt {} 字符，completion {} 字符",
                cost, JsonUtil.toJson(messages).length(), response.body().length());

        String content = JsonUtil.fromJson(response.body(), ChatCompletionResponse.class).firstContent();
        if (content == null || content.isBlank()) {
            throw new RetryableException("LLM 返回内容为空", null);
        }
        return content;
    }
}
```

- [ ] **Step 6: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

若报 `JsonMapper.builder()` 或 `readValue` 找不到，说明 Jackson 3 的 API 名字与预期不符——用 IDE 跳进 `tools.jackson.databind.json.JsonMapper` 确认实际方法名后修正。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/
git commit -m "feat(llm): OpenAI 兼容客户端（JDK HttpClient 实现）"
```

---

## Task 9: wrapper/llm —— 重试装饰器与 Bean 装配

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/RetryableLlmClient.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmConfig.java`

- [ ] **Step 1: 写 `RetryableLlmClient.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import com.ke.nhservice.aimianshi.common.exception.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 重试装饰器：首次失败后重试 4 次，共最多 5 次调用。
 *
 * 只捕获 RetryableException（超时 / 连接失败 / 5xx / 429）。
 * NonRetryableException（4xx）直接穿透，不做无谓等待。
 *
 * 已知影响：最坏情况光等待就是 75 秒，加上 5 次调用耗时，单次请求可能超过 2 分钟。
 * 前端 loading 文案要写明这一点。
 */
public class RetryableLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RetryableLlmClient.class);

    /** 第 1 次失败后等 5s，第 2 次等 10s，第 3 次等 20s，第 4 次等 40s */
    private static final long[] BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L};

    private final LlmClient delegate;

    public RetryableLlmClient(LlmClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        for (int attempt = 0; ; attempt++) {
            try {
                return delegate.chat(messages);
            } catch (RetryableException e) {
                if (attempt >= BACKOFF_MS.length) {
                    log.error("LLM 调用失败 {} 次后放弃: {}", attempt + 1, e.getMessage());
                    throw e;
                }
                long waitMs = BACKOFF_MS[attempt];
                log.warn("LLM 调用失败（第 {} 次），{} ms 后重试: {}", attempt + 1, waitMs, e.getMessage());
                sleep(waitMs);
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException("重试等待被中断", e);
        }
    }
}
```

- [ ] **Step 2: 写 `LlmConfig.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    /**
     * 装配顺序：真实客户端在外面，重试装饰器包在里面。
     * 注意是 new RetryableLlmClient(new OpenAiCompatibleClient(props))——
     * 装饰器在最外层，才能拦住内层抛出的 RetryableException。
     */
    @Bean
    public LlmClient llmClient(LlmProperties props) {
        return new RetryableLlmClient(new OpenAiCompatibleClient(props));
    }
}
```

- [ ] **Step 3: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/
git commit -m "feat(llm): 指数退避重试装饰器（5s/10s/20s/40s，仅重试可重试错误）"
```

---

## Task 10: wrapper/pdf —— PDF 解析

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/wrapper/pdf/PdfTextExtractor.java`

- [ ] **Step 1: 写 `PdfTextExtractor.java`**

```java
package com.ke.nhservice.aimianshi.wrapper.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * PDF 取文本。PDFBox 3 的入口是 Loader.loadPDF，不再是 PDDocument.load。
 */
public final class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 简历正文超过这个长度就截断——长简历塞进 prompt 又贵又没用 */
    private static final int MAX_CHARS = 8000;

    private PdfTextExtractor() {
    }

    /**
     * @throws IOException 文件损坏、加密、不是 PDF
     */
    public static String extract(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String cleaned = cleanup(text);
            log.info("PDF 解析完成：{} 页，提取 {} 字符", document.getNumberOfPages(), cleaned.length());
            if (cleaned.isBlank()) {
                log.warn("PDF 解析结果为空，可能是扫描件（图片型 PDF），当前不支持 OCR");
            }
            return cleaned;
        }
    }

    private static String cleanup(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n")
                .replace('\u00A0', ' ')       // 不间断空格，PDF 里很常见
                .replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS);
    }
}
```

- [ ] **Step 2: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。若报 `Loader` 找不到，说明拉到的不是 PDFBox 3——检查 `pom.xml` 里的 `<version>3.0.4</version>` 有没有被 BOM 覆盖。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/wrapper/pdf/
git commit -m "feat(pdf): PDFBox 3 提取简历文本"
```

---

## Task 11: 认证 —— token、当前用户、拦截器

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/user/User.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/user/UserDao.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/auth/TokenUtil.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/auth/UserContext.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/auth/AuthInterceptor.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/config/WebConfig.java`

- [ ] **Step 1: 写 `User.java`**

```java
package com.ke.nhservice.aimianshi.biz.user;

public record User(Long id, String username, String passwordHash, String nickname, long createdAt) {
}
```

- [ ] **Step 2: 写 `UserDao.java`**

```java
package com.ke.nhservice.aimianshi.biz.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserDao {

    private static final RowMapper<User> MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbc.query(
                "SELECT id, username, password_hash, nickname, created_at FROM t_user WHERE username = ?",
                MAPPER, username);
        return list.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> list = jdbc.query(
                "SELECT id, username, password_hash, nickname, created_at FROM t_user WHERE id = ?",
                MAPPER, id);
        return list.stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM t_user", Long.class);
        return n == null ? 0 : n;
    }

    public long insert(String username, String passwordHash, String nickname) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_user(username, password_hash, nickname, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            ps.setLong(4, System.currentTimeMillis());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入用户后没有拿到自增主键");
        }
        return key.longValue();
    }
}
```

- [ ] **Step 3: 写 `TokenUtil.java`**

```java
package com.ke.nhservice.aimianshi.common.auth;

import com.ke.nhservice.aimianshi.common.config.AppProperties;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC 自签 token，零依赖（JDK 自带的 javax.crypto）。
 *
 * 格式：base64url( userId + "." + expireAt + "." + hmacSha256(userId + "." + expireAt) )
 *
 * 已知限制：无法主动失效，登出只是前端删掉 token。单用户自用场景可接受。
 * 好处是密钥在配置里，服务重启后 token 依然有效。
 */
@Component
public class TokenUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final AppProperties props;

    public TokenUtil(AppProperties props) {
        this.props = props;
    }

    public String issue(Long userId) {
        long expireAt = System.currentTimeMillis() + props.getTtlMillis();
        String payload = userId + "." + expireAt;
        String raw = payload + "." + sign(payload);
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 用户 id；token 缺失/被篡改/过期一律抛 401 */
    public Long verify(String token) {
        if (token == null || token.isBlank()) {
            throw BizException.unauthorized("未登录");
        }
        String raw;
        try {
            raw = new String(DECODER.decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw BizException.unauthorized("登录凭证格式错误");
        }

        String[] parts = raw.split("\\.");
        if (parts.length != 3) {
            throw BizException.unauthorized("登录凭证格式错误");
        }

        String expected = sign(parts[0] + "." + parts[1]);
        // 定长比较，避免时序侧信道
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw BizException.unauthorized("登录凭证校验失败");
        }

        long expireAt;
        try {
            expireAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw BizException.unauthorized("登录凭证格式错误");
        }
        if (System.currentTimeMillis() > expireAt) {
            throw BizException.unauthorized("登录已过期，请重新登录");
        }

        return Long.parseLong(parts[0]);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(props.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }
}
```

- [ ] **Step 4: 写 `UserContext.java`**

```java
package com.ke.nhservice.aimianshi.common.auth;

import com.ke.nhservice.aimianshi.common.exception.BizException;

/**
 * 当前登录用户。由 AuthInterceptor 在请求开始时塞入、结束时清理。
 * 用 ThreadLocal 而不是给每个 Controller 方法加参数，是因为绝大多数接口都要用。
 *
 * 用 @RequestAttribute 也可以，但那样每个方法签名都得带一个参数，更啰嗦。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        CURRENT.set(userId);
    }

    public static Long get() {
        Long userId = CURRENT.get();
        if (userId == null) {
            throw BizException.unauthorized("未登录");
        }
        return userId;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

- [ ] **Step 5: 写 `AuthInterceptor.java`**

```java
package com.ke.nhservice.aimianshi.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final TokenUtil tokenUtil;

    public AuthInterceptor(TokenUtil tokenUtil) {
        this.tokenUtil = tokenUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        String token = header != null && header.startsWith(PREFIX)
                ? header.substring(PREFIX.length()).trim()
                : null;
        // verify 失败会抛 BizException(401)，由 GlobalExceptionHandler 统一转成响应
        UserContext.set(tokenUtil.verify(token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        // 线程池会复用线程，必须清掉，否则下一个请求会串号
        UserContext.clear();
    }
}
```

- [ ] **Step 6: 写 `WebConfig.java`**

```java
package com.ke.nhservice.aimianshi.common.config;

import com.ke.nhservice.aimianshi.common.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦 /api/**，静态页面和前端资源不拦——
        // 否则未登录时连 login.html 都打不开，死锁。
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }
}
```

- [ ] **Step 7: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/
git commit -m "feat(auth): HMAC 自签 token、ThreadLocal 当前用户、只拦 /api/** 的拦截器"
```

---

## Task 12: 认证 —— 用户服务、控制器、全局异常处理

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/LoginRequest.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/LoginVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/MeVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/user/UserService.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/exception/GlobalExceptionHandler.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/controller/AuthController.java`

- [ ] **Step 1: 写三个 DTO**

`LoginRequest.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record LoginRequest(String username, String password) {
}
```

`LoginVO.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record LoginVO(String token, String nickname) {
}
```

`MeVO.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record MeVO(Long id, String username, String nickname) {
}
```

- [ ] **Step 2: 写 `UserService.java`**

```java
package com.ke.nhservice.aimianshi.biz.user;

import com.ke.nhservice.aimianshi.common.auth.TokenUtil;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final String SEED_USERNAME = "admin";
    private static final String SEED_PASSWORD = "admin123";

    private final UserDao userDao;
    private final TokenUtil tokenUtil;
    /** 只用了 BCrypt 一个类，所以不引 Spring Security 全家桶，只引 spring-security-crypto */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserDao userDao, TokenUtil tokenUtil) {
        this.userDao = userDao;
        this.tokenUtil = tokenUtil;
    }

    public LoginResult login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BizException("用户名和密码不能为空");
        }
        User user = userDao.findByUsername(username.trim())
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!encoder.matches(rawPassword, user.passwordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return new LoginResult(tokenUtil.issue(user.id()), user);
    }

    /** 登录成功要同时把 token 和用户信息返回给前端，所以两个一起给 */
    public record LoginResult(String token, User user) {
    }

    public User requireUser(Long userId) {
        return userDao.findById(userId)
                .orElseThrow(() -> BizException.unauthorized("用户不存在"));
    }

    /**
     * 首次启动时建一个演示账号，省掉注册流程（设计文档明确不做注册）。
     * 密码用 BCrypt 存，不存明文。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (userDao.count() > 0) {
            return;
        }
        userDao.insert(SEED_USERNAME, encoder.encode(SEED_PASSWORD), "演示账号");
        log.warn("已创建默认账号 {} / {}，请尽快修改密码（当前没有改密接口，直接改库或删库重建）",
                SEED_USERNAME, SEED_PASSWORD);
    }
}
```

- [ ] **Step 3: 写 `GlobalExceptionHandler.java`**

```java
package com.ke.nhservice.aimianshi.common.exception;

import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        // 401 同时改 HTTP 状态，前端才能统一拦截跳登录页；
        // 其余业务错误用 HTTP 200 + code，前端按 code 提示即可
        HttpStatus status = e.getCode() == 401 ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误：" + e.getMessage()));
    }
}
```

- [ ] **Step 4: 写 `AuthController.java`**

登录响应里要带昵称，所以让 `UserService.login` 直接返回「token + 用户」，
而不是只返回 token 再反查一次。

```java
package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.user.User;
import com.ke.nhservice.aimianshi.biz.user.UserService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.LoginRequest;
import com.ke.nhservice.aimianshi.common.dto.LoginVO;
import com.ke.nhservice.aimianshi.common.dto.MeVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginVO> login(@RequestBody LoginRequest request) {
        UserService.LoginResult result = userService.login(request.username(), request.password());
        return ApiResponse.ok(new LoginVO(result.token(), result.user().nickname()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        // token 无法主动失效（见 TokenUtil 注释），登出只是前端删掉本地 token
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<MeVO> me() {
        User user = userService.requireUser(UserContext.get());
        return ApiResponse.ok(new MeVO(user.id(), user.username(), user.nickname()));
    }
}
```

- [ ] **Step 5: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

- [ ] **Step 6: 启动并验证登录**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run
```

另开终端：
```bash
# 未带 token 访问受保护接口 → 401
curl -s -o /dev/null -w "no-token: %{http_code}\n" http://localhost:8080/api/auth/me

# 登录
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
Expected:
```
no-token: 401
{"code":0,"message":"ok","data":{"token":"eyJ...","nickname":"演示账号"}}
```

```bash
# 拿上一步的 token 再试
TOKEN=<粘贴上面的 token>
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```
Expected: `{"code":0,"message":"ok","data":{"id":1,"username":"admin","nickname":"演示账号"}}`

启动日志里应能看到 `已创建默认账号 admin / admin123`。`Ctrl+C` 停止。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/
git commit -m "feat(auth): 登录接口、BCrypt 校验、默认账号、全局异常处理"
```

---

## Task 13: 简历上传与解析

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/Resume.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeDao.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeService.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/controller/ResumeController.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/knowledge/Chunk.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/knowledge/Retriever.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/knowledge/EmptyRetriever.java`

- [ ] **Step 1: 写 `Resume.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

public record Resume(Long id, Long userId, String filename, String content, boolean isDefault, long createdAt) {

    /** 列表展示用的摘要：去掉换行、截断，避免前端渲染出一大坨 */
    public String preview() {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
```

- [ ] **Step 2: 写 `ResumeDao.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class ResumeDao {

    private static final RowMapper<Resume> MAPPER = (rs, rowNum) -> new Resume(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("filename"),
            rs.getString("content"),
            rs.getInt("is_default") == 1,
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ResumeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long userId, String filename, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_resume(user_id, filename, content, is_default, created_at) VALUES (?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, filename);
            ps.setString(3, content);
            ps.setLong(4, System.currentTimeMillis());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入简历后没有拿到自增主键");
        }
        return key.longValue();
    }

    public List<Resume> listByUser(Long userId) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE user_id = ? ORDER BY is_default DESC, created_at DESC
                """, MAPPER, userId);
    }

    public Optional<Resume> findById(Long id) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Resume> findDefault(Long userId) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE user_id = ? AND is_default = 1
                ORDER BY created_at DESC LIMIT 1
                """, MAPPER, userId).stream().findFirst();
    }

    public int countByUser(Long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM t_resume WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** 先把该用户所有简历的标记清掉再设新的，保证「默认简历」全局唯一 */
    @Transactional
    public void setDefault(Long userId, Long resumeId) {
        jdbc.update("UPDATE t_resume SET is_default = 0 WHERE user_id = ?", userId);
        jdbc.update("UPDATE t_resume SET is_default = 1 WHERE id = ? AND user_id = ?", resumeId, userId);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM t_resume WHERE id = ?", id);
    }
}
```

- [ ] **Step 3: 写 `ResumeService.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.pdf.PdfTextExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class ResumeService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ResumeDao resumeDao;

    public ResumeService(ResumeDao resumeDao) {
        this.resumeDao = resumeDao;
    }

    public Resume upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        String filename = file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".pdf")) {
            throw new BizException("只支持 PDF 格式的简历");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("文件超过 10MB 上限");
        }

        String content;
        try {
            content = PdfTextExtractor.extract(file.getBytes());
        } catch (IOException e) {
            throw new BizException("PDF 解析失败，请确认文件未损坏或加密：" + e.getMessage());
        }
        if (content.isBlank()) {
            throw new BizException("没能从这份 PDF 里提取到文字，可能是扫描件（图片型 PDF），当前不支持 OCR");
        }

        long id = resumeDao.insert(userId, filename, content);
        // 第一份简历自动设为默认，省掉用户一次点击
        if (resumeDao.countByUser(userId) == 1) {
            resumeDao.setDefault(userId, id);
        }
        return resumeDao.findById(id).orElseThrow(() -> new BizException("简历保存后读取失败"));
    }

    public List<Resume> list(Long userId) {
        return resumeDao.listByUser(userId);
    }

    public void setDefault(Long userId, Long resumeId) {
        requireOwned(userId, resumeId);
        resumeDao.setDefault(userId, resumeId);
    }

    public void delete(Long userId, Long resumeId) {
        requireOwned(userId, resumeId);
        resumeDao.delete(resumeId);
    }

    /** 面试开始时解析简历文本用 */
    public Resume requireOwned(Long userId, Long resumeId) {
        Resume resume = resumeDao.findById(resumeId)
                .orElseThrow(() -> BizException.notFound("简历不存在"));
        if (!resume.userId().equals(userId)) {
            throw BizException.notFound("简历不存在");
        }
        return resume;
    }

    /** 面试开始时若没指定简历，就用默认简历；一份都没有则返回 null（允许无简历面试） */
    public Resume defaultOrNull(Long userId) {
        return resumeDao.findDefault(userId).orElse(null);
    }
}
```

- [ ] **Step 4: 写 `ResumeVO.java`**

```java
package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.biz.resume.Resume;

public record ResumeVO(Long id, String filename, boolean isDefault, long createdAt, String preview) {

    public static ResumeVO of(Resume resume) {
        return new ResumeVO(resume.id(), resume.filename(), resume.isDefault(),
                resume.createdAt(), resume.preview());
    }
}
```

- [ ] **Step 5: 写 `ResumeController.java`**

```java
package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.resume.Resume;
import com.ke.nhservice.aimianshi.biz.resume.ResumeService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.ResumeVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/upload")
    public ApiResponse<ResumeVO> upload(@RequestParam("file") MultipartFile file) {
        Resume resume = resumeService.upload(UserContext.get(), file);
        return ApiResponse.ok(ResumeVO.of(resume));
    }

    @GetMapping("/list")
    public ApiResponse<List<ResumeVO>> list() {
        List<ResumeVO> list = resumeService.list(UserContext.get()).stream()
                .map(ResumeVO::of)
                .toList();
        return ApiResponse.ok(list);
    }

    @PostMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable Long id) {
        resumeService.setDefault(UserContext.get(), id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resumeService.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 6: 启动并验证上传**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run
```

另开终端（先登录拿 token，再用任意一个 PDF 测试；手边没有 PDF 可先用系统里现成的）：
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 找一个 PDF 来试
curl -s -X POST http://localhost:8080/api/resume/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/your/resume.pdf"

# 非 PDF 应被拒
curl -s -X POST http://localhost:8080/api/resume/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@pom.xml"
```
Expected:
- 第一个：`{"code":0,...,"data":{"id":1,"filename":"resume.pdf","isDefault":true,...}}`
- 第二个：`{"code":400,"message":"只支持 PDF 格式的简历","data":null}`

> **✅ 已验证（2026-09-28）：key 就是 `isDefault`，不需要加注解。**
>
> 当初的担心是：Jackson 3 可能对 record 的布尔组件按 JavaBean 习惯吃掉 `is` 前缀，
> 序列化成 `default`，那样前端所有 `r.isDefault` 都会变 `undefined`。
> 实测上传一份 PDF 后的真实响应体：
> ```json
> {"code":0,"message":"ok","data":{"id":1,"filename":"sample-resume.pdf",
>  "isDefault":true,"createdAt":1790585947198,"preview":"Zhang San Java Backend..."}}
> ```
> 断言 `contains("\"isDefault\"") == true` 且 `contains("\"default\":") == false`，通过。
> 结论：Jackson 3 对 record 直接用**组件名**做 key，不做 JavaBean 的 `is` 前缀推断。
> **不要**再加 `@JsonProperty("isDefault")`——那是多余的。前端照 `r.isDefault` 写即可。

- [ ] **Step 7: 建知识库占位接口**

设计文档六.5 明确要求「一期不建表，只提供接口」，文件结构总览里的 `biz/knowledge/` 就是它。
三个文件、没有调用方，建它是为了让二期接 embedding 时业务代码不用动。

`Chunk.java`：

```java
package com.ke.nhservice.aimianshi.biz.knowledge;

/** 检索到的一段上下文。一期不会产生实例，只定义形状 */
public record Chunk(String source, String content, double score) {
}
```

`Retriever.java`：

```java
package com.ke.nhservice.aimianshi.biz.knowledge;

import java.util.List;

/**
 * 知识库检索。一期只提供接口不实现，
 * 二期接国产 embedding API 时新增实现类即可，调用方不用改。
 */
public interface Retriever {

    List<Chunk> retrieve(String query, int topK);
}
```

`EmptyRetriever.java`：

```java
package com.ke.nhservice.aimianshi.biz.knowledge;

import org.springframework.stereotype.Component;

import java.util.List;

/** 一期默认实现：永远返回空。二期换掉这个类，其余代码无感 */
@Component
public class EmptyRetriever implements Retriever {

    @Override
    public List<Chunk> retrieve(String query, int topK) {
        return List.of();
    }
}
```

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/
git commit -m "feat(resume): PDF 上传解析、默认简历、增删查接口；补知识库占位接口"
```

---

## Task 14: 面试业务状态类

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResult.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/Dialogue.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/HistoryItem.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/ScoreHistory.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/TopicTracker.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewState.java`

- [ ] **Step 1: 写 `EvalResult.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.constant.NextAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次评分的完整结果。五个维度是复盘雷达图的数据源，一期就先采全。
 */
public class EvalResult {

    /** 五个维度的 key，顺序固定：决定了前端雷达图的轴顺序 */
    public static final List<String> DIMENSIONS =
            List.of("accuracy", "depth", "clarity", "practice", "problemSolving");

    public static final Map<String, String> DIMENSION_LABELS = Map.of(
            "accuracy", "准确性",
            "depth", "深度",
            "clarity", "表达",
            "practice", "实践",
            "problemSolving", "解题思路");

    private double overall;
    private Map<String, Double> dimensions = new LinkedHashMap<>();
    private List<String> coveredTopics = List.of();
    private String comment = "";
    private NextAction nextAction = NextAction.CONTINUE;

    /** 评分服务不可用时的降级结果：不中断面试，按 CONTINUE 走 */
    public static EvalResult degraded(String reason) {
        EvalResult r = new EvalResult();
        r.setOverall(0);
        r.setComment("评分服务暂不可用（" + reason + "），本题不计入有效评分");
        r.setNextAction(NextAction.CONTINUE);
        Map<String, Double> dims = new LinkedHashMap<>();
        for (String key : DIMENSIONS) {
            dims.put(key, 0.0);
        }
        r.setDimensions(dims);
        return r;
    }

    public double getOverall() { return overall; }

    public void setOverall(double overall) { this.overall = overall; }

    public Map<String, Double> getDimensions() { return dimensions; }

    public void setDimensions(Map<String, Double> dimensions) { this.dimensions = dimensions; }

    public List<String> getCoveredTopics() { return coveredTopics; }

    public void setCoveredTopics(List<String> coveredTopics) { this.coveredTopics = coveredTopics; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public NextAction getNextAction() { return nextAction; }

    public void setNextAction(NextAction nextAction) { this.nextAction = nextAction; }
}
```

- [ ] **Step 2: 写 `Dialogue.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一道题的完整记录。同时是 t_interview_dialogue 一行的 Java 形态。
 */
public class Dialogue {

    private int seq;
    private String topic;
    private String difficulty;      // 中文标签：简单/中等/困难
    private String question;
    private String answer;
    private Double score;
    private Map<String, Double> dimensions = new LinkedHashMap<>();
    private String comment;
    private String nextAction;      // deepen|continue|lower|switch|end
    private String nextTopic;       // 走 switch 时换到的话题

    public int getSeq() { return seq; }

    public void setSeq(int seq) { this.seq = seq; }

    public String getTopic() { return topic; }

    public void setTopic(String topic) { this.topic = topic; }

    public String getDifficulty() { return difficulty; }

    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public Double getScore() { return score; }

    public void setScore(Double score) { this.score = score; }

    public Map<String, Double> getDimensions() { return dimensions; }

    public void setDimensions(Map<String, Double> dimensions) { this.dimensions = dimensions; }

    public String getComment() { return comment; }

    public void setComment(String comment) { this.comment = comment; }

    public String getNextAction() { return nextAction; }

    public void setNextAction(String nextAction) { this.nextAction = nextAction; }

    public String getNextTopic() { return nextTopic; }

    public void setNextTopic(String nextTopic) { this.nextTopic = nextTopic; }
}
```

- [ ] **Step 3: 写 `HistoryItem.java` 和 `ScoreHistory.java`**

`HistoryItem.java`:

```java
package com.ke.nhservice.aimianshi.biz.interview;

/**
 * 滑动窗口里的一题。只保留最近 2 题——
 * token 按量计费，历史全带上会让 prompt 随轮次线性膨胀。
 */
public class HistoryItem {

    private String question;
    private String answer;
    private Double score;

    public HistoryItem() {
    }

    public HistoryItem(String question, String answer, Double score) {
        this.question = question;
        this.answer = answer;
        this.score = score;
    }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public Double getScore() { return score; }

    public void setScore(Double score) { this.score = score; }
}
```

`ScoreHistory.java`:

```java
package com.ke.nhservice.aimianshi.biz.interview;

import java.util.ArrayList;
import java.util.List;

public class ScoreHistory {

    private List<Double> scores = new ArrayList<>();

    public void add(double score) {
        scores.add(score);
    }

    public List<Double> getScores() { return scores; }

    public void setScores(List<Double> scores) { this.scores = scores; }

    public double total() {
        double sum = 0;
        for (Double s : scores) {
            sum += s == null ? 0 : s;
        }
        return sum;
    }

    public double average() {
        return scores.isEmpty() ? 0 : total() / scores.size();
    }
}
```

- [ ] **Step 4: 写 `TopicTracker.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import java.util.ArrayList;
import java.util.List;

/**
 * 话题追踪。累积状态随 InterviewState 一起序列化进 state_json，所以跨请求保持。
 *
 * 话题池来自配置（app.interview.topics），不让 LLM 现场生成——
 * 话题池决定了整场面试的覆盖面，需要稳定可控、可人工调整，还省掉一次 LLM 调用。
 */
public class TopicTracker {

    private List<String> allTopics = new ArrayList<>();
    private List<String> coveredTopics = new ArrayList<>();
    private String currentTopic;

    /** 当前话题连续追问了几次。达到上限就强制换话题（见 EvaluateNode） */
    private int followUpCount;

    /** 挑一个还没聊过的话题；全聊完了就回到第一个，允许循环 */
    public String suggestNextTopic() {
        if (allTopics.isEmpty()) {
            return currentTopic;
        }
        for (String topic : allTopics) {
            if (!coveredTopics.contains(topic)) {
                return topic;
            }
        }
        return allTopics.get(0);
    }

    public void markCovered(String topic) {
        if (topic != null && !coveredTopics.contains(topic)) {
            coveredTopics.add(topic);
        }
    }

    public List<String> getAllTopics() { return allTopics; }

    public void setAllTopics(List<String> allTopics) {
        this.allTopics = allTopics == null ? new ArrayList<>() : new ArrayList<>(allTopics);
    }

    public List<String> getCoveredTopics() { return coveredTopics; }

    public void setCoveredTopics(List<String> coveredTopics) {
        this.coveredTopics = coveredTopics == null ? new ArrayList<>() : coveredTopics;
    }

    public String getCurrentTopic() { return currentTopic; }

    public void setCurrentTopic(String currentTopic) { this.currentTopic = currentTopic; }

    public int getFollowUpCount() { return followUpCount; }

    public void setFollowUpCount(int followUpCount) { this.followUpCount = followUpCount; }
}
```

- [ ] **Step 5: 写 `InterviewState.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.constant.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试的业务状态。整个对象序列化成 JSON 存进 t_interview_record.state_json。
 *
 * 这个类必须能被 Jackson 无参构造 + getter/setter 完整还原，
 * 因为它就是「断点续传」的全部依据。
 */
public class InterviewState {

    // ── 会话标识 ──
    private Long recordId;
    private String sessionId;

    // ── 会话配置（StartNode 从记录表带进来，供出题提示词使用）──
    private String position;
    private String company;
    private String domain;
    private String resumeSummary;

    // ── 当前轮次 ──
    /** 从 1 开始 */
    private int questionIndex;
    /** 本题难度，初始 = 会话难度，被 deepen/lower 调整 */
    private Difficulty currentDifficulty = Difficulty.MEDIUM;
    private String questionText;
    /** 挂起后由下一次 HTTP 请求塞入；被 evaluate 消费后清空 */
    private String answer;
    private EvalResult evalResult;

    // ── 分支节点写入的「下一题方向」──
    private String nextActionHint;

    // ── 累积状态（跨轮次）──
    private List<Dialogue> dialogues = new ArrayList<>();
    private ScoreHistory scoreHistory = new ScoreHistory();
    private TopicTracker topicTracker = new TopicTracker();
    /** 滑动窗口，只保留最近 2 题 */
    private List<HistoryItem> recentHistory = new ArrayList<>();

    // ── 控制 ──
    private boolean shouldStop;
    private int maxQuestions = 10;
    private String error;

    // ── 收尾产物（EndNode 写入）──
    private String report;
    private Double totalScore;
    /** 本题实际走的分支（deepen/continue/lower/switch/end），供接口返回与复盘展示 */
    private String lastRouting;

    public void pushHistory(HistoryItem item, int keep) {
        recentHistory.add(item);
        while (recentHistory.size() > keep) {
            recentHistory.remove(0);
        }
    }

    public Long getRecordId() { return recordId; }

    public void setRecordId(Long recordId) { this.recordId = recordId; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getPosition() { return position; }

    public void setPosition(String position) { this.position = position; }

    public String getCompany() { return company; }

    public void setCompany(String company) { this.company = company; }

    public String getDomain() { return domain; }

    public void setDomain(String domain) { this.domain = domain; }

    public String getResumeSummary() { return resumeSummary; }

    public void setResumeSummary(String resumeSummary) { this.resumeSummary = resumeSummary; }

    public int getQuestionIndex() { return questionIndex; }

    public void setQuestionIndex(int questionIndex) { this.questionIndex = questionIndex; }

    public Difficulty getCurrentDifficulty() { return currentDifficulty; }

    public void setCurrentDifficulty(Difficulty currentDifficulty) {
        this.currentDifficulty = currentDifficulty;
    }

    public String getQuestionText() { return questionText; }

    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public EvalResult getEvalResult() { return evalResult; }

    public void setEvalResult(EvalResult evalResult) { this.evalResult = evalResult; }

    public String getNextActionHint() { return nextActionHint; }

    public void setNextActionHint(String nextActionHint) { this.nextActionHint = nextActionHint; }

    public List<Dialogue> getDialogues() { return dialogues; }

    public void setDialogues(List<Dialogue> dialogues) { this.dialogues = dialogues; }

    public ScoreHistory getScoreHistory() { return scoreHistory; }

    public void setScoreHistory(ScoreHistory scoreHistory) { this.scoreHistory = scoreHistory; }

    public TopicTracker getTopicTracker() { return topicTracker; }

    public void setTopicTracker(TopicTracker topicTracker) { this.topicTracker = topicTracker; }

    public List<HistoryItem> getRecentHistory() { return recentHistory; }

    public void setRecentHistory(List<HistoryItem> recentHistory) { this.recentHistory = recentHistory; }

    public boolean isShouldStop() { return shouldStop; }

    public void setShouldStop(boolean shouldStop) { this.shouldStop = shouldStop; }

    public int getMaxQuestions() { return maxQuestions; }

    public void setMaxQuestions(int maxQuestions) { this.maxQuestions = maxQuestions; }

    public String getError() { return error; }

    public void setError(String error) { this.error = error; }

    public String getReport() { return report; }

    public void setReport(String report) { this.report = report; }

    public Double getTotalScore() { return totalScore; }

    public void setTotalScore(Double totalScore) { this.totalScore = totalScore; }

    public String getLastRouting() { return lastRouting; }

    public void setLastRouting(String lastRouting) { this.lastRouting = lastRouting; }
}
```

- [ ] **Step 6: 验证序列化能往返**

> **✅ 已验证（2026-09-28）：16/16 通过，`round trip byte-identical: same`。**
>
> 验证方式与下面写的有出入，以这里为准：临时类**放在仓库外**，不落进 `src/`，
> 省掉「创建再删除」两步，也避免误提交。
> ```bash
> # 写到系统临时目录，用单文件源码直接运行，classpath 指向 target/classes + 依赖
> "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 \
>   -cp "target/classes;$(cat /tmp/cp.txt)" /tmp/StateJsonSmokeTest.java
> ```
> （`/tmp/cp.txt` 由 `dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt` 生成一次即可。）
>
> 结论要点：`Difficulty`/`NextAction` 枚举按 `name()` 存、按 `name()` 还原；
> `null` 字段（如 `totalScore`）能存活；`List.of()` 与会话中 `ArrayList` 的差异不影响一致性；
> `public static final` 的 `DIMENSIONS`/`DIMENSION_LABELS` 被 Jackson 正确忽略（static 不序列化）。

创建临时类 `src/main/java/com/ke/nhservice/aimianshi/biz/interview/StateJsonSmokeTest.java`：

```java
package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.common.constant.NextAction;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;

/** 临时手工验证用，验证完即删 */
public class StateJsonSmokeTest {

    public static void main(String[] args) {
        InterviewState s = new InterviewState();
        s.setRecordId(1L);
        s.setSessionId("abc");
        s.setQuestionIndex(3);
        s.setCurrentDifficulty(Difficulty.HARD);
        s.setQuestionText("什么是 JMM？");
        s.getTopicTracker().setAllTopics(java.util.List.of("JVM 内存模型", "并发编程"));
        s.getTopicTracker().setCurrentTopic("JVM 内存模型");
        s.getTopicTracker().markCovered("JVM 内存模型");
        s.setEvalResult(new EvalResult());
        s.getEvalResult().setOverall(7.5);
        s.getEvalResult().setNextAction(NextAction.DEEPEN);
        s.getScoreHistory().add(7.5);
        s.pushHistory(new HistoryItem("q1", "a1", 7.5), 2);
        s.pushHistory(new HistoryItem("q2", "a2", 6.0), 2);
        s.pushHistory(new HistoryItem("q3", "a3", 8.0), 2);

        String json = JsonUtil.toJson(s);
        System.out.println("JSON 长度: " + json.length());
        System.out.println("滑动窗口保留: " + s.getRecentHistory().size() + " 条（应为 2）");

        InterviewState back = JsonUtil.fromJson(json, InterviewState.class);
        System.out.println("还原后 recordId=" + back.getRecordId()
                + " 难度=" + back.getCurrentDifficulty()
                + " nextAction=" + back.getEvalResult().getNextAction()
                + " 已覆盖话题=" + back.getTopicTracker().getCoveredTopics()
                + " 均分=" + back.getScoreHistory().average()
                + " 窗口=" + back.getRecentHistory().size());
        System.out.println("往返一致: " + json.equals(JsonUtil.toJson(back)));
    }
}
```

Run:
```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
java -cp target/classes com.ke.nhservice.aimianshi.biz.interview.StateJsonSmokeTest
```
Expected:
```
JSON 长度: xxx
滑动窗口保留: 2 条（应为 2）
还原后 recordId=1 难度=HARD nextAction=DEEPEN 已覆盖话题=[JVM 内存模型] 均分=7.5 窗口=2
往返一致: true
```

**这一条很关键**：`往返一致: true` 说明「挂起 → 存 JSON → 下次读回来接着跑」不会丢字段。如果这里是 false，先解决再往下走。

- [ ] **Step 7: 删除临时类并提交**

```bash
rm src/main/java/com/ke/nhservice/aimianshi/biz/interview/StateJsonSmokeTest.java
git add src/main/java/com/ke/nhservice/aimianshi/
git commit -m "feat(interview): 面试业务状态类（含 JSON 往返验证）"
```

---

## Task 15: 提示词加载器与 8 个提示词文件

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/prompt/PromptLoader.java`
- Create: `src/main/resources/prompts/*.md`（8 个）

- [ ] **Step 1: 写 `PromptLoader.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词外置在 resources/prompts/*.md，不硬编码进 Java 文件。
 * 提示词本质是配置，不是代码。
 *
 * 占位符用 {key} 而不是 String.format 的 %s——
 * 提示词里出现 % 是常事（「准确率提升 30%」），用 %s 会直接炸。
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 读原始模板。缓存起来，避免每次出题都读一遍磁盘 */
    public String load(String name) {
        return cache.computeIfAbsent(name, key -> {
            ClassPathResource resource = new ClassPathResource("prompts/" + key + ".md");
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("提示词文件不存在或读取失败: prompts/" + key + ".md", e);
            }
        });
    }

    /** 读模板并把 {key} 替换掉。没传的占位符保持原样，方便发现漏传 */
    public String render(String name, Map<String, String> values) {
        String template = load(name);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return template;
    }
}
```

- [ ] **Step 2: 写 8 个提示词文件**

`src/main/resources/prompts/question_first.md`:

```markdown
你是一位资深 {position} 面试官，正在面试一位候选人。

候选人背景（简历摘要）：
{resume}

本次面试信息：
- 岗位：{position}
- 目标公司：{company}
- 技术方向：{domain}
- 题目难度：{difficulty}
- 这是第 {questionIndex} 题，共 {maxQuestions} 题
- 本题话题：{topic}

请提出第一道面试题。要求：
1. 只输出题目本身，不要有任何前缀、编号、解释或问候语
2. 题目要具体，避免「请介绍一下 XXX」这类泛泛而问
3. 难度符合「{difficulty}」档位
4. 优先结合候选人简历中的真实项目经历提问
5. 长度控制在 100 字以内
```

`src/main/resources/prompts/question_followup.md`:

```markdown
你是一位资深 {position} 面试官，正在继续一场面试。

候选人背景（简历摘要）：
{resume}

本次面试信息：
- 岗位：{position}
- 技术方向：{domain}
- 这是第 {questionIndex} 题，共 {maxQuestions} 题
- 本题话题：{topic}
- 本题难度：{difficulty}

上一轮面试官给出的追问方向：
{nextActionHint}

最近的问答记录：
{history}

请据此提出下一道面试题。要求：
1. 只输出题目本身，不要有任何前缀、编号、解释或问候语
2. 顺着上面的「追问方向」提问，不要跑题
3. 如果追问方向要求换话题，就围绕话题「{topic}」重新提问
4. 难度符合「{difficulty}」档位
5. 不要重复「最近的问答记录」里已经问过的问题
6. 长度控制在 100 字以内
```

`src/main/resources/prompts/hint_deepen.md`:

```markdown
候选人上一题回答得不错，已经答到了点上。请继续深挖：针对同一个话题追问更底层的原理、边界情况、极端场景，或者问「为什么这么设计」。目的是考察技术深度。不要换话题。
```

`src/main/resources/prompts/hint_continue.md`:

```markdown
候选人上一题回答中规中矩，基本正确但不够深入。请围绕同一个话题换一个角度提问，考察其知识面的广度。不要重复上一题的问法。
```

`src/main/resources/prompts/hint_lower.md`:

```markdown
候选人上一题回答得不好，可能当前难度偏高。请降低难度，回到该话题更基础、更常见的知识点上提问，帮助候选人找回节奏。
```

`src/main/resources/prompts/hint_switch.md`:

```markdown
候选人在上一个话题上已经聊得比较充分了。请换一个新话题继续面试，新话题是：{topic}。请围绕这个新话题提问。
```

`src/main/resources/prompts/evaluate.md`:

```markdown
你是一位资深技术面试官，请对候选人本题的回答做出评估。

岗位：{position}
技术方向：{domain}
话题：{topic}
本题难度：{difficulty}

问题：
{question}

候选人的回答：
{answer}

请严格按以下 JSON 格式输出评估结果，不要输出任何其他内容（不要用 markdown 代码块包裹）：

{
  "overall": 7.5,
  "dimensions": {
    "accuracy": 8.0,
    "depth": 7.0,
    "clarity": 8.0,
    "practice": 6.0,
    "problemSolving": 7.5
  },
  "coveredTopics": ["JVM 内存模型"],
  "comment": "一句话点评，指出亮点与不足",
  "nextAction": "CONTINUE"
}

字段说明：
- overall：总分，0-10 分，保留一位小数
- dimensions：五个维度各 0-10 分
  - accuracy：技术点是否正确，有无硬伤
  - depth：是否讲到原理层面
  - clarity：表达是否清晰有条理
  - practice：是否结合真实项目经验
  - problemSolving：分析问题的思路是否正确
- coveredTopics：本次回答覆盖到的具体知识点
- comment：一句话点评，不超过 80 字
- nextAction：下一步动作，只能取以下四个值之一
  - "DEEPEN"：回答很好（通常 8 分以上），值得往更深里追问
  - "CONTINUE"：回答中等（4-8 分），同话题换个角度继续
  - "LOWER"：回答较差（通常 4 分以下），应该降低难度
  - "SWITCH"：这个话题已经聊透，应该换一个新话题

评分要客观严格，不要给所有回答都打高分。
```

`src/main/resources/prompts/report.md`:

```markdown
你是一位资深技术面试官，刚刚完成了一场面试，请撰写一份综合评估报告。

岗位：{position}
技术方向：{domain}
实际作答：{count} 题
总得分：{total}（满分 {count} × 10）
平均分：{average}
{error}

逐题记录：
{detail}

请输出一份中文综合报告，使用 markdown 格式，包含以下部分：

## 总体评价
（3-4 句话概括本次面试的整体表现）

## 突出亮点
（列出 2-3 个做得好的地方，要具体，引用候选人实际说过的内容）

## 主要短板
（列出 2-3 个明显不足，要具体，不要空泛）

## 改进建议
（针对每个短板给出可执行的改进动作，例如要看什么书、写什么代码、怎么练习表达）

## 结论
（一句话给出是否达到目标岗位要求的判断）

要求：直接、具体、有依据。不要写客套话。
```

- [ ] **Step 3: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/prompt/ src/main/resources/prompts/
git commit -m "feat(prompt): 提示词外置 + {key} 占位符渲染器"
```

---

## Task 16: 面试 DAO

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewDao.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/RecordRow.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/TraceRow.java`

- [ ] **Step 1: 写 `RecordRow.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

/**
 * t_interview_record 一行的扁平形态。
 * 和 InterviewState 分开：前者是「记录元信息 + 引擎快照」，后者是「业务现场」。
 */
public record RecordRow(
        Long id,
        Long userId,
        Long resumeId,
        String position,
        String company,
        String domain,
        String difficulty,
        String status,
        Double totalScore,
        String report,
        String stateJson,
        String cursor,
        long createdAt,
        long updatedAt,
        String resumeSummary) {
}
```

- [ ] **Step 2: 写 `TraceRow.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

public record TraceRow(
        long seq,
        Integer round,
        String nodeName,
        String nodeType,
        String fromNode,
        String toNode,
        Long costMs,
        String status,
        String errorMsg,
        long createdAt) {
}
```

- [ ] **Step 3: 写 `InterviewDao.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class InterviewDao {

    /** 简历正文塞进 prompt 前先截断，长简历又贵又没用 */
    private static final int RESUME_SUMMARY_CHARS = 2000;

    private static final RowMapper<RecordRow> RECORD_MAPPER = (rs, rowNum) -> new RecordRow(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getObject("resume_id") == null ? null : rs.getLong("resume_id"),
            rs.getString("position"),
            rs.getString("company"),
            rs.getString("domain"),
            rs.getString("difficulty"),
            rs.getString("status"),
            rs.getObject("total_score") == null ? null : rs.getDouble("total_score"),
            rs.getString("report"),
            rs.getString("state_json"),
            rs.getString("cursor"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getString("resume_summary"));

    private final JdbcTemplate jdbc;

    public InterviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ────────────────────────── 面试记录 ──────────────────────────

    public long insertRecord(Long userId, Long resumeId, String position, String company,
                             String domain, String difficulty) {
        long now = System.currentTimeMillis();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_interview_record
                        (user_id, resume_id, position, company, domain, difficulty,
                         status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'in_progress', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            if (resumeId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, resumeId);
            }
            ps.setString(3, position);
            ps.setString(4, company);
            ps.setString(5, domain);
            ps.setString(6, difficulty);
            ps.setLong(7, now);
            ps.setLong(8, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入面试记录后没有拿到自增主键");
        }
        return key.longValue();
    }

    /** 连表带出简历摘要，供 StartNode 塞进 state */
    public Optional<RecordRow> findRecord(Long id) {
        return jdbc.query("""
                SELECT r.*, substr(coalesce(s.content, ''), 1, ?) AS resume_summary
                FROM t_interview_record r
                LEFT JOIN t_resume s ON s.id = r.resume_id
                WHERE r.id = ?
                """, RECORD_MAPPER, RESUME_SUMMARY_CHARS, id).stream().findFirst();
    }

    /** 每次跑完图都要落库：state_json 是断点续传的全部依据 */
    public void saveState(Long id, String stateJson, String cursor) {
        jdbc.update("UPDATE t_interview_record SET state_json = ?, cursor = ?, updated_at = ? WHERE id = ?",
                stateJson, cursor, System.currentTimeMillis(), id);
    }

    public void finishRecord(Long id, double totalScore, String report) {
        jdbc.update("""
                UPDATE t_interview_record
                SET status = 'finished', total_score = ?, report = ?, updated_at = ?
                WHERE id = ?
                """, totalScore, report, System.currentTimeMillis(), id);
    }

    public List<RecordRow> listByUser(Long userId, int limit, int offset) {
        return jdbc.query("""
                SELECT r.*, '' AS resume_summary
                FROM t_interview_record r
                WHERE r.user_id = ?
                ORDER BY r.created_at DESC
                LIMIT ? OFFSET ?
                """, RECORD_MAPPER, userId, limit, offset);
    }

    public int countByUser(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_interview_record WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    // ────────────────────────── 逐题对话 ──────────────────────────

    /**
     * INSERT OR REPLACE 配合 (record_id, seq) 唯一索引：
     * evaluate 节点因 LLM 失败被重跑时覆盖旧行，不会写出两条。
     */
    public void upsertDialogue(Dialogue d, Long recordId) {
        jdbc.update("""
                INSERT OR REPLACE INTO t_interview_dialogue
                    (record_id, seq, topic, difficulty, question, answer, score,
                     eval_json, next_action, next_topic, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                recordId, d.getSeq(), d.getTopic(), d.getDifficulty(), d.getQuestion(),
                d.getAnswer(), d.getScore(),
                com.ke.nhservice.aimianshi.common.util.JsonUtil.toJson(d.getDimensions()),
                d.getNextAction(), d.getNextTopic(), System.currentTimeMillis());
    }

    public List<Dialogue> listDialogues(Long recordId) {
        return jdbc.query("""
                SELECT seq, topic, difficulty, question, answer, score, eval_json, next_action, next_topic
                FROM t_interview_dialogue WHERE record_id = ? ORDER BY seq
                """, (rs, rowNum) -> {
            Dialogue d = new Dialogue();
            d.setSeq(rs.getInt("seq"));
            d.setTopic(rs.getString("topic"));
            d.setDifficulty(rs.getString("difficulty"));
            d.setQuestion(rs.getString("question"));
            d.setAnswer(rs.getString("answer"));
            d.setScore(rs.getObject("score") == null ? null : rs.getDouble("score"));
            d.setNextAction(rs.getString("next_action"));
            d.setNextTopic(rs.getString("next_topic"));
            String evalJson = rs.getString("eval_json");
            if (evalJson != null && !evalJson.isBlank()) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Double> dims =
                        com.ke.nhservice.aimianshi.common.util.JsonUtil.fromJson(
                                evalJson, java.util.Map.class);
                d.setDimensions(dims);
            }
            return d;
        }, recordId);
    }

    // ────────────────────────── 图执行轨迹 ──────────────────────────

    public void insertTrace(Long recordId, Integer round, String nodeName, String nodeType,
                            String fromNode, String toNode, long costMs, String status, String errorMsg) {
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM t_graph_trace WHERE record_id = ?",
                Integer.class, recordId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        jdbc.update("""
                INSERT INTO t_graph_trace
                    (record_id, seq, round, node_name, node_type, from_node, to_node,
                     cost_ms, status, error_msg, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, recordId, seq, round, nodeName, nodeType, fromNode, toNode,
                costMs, status, errorMsg, System.currentTimeMillis());
    }

    public List<TraceRow> listTraces(Long recordId) {
        return jdbc.query("""
                SELECT seq, round, node_name, node_type, from_node, to_node,
                       cost_ms, status, error_msg, created_at
                FROM t_graph_trace WHERE record_id = ? ORDER BY seq
                """, (rs, rowNum) -> new TraceRow(
                rs.getLong("seq"),
                rs.getObject("round") == null ? null : rs.getInt("round"),
                rs.getString("node_name"),
                rs.getString("node_type"),
                rs.getString("from_node"),
                rs.getString("to_node"),
                rs.getObject("cost_ms") == null ? null : rs.getLong("cost_ms"),
                rs.getString("status"),
                rs.getString("error_msg"),
                rs.getLong("created_at")), recordId);
    }
}
```

- [ ] **Step 4: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/
git commit -m "feat(interview): 面试记录/逐题对话/图轨迹 三张表的 DAO"
```

---

## 节点依赖注入的统一约定

从 Task 17 开始的所有节点都遵循同一条约定：

- **构造器不接服务依赖**，节点从 `NodeContext` 按类型取（`ctx.get(LlmClient.class)`）
- 唯一例外是 `SetHintNode`——它接的是「行为参数」（提示词构造 lambda + 难度增减），不是服务

这样做的好处：`InterviewGraphFactory` 里 `addNode("question", new QuestionNode())` 这种写法一眼能看出图的形状，服务装配全在 `NodeContext` 一处完成。同时 `graph/` 包依然不认识任何业务类型。

`NodeContext` 在 `InterviewEngine` 里一次性装配：

```java
NodeContext context = new NodeContext()
        .put(LlmClient.class, llmClient)
        .put(InterviewDao.class, interviewDao)
        .put(PromptLoader.class, promptLoader)
        .put(InterviewProperties.class, props);
```

---

## Task 17: 节点 —— start / question / wait_answer

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/StartNode.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/WaitAnswerNode.java`

- [ ] **Step 1: 写 `StartNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.TopicTracker;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

import java.util.List;

/**
 * 初始化：题号归 1，把会话配置和话题池灌进 state。
 * 只跑一次——游标离开 start 之后就不会再回来。
 */
public class StartNode implements Node<InterviewState> {

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        InterviewProperties props = ctx.get(InterviewProperties.class);
        InterviewDao dao = ctx.get(InterviewDao.class);

        RecordRow row = dao.findRecord(state.getRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "面试记录不存在: " + state.getRecordId()));

        state.setQuestionIndex(1);
        state.setMaxQuestions(props.getMaxQuestions());
        state.setPosition(row.position());
        state.setCompany(row.company());
        state.setDomain(row.domain());
        state.setCurrentDifficulty(Difficulty.fromLabel(row.difficulty()));
        state.setResumeSummary(row.resumeSummary() == null ? "" : row.resumeSummary());
        state.setShouldStop(false);
        state.setError(null);

        List<String> topics = props.topicsOf(row.domain());
        TopicTracker tracker = new TopicTracker();
        tracker.setAllTopics(topics);
        // 第一题就用话题池里的第一个，currentTopic 不能为 null
        tracker.setCurrentTopic(tracker.suggestNextTopic());
        state.setTopicTracker(tracker);

        return NodeResult.NEXT;
    }
}
```

- [ ] **Step 2: 写 `QuestionNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.HistoryItem;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 出题。拼 prompt → 调 LLM → 写 state.questionText。
 *
 * 失败策略：LLM 重试耗尽后异常直接往上抛，引擎转成 FAILED，游标停在 question。
 * 这不是「面试挂了」——state_json 已落库，用户刷新页面点继续就能重跑这一节点。
 * 只有 LLM 返回空内容这种「调用成功但结果不可用」才走 shouldStop 收尾。
 */
public class QuestionNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(QuestionNode.class);

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        LlmClient llm = ctx.get(LlmClient.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);

        boolean firstQuestion = state.getDialogues().isEmpty() && state.getQuestionIndex() <= 1;
        String template = firstQuestion ? "question_first" : "question_followup";

        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("company", orEmpty(state.getCompany()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("difficulty", state.getCurrentDifficulty().getLabel());
        vars.put("topic", orEmpty(state.getTopicTracker().getCurrentTopic()));
        vars.put("resume", orEmpty(state.getResumeSummary()));
        vars.put("history", renderHistory(state));
        vars.put("nextActionHint", orEmpty(state.getNextActionHint()));
        vars.put("questionIndex", String.valueOf(state.getQuestionIndex()));
        vars.put("maxQuestions", String.valueOf(state.getMaxQuestions()));

        String question = llm.chat(prompts.render(template, vars)).trim();
        log.debug("第 {} 题生成完成，{} 字符", state.getQuestionIndex(), question.length());

        if (question.isBlank()) {
            state.setError("出题失败：模型返回了空内容");
            state.setShouldStop(true);
            return NodeResult.NEXT;
        }

        state.setQuestionText(question);
        // 上一题的东西全部清掉，保证 wait_answer 一定会挂起
        state.setAnswer(null);
        state.setEvalResult(null);
        state.setNextActionHint(null);
        return NodeResult.NEXT;
    }

    /** 滑动窗口里的最近 2 题。全带上会让 prompt 随轮次线性膨胀，而 token 是按量计费的 */
    private String renderHistory(InterviewState state) {
        if (state.getRecentHistory().isEmpty()) {
            return "（这是第一个问题，暂无历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (HistoryItem item : state.getRecentHistory()) {
            sb.append("问：").append(item.getQuestion()).append('\n');
            sb.append("答：").append(item.getAnswer()).append('\n');
            sb.append("得分：").append(item.getScore()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 3: 写 `WaitAnswerNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

/**
 * ★ 全图唯一的挂起点。
 *
 * 这个节点不「等」——它看状态决定能不能往下走，不能就返回 Suspend，
 * 引擎存完现场直接退出。请求线程立刻释放，没有线程被占住，也不需要心跳。
 *
 * 天然幂等：判断依据是「answer 是否为空」而不是计数器，重复执行结果一样。
 */
public class WaitAnswerNode implements Node<InterviewState> {

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        // 出题已经失败了，别再等答案，直接往下走让 evaluate 跳过、分支路由到 end 收尾
        if (state.isShouldStop()) {
            return NodeResult.NEXT;
        }
        String answer = state.getAnswer();
        if (answer == null || answer.isBlank()) {
            return NodeResult.SUSPEND;
        }
        return NodeResult.NEXT;
    }
}
```

- [ ] **Step 4: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/
git commit -m "feat(interview): start / question / wait_answer 三个节点"
```

---

## Task 18: 评分 —— 解析器与 evaluate 节点

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResultParser.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/EvaluateNode.java`

- [ ] **Step 1: 写 `EvalResultParser.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ke.nhservice.aimianshi.common.constant.NextAction;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 LLM 返回的评分 JSON 解析成 EvalResult。
 *
 * LLM 输出的不可控之处都要在这里兜住：
 * - 可能包在 ```json ``` 代码块里 → extractJson 剥围栏
 * - 前面可能带一句「好的，评估如下：」→ 截取第一个 { 到最后一个 }
 * - 分数可能超出 0-10 → clamp
 * - nextAction 可能拼错或漏给 → 按分数兜底（NextAction.from）
 * - 五个维度可能缺几个 → 缺的补 0
 */
public final class EvalResultParser {

    private EvalResultParser() {
    }

    public static EvalResult parse(String raw) {
        LlmEvalDto dto = JsonUtil.fromJson(extractJson(raw), LlmEvalDto.class);

        EvalResult result = new EvalResult();
        result.setOverall(clamp(dto.overall()));
        result.setDimensions(normalizeDimensions(dto.dimensions()));
        result.setCoveredTopics(dto.coveredTopics() == null ? List.of() : dto.coveredTopics());
        result.setComment(dto.comment() == null ? "" : dto.comment());
        result.setNextAction(NextAction.from(dto.nextAction(), result.getOverall()));
        return result;
    }

    /** 截取第一个 { 到最后一个 }，顺带剥掉 markdown 围栏和前后的解释文字 */
    static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException(
                    "模型返回中找不到 JSON 对象: " + JsonUtil.abbreviate(raw));
        }
        return raw.substring(start, end + 1);
    }

    private static double clamp(Double value) {
        if (value == null || value.isNaN()) {
            return 0;
        }
        return Math.max(0, Math.min(10, value));
    }

    /** 永远返回完整的五个维度，顺序固定——前端雷达图直接按顺序画 */
    private static Map<String, Double> normalizeDimensions(Map<String, Double> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : EvalResult.DIMENSIONS) {
            out.put(key, clamp(raw == null ? null : raw.get(key)));
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LlmEvalDto(Double overall,
                      Map<String, Double> dimensions,
                      List<String> coveredTopics,
                      String comment,
                      String nextAction) {
    }
}
```

- [ ] **Step 2: 写 `EvaluateNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.EvalResultParser;
import com.ke.nhservice.aimianshi.biz.interview.HistoryItem;
import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.TopicTracker;
import com.ke.nhservice.aimianshi.biz.interview.flow.InterviewRouting;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.NextAction;
import com.ke.nhservice.aimianshi.common.constant.RecordStatus;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 评分。这是整张图信息最密集的节点，做了四件事：
 *
 * 1. 调 LLM 评分（失败则降级为 CONTINUE，不中断面试）
 * 2. 话题追踪（覆盖度、连续追问计数、必要时强制换话题）
 * 3. 落库 t_interview_dialogue（含图的分支决策）
 * 4. 清掉已消费的 answer，把本题推入滑动窗口
 *
 * 关于落库的 next_action：用的是 InterviewRouting.decide() —— 和紧接着执行的分支
 * 判断是同一个纯函数。此时 state 还没被分支节点改动，所以算出来的结果和分支
 * 实际走的路由必然一致，不会出现「DB 记着 deepen、实际走了 switch」。
 */
public class EvaluateNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(EvaluateNode.class);

    /** 滑动窗口保留的题数 */
    private static final int HISTORY_WINDOW = 2;

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        // 出题失败或用户已点结束，跳过评分直接收尾
        if (state.isShouldStop()) {
            return NodeResult.NEXT;
        }

        LlmClient llm = ctx.get(LlmClient.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        InterviewDao dao = ctx.get(InterviewDao.class);
        InterviewProperties props = ctx.get(InterviewProperties.class);

        String question = state.getQuestionText();
        String answer = state.getAnswer();
        TopicTracker tracker = state.getTopicTracker();

        EvalResult result = evaluate(llm, prompts, state, question, answer);

        // 同一个话题追问太多次就强制换话题，不完全交给 LLM 判断
        if (tracker.getFollowUpCount() >= props.getMaxFollowUp()) {
            log.debug("话题「{}」已连续追问 {} 次，强制换话题",
                    tracker.getCurrentTopic(), tracker.getFollowUpCount());
            result.setNextAction(NextAction.SWITCH);
        }
        state.setEvalResult(result);

        String routing = InterviewRouting.decide(state);
        state.setLastRouting(routing);

        String nextTopic = "switch".equals(routing) ? tracker.suggestNextTopic() : null;

        Dialogue dialogue = new Dialogue();
        dialogue.setSeq(state.getQuestionIndex());
        dialogue.setTopic(tracker.getCurrentTopic());
        dialogue.setDifficulty(state.getCurrentDifficulty().getLabel());
        dialogue.setQuestion(question);
        dialogue.setAnswer(answer);
        dialogue.setScore(result.getOverall());
        dialogue.setDimensions(result.getDimensions());
        dialogue.setComment(result.getComment());
        // 落库时把 end_loop 写成 "end"，复盘表格里读起来更自然
        dialogue.setNextAction(InterviewRouting.END.equals(routing)
                ? RecordStatus.NEXT_ACTION_END : routing);
        dialogue.setNextTopic(nextTopic);
        dao.upsertDialogue(dialogue, state.getRecordId());

        state.getDialogues().add(dialogue);
        state.getScoreHistory().add(result.getOverall());
        state.pushHistory(new HistoryItem(question, answer, result.getOverall()), HISTORY_WINDOW);
        tracker.markCovered(tracker.getCurrentTopic());
        tracker.setFollowUpCount(result.getNextAction() == NextAction.DEEPEN
                ? tracker.getFollowUpCount() + 1 : 0);

        // 答案已消费，清掉。这样下一轮回到 wait_answer 才会正确挂起
        state.setAnswer(null);
        return NodeResult.NEXT;
    }

    private EvalResult evaluate(LlmClient llm, PromptLoader prompts, InterviewState state,
                                String question, String answer) {
        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("topic", orEmpty(state.getTopicTracker().getCurrentTopic()));
        vars.put("difficulty", state.getCurrentDifficulty().getLabel());
        vars.put("question", orEmpty(question));
        vars.put("answer", orEmpty(answer));

        try {
            return EvalResultParser.parse(llm.chat(prompts.render("evaluate", vars)));
        } catch (Exception e) {
            // 评分失败不中断面试：本题按 0 分记、下一步按 CONTINUE 走，
            // 候选人还能继续答后面的题，已经答过的几题也都保住了
            log.warn("第 {} 题评分失败，降级为 CONTINUE: {}", state.getQuestionIndex(), e.getMessage());
            return EvalResult.degraded(e.getMessage());
        }
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 3: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/
git commit -m "feat(interview): 评分解析器（容错）与 evaluate 节点"
```

---

## Task 19: 节点 —— SetHintNode 与 end

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/SetHintNode.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/EndNode.java`

- [ ] **Step 1: 写 `SetHintNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;

import java.util.function.Function;

/**
 * deepen / continue / lower / switch 四个分支节点合并成这一个类。
 *
 * 参考项目（Go 版）这四个节点是四个几乎重复的函数，约 80 行；
 * 这里靠「提示词构造 lambda + 难度增减量」两个参数合并，约 20 行。
 * 将来加新分支只需要一行 addNode + 一个提示词文件。
 */
public class SetHintNode implements Node<InterviewState> {

    private final Function<InterviewState, String> hintBuilder;

    /** +1 升档 / -1 降档 / 0 不变。Difficulty.shift 会自动夹紧边界 */
    private final int difficultyDelta;

    public SetHintNode(Function<InterviewState, String> hintBuilder) {
        this(hintBuilder, 0);
    }

    public SetHintNode(Function<InterviewState, String> hintBuilder, int difficultyDelta) {
        this.hintBuilder = hintBuilder;
        this.difficultyDelta = difficultyDelta;
    }

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        state.setNextActionHint(hintBuilder.apply(state));
        if (difficultyDelta != 0) {
            state.setCurrentDifficulty(
                    Difficulty.shift(state.getCurrentDifficulty(), difficultyDelta));
        }
        // 题号在这里递增。所以 EvaluateBranch 里「questionIndex >= maxQuestions」判断的是
        // 「刚答完的那题」，不是下一题——顺序不能颠倒。
        state.setQuestionIndex(state.getQuestionIndex() + 1);
        return NodeResult.NEXT;
    }
}
```

- [ ] **Step 2: 写 `EndNode.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.node;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.graph.Node;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 收尾：生成综合报告，把记录标成 finished。
 *
 * 这个节点是图的终点，执行完引擎返回 FINISHED。
 * 报告生成失败也要落库一个降级版本——不然用户会看到一场「结束了但没有报告」的面试。
 */
public class EndNode implements Node<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(EndNode.class);

    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        InterviewDao dao = ctx.get(InterviewDao.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        LlmClient llm = ctx.get(LlmClient.class);

        double total = state.getScoreHistory().total();
        double average = state.getScoreHistory().average();
        int count = state.getScoreHistory().getScores().size();

        Map<String, String> vars = new HashMap<>();
        vars.put("position", orEmpty(state.getPosition()));
        vars.put("domain", orEmpty(state.getDomain()));
        vars.put("count", String.valueOf(count));
        vars.put("total", String.format("%.1f", total));
        vars.put("average", String.format("%.1f", average));
        vars.put("detail", renderDetail(state));
        vars.put("error", state.getError() == null || state.getError().isBlank()
                ? "" : "\n注意：本次面试因故提前结束，原因：" + state.getError() + "\n");

        String report;
        try {
            report = llm.chat(prompts.render("report", vars));
        } catch (Exception e) {
            log.warn("生成综合报告失败，落库降级版本: {}", e.getMessage());
            report = "## 综合报告生成失败\n\n原因：" + e.getMessage()
                    + "\n\n以下是本次面试的原始记录：\n\n" + renderDetail(state);
        }

        state.setReport(report);
        state.setTotalScore(average);
        dao.finishRecord(state.getRecordId(), average, report);

        log.info("面试 {} 结束：{} 题，平均分 {}", state.getRecordId(), count,
                String.format("%.1f", average));
        return NodeResult.NEXT;
    }

    private String renderDetail(InterviewState state) {
        if (state.getDialogues().isEmpty()) {
            return "（本次面试没有产生有效问答记录）";
        }
        StringBuilder sb = new StringBuilder();
        for (Dialogue d : state.getDialogues()) {
            sb.append("第 ").append(d.getSeq()).append(" 题");
            if (d.getTopic() != null) {
                sb.append("（话题：").append(d.getTopic())
                        .append("，难度：").append(d.getDifficulty()).append("）");
            }
            sb.append('\n');
            sb.append("问：").append(d.getQuestion()).append('\n');
            sb.append("答：").append(d.getAnswer()).append('\n');
            sb.append("得分：").append(d.getScore()).append('\n');
            if (d.getComment() != null && !d.getComment().isBlank()) {
                sb.append("评语：").append(d.getComment()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 3: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/
git commit -m "feat(interview): 分支节点合并为 SetHintNode、end 收尾节点"
```

---

## Task 20: 流程图定义

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/flow/InterviewRouting.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/flow/EvaluateBranch.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/flow/InterviewGraphFactory.java`

- [ ] **Step 1: 写 `InterviewRouting.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;

/**
 * ★ 全图唯一的决策点，纯函数（不碰数据库、不调 LLM）。
 *
 * 抽成独立静态函数而不是写在 EvaluateBranch 里，是因为 EvaluateNode 落库
 * next_action 时要用同一套判断。两处各写一份，迟早会出现「数据库记录的分支
 * 和实际走的分支不一致」。
 *
 * 题数检查放在这里（evaluate 之后）而不是 question 之前：
 * 此时 questionIndex 恰好是刚答完那题的编号，而 deepen 等分支节点只负责 +1、
 * 不设 shouldStop，所以回到 question 时无需再判断——不存在 off-by-one。
 */
public final class InterviewRouting {

    public static final String END = "end_loop";
    public static final String DEEPEN = "deepen";
    public static final String CONTINUE = "continue";
    public static final String LOWER = "lower";
    public static final String SWITCH = "switch";

    private InterviewRouting() {
    }

    public static String decide(InterviewState state) {
        if (state.isShouldStop()) {
            return END;
        }
        if (state.getQuestionIndex() >= state.getMaxQuestions()) {
            return END;
        }
        EvalResult result = state.getEvalResult();
        if (result == null || result.getNextAction() == null) {
            return CONTINUE;
        }
        return switch (result.getNextAction()) {
            case DEEPEN -> DEEPEN;
            case LOWER -> LOWER;
            case SWITCH -> SWITCH;
            case CONTINUE -> CONTINUE;
        };
    }
}
```

- [ ] **Step 2: 写 `EvaluateBranch.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.BranchCondition;

/**
 * 薄适配器：把 InterviewRouting 的决策函数接进图引擎的 BranchCondition 接口。
 * 图引擎不允许认识 InterviewState 之外的东西，所以这层转发是必要的。
 */
public class EvaluateBranch implements BranchCondition<InterviewState> {

    @Override
    public String decide(InterviewState state) {
        return InterviewRouting.decide(state);
    }
}
```

- [ ] **Step 3: 写 `InterviewGraphFactory.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.flow;

import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.node.EndNode;
import com.ke.nhservice.aimianshi.biz.interview.node.EvaluateNode;
import com.ke.nhservice.aimianshi.biz.interview.node.QuestionNode;
import com.ke.nhservice.aimianshi.biz.interview.node.SetHintNode;
import com.ke.nhservice.aimianshi.biz.interview.node.StartNode;
import com.ke.nhservice.aimianshi.biz.interview.node.WaitAnswerNode;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.graph.CompiledGraph;
import com.ke.nhservice.aimianshi.graph.Graph;
import com.ke.nhservice.aimianshi.graph.GraphListener;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 面试流程图。形状：
 *
 * <pre>
 * START
 *   │
 *   ▼
 * start ──────── 初始化：questionIndex = 1，灌入简历摘要与话题池
 *   │
 *   ▼
 * question ───── 调 LLM 出题（prompt = nextActionHint + 简历 + 最近 2 题历史）
 *   │
 *   ▼
 * wait_answer ── ★ 全图唯一挂起点
 *   │
 *   ▼
 * evaluate ───── 调 LLM 评分 → EvalResult（含 nextAction）
 *   │
 *   ├──[ EvaluateBranch ]──┬──→ deepen   ┐
 *   │                      ├──→ continue │
 *   │                      ├──→ lower    ├──→ question   ← 普通边，形成循环
 *   │                      ├──→ switch   ┘
 *   │                      └──→ end_loop ──→ END
 * </pre>
 *
 * 构建出来的 CompiledGraph 不可变、节点无状态，所以可以放心地做成单例复用。
 */
@Component
public class InterviewGraphFactory {

    public static final String NODE_START = "start";
    public static final String NODE_QUESTION = "question";
    public static final String NODE_WAIT_ANSWER = "wait_answer";
    public static final String NODE_EVALUATE = "evaluate";
    public static final String NODE_DEEPEN = InterviewRouting.DEEPEN;
    public static final String NODE_CONTINUE = InterviewRouting.CONTINUE;
    public static final String NODE_LOWER = InterviewRouting.LOWER;
    public static final String NODE_SWITCH = InterviewRouting.SWITCH;
    public static final String NODE_END = InterviewRouting.END;

    private final PromptLoader prompts;
    private final InterviewProperties props;

    public InterviewGraphFactory(PromptLoader prompts, InterviewProperties props) {
        this.prompts = prompts;
        this.props = props;
    }

    public CompiledGraph<InterviewState> build(NodeContext context,
                                               GraphListener<InterviewState> listener) {
        Graph<InterviewState> graph = new Graph<>();
        graph.context(context);

        graph.addNode(NODE_START, new StartNode());
        graph.addNode(NODE_QUESTION, new QuestionNode());
        graph.addNode(NODE_WAIT_ANSWER, new WaitAnswerNode());
        graph.addNode(NODE_EVALUATE, new EvaluateNode());

        graph.addNode(NODE_DEEPEN, new SetHintNode(state -> prompts.load("hint_deepen"), +1));
        graph.addNode(NODE_CONTINUE, new SetHintNode(state -> prompts.load("hint_continue"), 0));
        graph.addNode(NODE_LOWER, new SetHintNode(state -> prompts.load("hint_lower"), -1));
        graph.addNode(NODE_SWITCH, new SetHintNode(state -> {
            // 换话题的动作在这里做：TopicTracker 选出没聊过的话题并设为当前话题，
            // 出题节点下一轮就会围绕它提问
            String topic = state.getTopicTracker().suggestNextTopic();
            state.getTopicTracker().setCurrentTopic(topic);
            return prompts.render("hint_switch", Map.of("topic", topic == null ? "" : topic));
        }, 0));

        graph.addNode(NODE_END, new EndNode());

        graph.startAt(NODE_START);
        graph.addEdge(NODE_START, NODE_QUESTION);
        graph.addEdge(NODE_QUESTION, NODE_WAIT_ANSWER);
        graph.addEdge(NODE_WAIT_ANSWER, NODE_EVALUATE);
        graph.addBranch(NODE_EVALUATE, new EvaluateBranch(),
                Set.of(NODE_DEEPEN, NODE_CONTINUE, NODE_LOWER, NODE_SWITCH, NODE_END));
        graph.addEdge(NODE_DEEPEN, NODE_QUESTION);
        graph.addEdge(NODE_CONTINUE, NODE_QUESTION);
        graph.addEdge(NODE_LOWER, NODE_QUESTION);
        graph.addEdge(NODE_SWITCH, NODE_QUESTION);
        graph.endAt(NODE_END);

        if (listener != null) {
            graph.listener(listener);
        }
        return graph.compile(props.getMaxSteps());
    }
}
```

- [ ] **Step 4: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/flow/
git commit -m "feat(interview): 流程图定义（唯一决策点 + 四分支合并）"
```

---

## Task 21: 轨迹记录器

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/trace/TraceRecorder.java`

- [ ] **Step 1: 写 `TraceRecorder.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview.trace;

import com.ke.nhservice.aimianshi.biz.interview.InterviewDao;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.graph.GraphListener;
import com.ke.nhservice.aimianshi.graph.NodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把图的执行轨迹写进 t_graph_trace。
 *
 * 这是「图引擎不依赖业务」这条约束的验证点：引擎只管在关键时刻回调，
 * 记录什么、往哪记，全在这个业务侧的实现里。
 *
 * 每个节点执行写一行（node_type = normal / suspend），每次分支决策再写一行
 * （node_type = branch，to_node 是决策结果）。所以 evaluate 那一轮会有两行：
 * 一行是节点本身，一行是它做出的分支决策。
 */
@Component
public class TraceRecorder implements GraphListener<InterviewState> {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final InterviewDao dao;

    public TraceRecorder(InterviewDao dao) {
        this.dao = dao;
    }

    @Override
    public void onNodeExit(String node, NodeResult result, long costMs, InterviewState state) {
        if (state.getRecordId() == null) {
            return;
        }
        String nodeType;
        String status;
        if (result == null) {
            nodeType = "normal";
            status = "error";
        } else if (result instanceof NodeResult.Suspend) {
            nodeType = "suspend";
            status = "suspend";
        } else {
            nodeType = "normal";
            status = "ok";
        }
        write(state, node, nodeType, null, null, costMs, status, null);
    }

    @Override
    public void onBranchDecided(String from, String decided, InterviewState state) {
        if (state.getRecordId() == null) {
            return;
        }
        write(state, from, "branch", from, decided, 0L, "ok", null);
    }

    /** 轨迹写失败不能影响面试本身——它只是观察者 */
    private void write(InterviewState state, String nodeName, String nodeType,
                       String fromNode, String toNode, long costMs, String status, String errorMsg) {
        try {
            dao.insertTrace(state.getRecordId(), state.getQuestionIndex(), nodeName, nodeType,
                    fromNode, toNode, costMs, status, errorMsg);
        } catch (Exception e) {
            log.warn("写图执行轨迹失败（不影响面试）: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 编译并提交**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/trace/
git commit -m "feat(interview): GraphListener 落库实现（节点轨迹 + 分支决策）"
```

---

## Task 22: 面试引擎

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java`

- [ ] **Step 1: 写 `InterviewEngine.java`**

```java
package com.ke.nhservice.aimianshi.biz.interview;

import com.ke.nhservice.aimianshi.biz.interview.flow.InterviewGraphFactory;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.biz.interview.trace.TraceRecorder;
import com.ke.nhservice.aimianshi.common.config.InterviewProperties;
import com.ke.nhservice.aimianshi.common.constant.Difficulty;
import com.ke.nhservice.aimianshi.common.constant.RecordStatus;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;
import com.ke.nhservice.aimianshi.graph.CompiledGraph;
import com.ke.nhservice.aimianshi.graph.Execution;
import com.ke.nhservice.aimianshi.graph.NodeContext;
import com.ke.nhservice.aimianshi.graph.RunResult;
import com.ke.nhservice.aimianshi.graph.RunStatus;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 面试引擎：把「HTTP 请求」翻译成「跑图 + 落库」。
 *
 * 四个入口（start / answer / finish / resume）做的是同一件事的不同变体：
 * 载入现场 → 改一下 state 或 cursor → 跑图 → 存回现场。
 *
 * 「继续面试」不需要专门的恢复逻辑——现场全在 state_json + cursor 里，
 * 读一条记录就能接着跑。
 */
@Service
public class InterviewEngine {

    private static final Logger log = LoggerFactory.getLogger(InterviewEngine.class);

    private final InterviewDao dao;
    private final InterviewGraphFactory graphFactory;
    private final TraceRecorder traceRecorder;
    private final LlmClient llmClient;
    private final PromptLoader promptLoader;
    private final InterviewProperties props;
    private final NodeContext nodeContext = new NodeContext();

    /** 图不可变、节点无状态，编译一次全局复用 */
    private CompiledGraph<InterviewState> graph;

    public InterviewEngine(InterviewDao dao,
                           InterviewGraphFactory graphFactory,
                           TraceRecorder traceRecorder,
                           LlmClient llmClient,
                           PromptLoader promptLoader,
                           InterviewProperties props) {
        this.dao = dao;
        this.graphFactory = graphFactory;
        this.traceRecorder = traceRecorder;
        this.llmClient = llmClient;
        this.promptLoader = promptLoader;
        this.props = props;
    }

    @PostConstruct
    void init() {
        nodeContext.put(LlmClient.class, llmClient)
                .put(InterviewDao.class, dao)
                .put(PromptLoader.class, promptLoader)
                .put(InterviewProperties.class, props);
        this.graph = graphFactory.build(nodeContext, traceRecorder);
        log.info("面试图已编译：起始节点 {}，结束节点 {}，最大步数 {}",
                graph.getStart(), graph.getEnd(), props.getMaxSteps());
    }

    // ────────────────────────── 四个入口 ──────────────────────────

    /** 新建一场面试，跑到第一个问题挂起为止 */
    public RunResult<InterviewState> start(Long userId, Long resumeId, String position,
                                           String company, String domain, String difficulty) {
        long recordId = dao.insertRecord(userId, resumeId, position, company, domain, difficulty);

        InterviewState state = new InterviewState();
        state.setRecordId(recordId);
        state.setSessionId(UUID.randomUUID().toString());
        state.setMaxQuestions(props.getMaxQuestions());
        state.setCurrentDifficulty(Difficulty.fromLabel(difficulty));

        log.info("用户 {} 开始面试 {}（{} / {} / {}）", userId, recordId, position, domain, difficulty);
        // cursor 传 null，引擎会从 start 节点开始
        return run(state, null);
    }

    /** 提交答案，跑到下一个问题挂起为止 */
    public RunResult<InterviewState> submitAnswer(Long userId, Long recordId, String answer) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            throw new BizException("这场面试已经结束了");
        }
        if (answer == null || answer.isBlank()) {
            throw new BizException("答案不能为空");
        }
        loaded.state().setAnswer(answer.trim());
        loaded.state().setShouldStop(false);
        return run(loaded.state(), loaded.cursor());
    }

    /**
     * 用户主动结束。直接把游标跳到结束节点——
     * 这是「Execution.cursor 存在库中且外部可改」的直接应用，
     * 不需要为「中途结束」在 NodeResult 里加第三种指令。
     */
    public RunResult<InterviewState> finish(Long userId, Long recordId) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            return RunResult.finished(InterviewGraphFactory.NODE_END, loaded.state());
        }
        loaded.state().setShouldStop(true);
        return run(loaded.state(), InterviewGraphFactory.NODE_END);
    }

    /** 从上次的游标继续跑。刷新页面、上次 LLM 失败、关掉浏览器再回来，都走这里 */
    public RunResult<InterviewState> resume(Long userId, Long recordId) {
        Loaded loaded = loadOwned(userId, recordId);
        if (loaded.isFinished()) {
            return RunResult.finished(InterviewGraphFactory.NODE_END, loaded.state());
        }
        return run(loaded.state(), loaded.cursor());
    }

    /** 只读当前状态，不推进图 */
    public InterviewState loadState(Long userId, Long recordId) {
        return loadOwned(userId, recordId).state();
    }

    // ────────────────────────── 内部 ──────────────────────────

    private RunResult<InterviewState> run(InterviewState state, String cursor) {
        Execution<InterviewState> execution = new Execution<>(state, cursor);
        RunResult<InterviewState> result = graph.run(execution);

        // 无论成功、挂起还是失败，现场都必须落库。
        // 这一步是「已答的题不会丢」的全部保障。
        persist(state, execution.getCursor(), result);
        return result;
    }

    private void persist(InterviewState state, String cursor, RunResult<InterviewState> result) {
        dao.saveState(state.getRecordId(), JsonUtil.toJson(state), cursor);
        switch (result.status()) {
            case FAILED -> log.error("面试 {} 在节点 {} 执行失败，游标已保留，可重新唤起继续",
                    state.getRecordId(), result.stoppedAt(), result.error());
            case STEP_LIMIT -> log.error("面试 {} 超过最大步数被中断，停在节点 {}，可能是图配置有环",
                    state.getRecordId(), result.stoppedAt());
            default -> {
            }
        }
    }

    /**
     * 载入现场。注意 cursor 为 null 是合法情况——
     * 那表示图停在起始节点之前，引擎会自动从 start 开始。
     */
    private Loaded loadOwned(Long userId, Long recordId) {
        RecordRow row = dao.findRecord(recordId)
                .orElseThrow(() -> BizException.notFound("面试记录不存在"));

        if (!row.userId().equals(userId)) {
            // 不区分「不存在」和「不是你的」，避免探测别人的记录 id
            throw BizException.notFound("面试记录不存在");
        }

        InterviewState state;
        if (row.stateJson() == null || row.stateJson().isBlank()) {
            state = new InterviewState();
            state.setRecordId(recordId);
            state.setMaxQuestions(props.getMaxQuestions());
        } else {
            state = JsonUtil.fromJson(row.stateJson(), InterviewState.class);
            // 快照里的 recordId 理论上一定有，兜一下防止旧数据缺字段
            state.setRecordId(recordId);
        }

        if (state.getTopicTracker() == null) {
            state.setTopicTracker(new TopicTracker());
        }
        if (state.getScoreHistory() == null) {
            state.setScoreHistory(new ScoreHistory());
        }
        if (state.getRecentHistory() == null) {
            state.setRecentHistory(new java.util.ArrayList<>());
        }
        if (state.getDialogues() == null) {
            state.setDialogues(new java.util.ArrayList<>());
        }

        return new Loaded(row, row.cursor(), state);
    }

    private record Loaded(RecordRow row, String cursor, InterviewState state) {
        boolean isFinished() {
            return RecordStatus.FINISHED.getCode().equals(row.status());
        }
    }
}
```

- [ ] **Step 2: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

- [ ] **Step 3: 启动，确认图编译日志**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run
```
Expected 日志里出现：
```
面试图已编译：起始节点 start，结束节点 end_loop，最大步数 200
```
`Ctrl+C` 停止。若出现 `节点 xxx 既没有出边也没有分支` 之类的 GraphException，说明 `InterviewGraphFactory` 的边漏配了。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/
git commit -m "feat(interview): 面试引擎（start/answer/finish/resume 四个入口）"
```

---

## Task 23: 面试接口

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/StartInterviewRequest.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/AnswerRequest.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/InterviewTurnVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/RecordListItemVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/InterviewDetailVO.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/controller/InterviewController.java`

- [ ] **Step 1: 写四个请求/响应 DTO**

`StartInterviewRequest.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record StartInterviewRequest(Long resumeId, String position, String company,
                                    String domain, String difficulty) {
}
```

`AnswerRequest.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record AnswerRequest(String answer) {
}
```

`RecordListItemVO.java`:

```java
package com.ke.nhservice.aimianshi.common.dto;

public record RecordListItemVO(Long id, String position, String company, String domain,
                               String difficulty, String status, Double totalScore,
                               long createdAt, long updatedAt, int dialogueCount) {
}
```

`InterviewDetailVO.java`——里面带上逐题详情和轨迹，复盘页只用这一个接口：

```java
package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;
import java.util.Map;

/**
 * 复盘详情。一期只用 dialogues + report；
 * traces 是一期就采全的数据，二期画「图执行路径可视化」时直接用。
 */
public record InterviewDetailVO(
        Long id,
        String position,
        String company,
        String domain,
        String difficulty,
        String status,
        Double totalScore,
        String report,
        String error,
        long createdAt,
        long updatedAt,
        List<DialogueVO> dialogues,
        List<TraceVO> traces) {

    public record DialogueVO(
            int seq,
            String topic,
            String difficulty,
            String question,
            String answer,
            Double score,
            Map<String, Double> dimensions,
            String comment,
            String nextAction,
            String nextTopic) {
    }

    public record TraceVO(
            long seq,
            Integer round,
            String nodeName,
            String nodeType,
            String fromNode,
            String toNode,
            Long costMs,
            String status) {
    }
}
```

`InterviewTurnVO.java`——start / answer / finish / resume 四个接口共用同一个响应形状：

```java
package com.ke.nhservice.aimianshi.common.dto;

import java.util.Map;

/**
 * 一次「推进面试」的结果。
 *
 * 四个接口返回同一个形状，前端只需要一套渲染逻辑：
 * - questionIndex / total  → 顶部进度「第 3/10 题」
 * - lastScore / lastComment → 上一题的评分反馈
 * - nextAction             → 上一题走的分支（deepen/continue/lower/switch/end）
 * - question               → 当前要回答的问题
 * - finished + report      → 结束了，跳复盘页
 */
public record InterviewTurnVO(
        Long recordId,
        String status,
        boolean finished,
        Integer questionIndex,
        Integer total,
        String question,
        String topic,
        String difficulty,
        Double lastScore,
        Map<String, Double> lastDimensions,
        String lastComment,
        String nextAction,
        Double averageScore,
        String report,
        String error) {
}
```

- [ ] **Step 2: 给 `InterviewEngine` 加三个只读方法**

在 `loadState` 方法之后插入：

```java
    // ────────────────────────── 只读查询 ──────────────────────────

    /** 面试记录列表（分页） */
    public List<RecordRow> list(Long userId, int page, int size) {
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = Math.max(0, (Math.max(1, page) - 1) * safeSize);
        return dao.listByUser(userId, safeSize, offset);
    }

    public int count(Long userId) {
        return dao.countByUser(userId);
    }

    /** 复盘详情。归属校验统一走 loadOwned，不在这里重复写一遍 */
    public RecordRow requireRecord(Long userId, Long recordId) {
        return loadOwned(userId, recordId).row();
    }

    public List<Dialogue> dialogues(Long recordId) {
        return dao.listDialogues(recordId);
    }

    public List<TraceRow> traces(Long recordId) {
        return dao.listTraces(recordId);
    }
```

并在文件顶部 import 区补上：

```java
import java.util.List;
```

- [ ] **Step 3: 写 `InterviewController.java`**

```java
package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewEngine;
import com.ke.nhservice.aimianshi.biz.interview.InterviewState;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.TraceRow;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.AnswerRequest;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.InterviewDetailVO;
import com.ke.nhservice.aimianshi.common.dto.InterviewTurnVO;
import com.ke.nhservice.aimianshi.common.dto.RecordListItemVO;
import com.ke.nhservice.aimianshi.common.dto.StartInterviewRequest;
import com.ke.nhservice.aimianshi.graph.RunResult;
import com.ke.nhservice.aimianshi.graph.RunStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private static final Map<RunStatus, String> STATUS_TEXT = Map.of(
            RunStatus.FINISHED, "面试已结束",
            RunStatus.SUSPENDED, "等待候选人作答",
            RunStatus.FAILED, "AI 服务暂时不可用，面试进度已保存，可稍后继续",
            RunStatus.STEP_LIMIT, "流程异常中断，进度已保存");

    private final InterviewEngine engine;

    public InterviewController(InterviewEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/start")
    public ApiResponse<InterviewTurnVO> start(@RequestBody StartInterviewRequest request) {
        RunResult<InterviewState> result = engine.start(UserContext.get(),
                request.resumeId(), request.position(), request.company(),
                request.domain(), request.difficulty());
        return ApiResponse.ok(toTurnVO(result));
    }

    @PostMapping("/{id}/answer")
    public ApiResponse<InterviewTurnVO> answer(@PathVariable Long id,
                                               @RequestBody AnswerRequest request) {
        RunResult<InterviewState> result = engine.submitAnswer(
                UserContext.get(), id, request.answer());
        return ApiResponse.ok(toTurnVO(result));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<InterviewTurnVO> finish(@PathVariable Long id) {
        return ApiResponse.ok(toTurnVO(engine.finish(UserContext.get(), id)));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<InterviewTurnVO> resume(@PathVariable Long id) {
        return ApiResponse.ok(toTurnVO(engine.resume(UserContext.get(), id)));
    }

    @GetMapping("/{id}/state")
    public ApiResponse<InterviewTurnVO> state(@PathVariable Long id) {
        InterviewState state = engine.loadState(UserContext.get(), id);
        RecordRow row = engine.requireRecord(UserContext.get(), id);
        return ApiResponse.ok(toTurnVO(state, row.status(), null));
    }

    @GetMapping("/list")
    public ApiResponse<List<RecordListItemVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.get();
        List<RecordListItemVO> items = engine.list(userId, page, size).stream()
                .map(row -> new RecordListItemVO(
                        row.id(), row.position(), row.company(), row.domain(),
                        row.difficulty(), row.status(), row.totalScore(),
                        row.createdAt(), row.updatedAt(),
                        engine.dialogues(row.id()).size()))
                .toList();
        return ApiResponse.ok(items);
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<InterviewDetailVO> detail(@PathVariable Long id) {
        Long userId = UserContext.get();
        RecordRow row = engine.requireRecord(userId, id);

        List<InterviewDetailVO.DialogueVO> dialogues = engine.dialogues(id).stream()
                .map(InterviewController::toDialogueVO)
                .toList();

        List<InterviewDetailVO.TraceVO> traces = engine.traces(id).stream()
                .map(t -> new InterviewDetailVO.TraceVO(
                        t.seq(), t.round(), t.nodeName(), t.nodeType(),
                        t.fromNode(), t.toNode(), t.costMs(), t.status()))
                .toList();

        // error 从 state_json 里取：EndNode 把「为什么提前结束」写在了那里
        InterviewState state = engine.loadState(userId, id);

        return ApiResponse.ok(new InterviewDetailVO(
                row.id(), row.position(), row.company(), row.domain(), row.difficulty(),
                row.status(), row.totalScore(), row.report(), state.getError(),
                row.createdAt(), row.updatedAt(), dialogues, traces));
    }

    @GetMapping("/{id}/trace")
    public ApiResponse<List<InterviewDetailVO.TraceVO>> trace(@PathVariable Long id) {
        engine.requireRecord(UserContext.get(), id);
        List<InterviewDetailVO.TraceVO> traces = engine.traces(id).stream()
                .map(t -> new InterviewDetailVO.TraceVO(
                        t.seq(), t.round(), t.nodeName(), t.nodeType(),
                        t.fromNode(), t.toNode(), t.costMs(), t.status()))
                .toList();
        return ApiResponse.ok(traces);
    }

    // ────────────────────────── 转换 ──────────────────────────

    private InterviewTurnVO toTurnVO(RunResult<InterviewState> result) {
        String status = switch (result.status()) {
            case FINISHED -> "finished";
            case SUSPENDED -> "in_progress";
            // 失败和超步数都保持 in_progress：进度已落库，用户还能继续
            default -> "in_progress";
        };
        return toTurnVO(result.state(), status, result.status());
    }

    private InterviewTurnVO toTurnVO(InterviewState state, String status, RunStatus runStatus) {
        boolean finished = "finished".equals(status);
        var eval = state.getEvalResult();
        String error = state.getError();
        if (error == null && runStatus != null && STATUS_TEXT.containsKey(runStatus)
                && runStatus != RunStatus.SUSPENDED && runStatus != RunStatus.FINISHED) {
            error = STATUS_TEXT.get(runStatus);
        }
        return new InterviewTurnVO(
                state.getRecordId(),
                status,
                finished,
                state.getQuestionIndex(),
                state.getMaxQuestions(),
                state.getQuestionText(),
                state.getTopicTracker() == null ? null : state.getTopicTracker().getCurrentTopic(),
                state.getCurrentDifficulty() == null ? null : state.getCurrentDifficulty().getLabel(),
                eval == null ? null : eval.getOverall(),
                eval == null ? null : eval.getDimensions(),
                eval == null ? null : eval.getComment(),
                state.getLastRouting(),
                state.getScoreHistory() == null ? null : state.getScoreHistory().average(),
                finished ? state.getReport() : null,
                error);
    }

    private static InterviewDetailVO.DialogueVO toDialogueVO(Dialogue d) {
        return new InterviewDetailVO.DialogueVO(
                d.getSeq(), d.getTopic(), d.getDifficulty(), d.getQuestion(), d.getAnswer(),
                d.getScore(), d.getDimensions(), d.getComment(), d.getNextAction(), d.getNextTopic());
    }
}
```

- [ ] **Step 4: 编译**

Run: `./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q compile`
Expected: 无输出。

- [ ] **Step 5: 用 curl 跑通一次完整面试（关键验证）**

先确认 API key 已配置：
```bash
export DEEPSEEK_API_KEY=sk-你的key
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run
```

另开终端：
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"

# 1. 开始面试
curl -s -X POST http://localhost:8080/api/interview/start -H "$AUTH" -H "$JSON" \
  -d '{"position":"Java 后端","company":"某互联网公司","domain":"Java","difficulty":"中等"}'
```

Expected：返回 `questionIndex:1`、`question` 是一道 Java 题、`finished:false`、`topic` 是「JVM 内存模型」（话题池第一个）。

```bash
# 把 recordId 记下来
RID=1

# 2. 答题
curl -s -X POST http://localhost:8080/api/interview/$RID/answer -H "$AUTH" -H "$JSON" \
  -d '{"answer":"JMM 定义了线程和主内存之间的抽象关系，规定了 8 种原子操作，通过 volatile、synchronized 和 final 保证可见性和有序性。"}'
```

Expected：返回上一次的 `lastScore`（0-10 的数字）、`lastComment`、`nextAction`（deepen/continue/lower/switch 之一）、以及**下一道新题**的 `question`、`questionIndex:2`。

连续答 2-3 题，确认 `questionIndex` 递增、`nextAction` 会随回答质量变化。

```bash
# 3. 中途退出模拟：什么都不做，直接重新拉状态（等价于关掉页面再打开）
curl -s http://localhost:8080/api/interview/$RID/state -H "$AUTH"
```

Expected：`status:"in_progress"`，`question` 还是刚才那道题。

```bash
# 4. 继续（不需要答案，从游标推进）
curl -s -X POST http://localhost:8080/api/interview/$RID/resume -H "$AUTH"
```

Expected：返回同一道题（游标停在 wait_answer，没答案就立刻挂起）。

```bash
# 5. 主动结束
curl -s -X POST http://localhost:8080/api/interview/$RID/finish -H "$AUTH"
```

Expected：`finished:true`，`report` 是一段 markdown 综合报告，`status:"finished"`。

```bash
# 6. 复盘详情
curl -s http://localhost:8080/api/interview/$RID/detail -H "$AUTH"
```

Expected：`dialogues` 里每题的 `nextAction` 有值、`score` 有值；`traces` 里能看到 `start / question / wait_answer / evaluate` 以及 `nodeType:"branch"` 的行。

- [ ] **Step 6: 验证失败可恢复**

```bash
# 故意把 api-key 改成错的再启动，或者断网
curl -s -X POST http://localhost:8080/api/interview/start -H "$AUTH" -H "$JSON" \
  -d '{"position":"Java 后端","domain":"Java","difficulty":"中等"}'
```

Expected：LLM 报 401（NonRetryableException），不重试，直接返回 `{"code":500,...}`。日志里有 `面试 x 在节点 question 执行失败，游标已保留，可重新唤起继续`。

```bash
# 修好 key 后重启服务，再继续这场面试
curl -s -X POST http://localhost:8080/api/interview/1/resume -H "$AUTH"
```

Expected：能正常跑出第一道题 —— 证明「面试中失败了可以重新唤起」这条需求成立。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/
git commit -m "feat(interview): 面试接口（start/answer/finish/resume/state/list/detail/trace）"
```

---

## Task 24: 前端 —— 公共资源与登录页

**Files:**
- Create: `src/main/resources/static/js/app.js`
- Create: `src/main/resources/static/css/app.css`
- Create: `src/main/resources/static/login.html`

- [ ] **Step 1: 写 `js/app.js`**

```javascript
/* 全局工具。所有页面共用，不用打包工具，直接 <script src="js/app.js"> */

const TOKEN_KEY = 'ai-mianshi-token';

function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

/** 未登录就踢回登录页。每个需要登录的页面在 onMounted 里调一次 */
function requireLogin() {
  if (!getToken()) {
    location.replace('login.html');
    return false;
  }
  return true;
}

function logout() {
  clearToken();
  location.replace('login.html');
}

/**
 * 统一的接口调用。
 * - 自动带 Authorization 头
 * - 401 自动清 token 跳登录
 * - code !== 0 抛异常，调用方 catch 后提示
 * - body 自动 JSON 序列化；FormData 原样传（文件上传）
 */
async function api(path, options = {}) {
  const opts = { method: options.method || 'GET', headers: {} };
  const isForm = options.body instanceof FormData;

  if (options.body !== undefined) {
    if (isForm) {
      opts.body = options.body;
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(options.body);
    }
  }

  const token = getToken();
  if (token) {
    opts.headers['Authorization'] = 'Bearer ' + token;
  }

  let response;
  try {
    response = await fetch(path, opts);
  } catch (e) {
    throw new Error('网络请求失败，请检查服务是否已启动');
  }

  if (response.status === 401) {
    clearToken();
    location.replace('login.html');
    throw new Error('登录已过期');
  }

  const json = await response.json();
  if (json.code !== 0) {
    throw new Error(json.message || '请求失败');
  }
  return json.data;
}

/** epoch 毫秒 → 2026-09-28 15:30 */
function fmtTime(millis) {
  if (!millis) return '-';
  const d = new Date(millis);
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
       + `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fmtScore(score) {
  return score === null || score === undefined ? '-' : Number(score).toFixed(1);
}

/** 顶部飘一条提示，2.5 秒后自动消失 */
function toast(message, isError = false) {
  let el = document.getElementById('global-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'global-toast';
    document.body.appendChild(el);
  }
  el.textContent = message;
  el.className = 'toast show' + (isError ? ' toast-error' : '');
  clearTimeout(el._timer);
  el._timer = setTimeout(() => { el.className = 'toast'; }, 2500);
}

/** 最小的 markdown 渲染：标题、粗体、列表、段落。报告是 LLM 出的，只用到这些 */
function renderMarkdown(text) {
  if (!text) return '';
  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return esc(text)
    .replace(/^### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^## (.+)$/gm, '<h3>$1</h3>')
    .replace(/^# (.+)$/gm, '<h2>$1</h2>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/^[-*] (.+)$/gm, '<li>$1</li>')
    .replace(/(<li>[\s\S]*?<\/li>)/g, '<ul>$1</ul>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/^/, '<p>')
    .replace(/$/, '</p>');
}

const DIFFICULTY_OPTIONS = ['简单', '中等', '困难'];

const DIMENSION_LABELS = {
  accuracy: '准确性',
  depth: '深度',
  clarity: '表达',
  practice: '实践',
  problemSolving: '解题思路'
};

const NEXT_ACTION_LABELS = {
  deepen: '深入追问',
  continue: '换个角度',
  lower: '降低难度',
  switch: '更换话题',
  end: '结束面试'
};
```

- [ ] **Step 2: 写 `css/app.css`**

```css
:root {
  --bg: #f5f6f8;
  --card: #ffffff;
  --border: #e3e6ea;
  --text: #1f2329;
  --muted: #8a9099;
  --primary: #2b6cff;
  --primary-dark: #1d54d0;
  --success: #17a673;
  --warning: #e8a33d;
  --danger: #e34d4d;
  --radius: 10px;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif;
  font-size: 14px;
  line-height: 1.7;
}

a { color: var(--primary); text-decoration: none; }

.topbar {
  display: flex;
  align-items: center;
  gap: 20px;
  background: var(--card);
  border-bottom: 1px solid var(--border);
  padding: 0 24px;
  height: 56px;
  position: sticky;
  top: 0;
  z-index: 10;
}

.topbar .brand { font-weight: 600; font-size: 16px; }
.topbar nav { display: flex; gap: 16px; flex: 1; }
.topbar nav a { color: var(--muted); }
.topbar nav a.active { color: var(--primary); font-weight: 500; }
.topbar .user { color: var(--muted); display: flex; align-items: center; gap: 12px; }

.container { max-width: 960px; margin: 24px auto; padding: 0 16px; }
.container-wide { max-width: 1200px; margin: 24px auto; padding: 0 16px; }

.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  margin-bottom: 16px;
}

.card h2 { margin: 0 0 16px; font-size: 16px; }
.card h3 { margin: 20px 0 8px; font-size: 15px; }
.card h4 { margin: 16px 0 6px; font-size: 14px; }

.field { margin-bottom: 14px; }
.field label { display: block; margin-bottom: 6px; color: var(--muted); font-size: 13px; }

input, select, textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 14px;
  font-family: inherit;
  background: #fff;
  color: var(--text);
}

input:focus, select:focus, textarea:focus {
  outline: none;
  border-color: var(--primary);
}

textarea { resize: vertical; min-height: 110px; line-height: 1.6; }

button {
  padding: 9px 18px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: #fff;
  color: var(--text);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}

button:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
button:disabled { opacity: .55; cursor: not-allowed; }

button.primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
}
button.primary:hover:not(:disabled) { background: var(--primary-dark); color: #fff; }

button.danger { color: var(--danger); }
button.danger:hover:not(:disabled) { border-color: var(--danger); color: var(--danger); }

.row { display: flex; gap: 12px; }
.row > * { flex: 1; }
.actions { display: flex; gap: 10px; margin-top: 8px; }

.muted { color: var(--muted); }
.small { font-size: 13px; }
.center { text-align: center; }

.tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid var(--border);
  color: var(--muted);
}
.tag.primary { color: var(--primary); border-color: #bcd0ff; background: #eef3ff; }
.tag.success { color: var(--success); border-color: #b6e4d3; background: #edfaf5; }
.tag.warning { color: var(--warning); border-color: #f3ddb4; background: #fdf7ec; }

.score { font-size: 22px; font-weight: 600; }
.score.good { color: var(--success); }
.score.mid { color: var(--warning); }
.score.bad { color: var(--danger); }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 9px 10px; border-bottom: 1px solid var(--border); text-align: left; }
th { color: var(--muted); font-weight: 500; background: #fafbfc; }
tbody tr:hover { background: #fafbfc; }

.empty { padding: 40px; text-align: center; color: var(--muted); }

.spinner {
  display: inline-block;
  width: 14px; height: 14px;
  border: 2px solid rgba(255,255,255,.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin .7s linear infinite;
  vertical-align: -2px;
  margin-right: 6px;
}
@keyframes spin { to { transform: rotate(360deg); } }

.toast {
  position: fixed;
  top: 20px; left: 50%;
  transform: translate(-50%, -20px);
  background: #32363d;
  color: #fff;
  padding: 10px 20px;
  border-radius: 6px;
  opacity: 0;
  pointer-events: none;
  transition: all .2s;
  z-index: 100;
}
.toast.show { opacity: 1; transform: translate(-50%, 0); }
.toast.toast-error { background: var(--danger); }

/* 面试对话流 */
.chat { display: flex; flex-direction: column; gap: 14px; }

.bubble { max-width: 82%; padding: 12px 16px; border-radius: 10px; white-space: pre-wrap; }
.bubble.interviewer { background: #eef3ff; border: 1px solid #dbe6ff; align-self: flex-start; }
.bubble.candidate { background: #fff; border: 1px solid var(--border); align-self: flex-end; }

.feedback {
  align-self: flex-start;
  max-width: 82%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #fdf7ec;
  border: 1px solid #f3ddb4;
  font-size: 13px;
}

.dim-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; margin-top: 8px; }
.dim-item { background: #fff; border: 1px solid var(--border); border-radius: 6px; padding: 6px; text-align: center; }
.dim-item .k { color: var(--muted); font-size: 12px; }
.dim-item .v { font-weight: 600; }

.progress-bar { height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; }
.progress-bar > div { height: 100%; background: var(--primary); transition: width .3s; }

.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card { width: 340px; }
.login-card h1 { font-size: 20px; margin: 0 0 6px; }
```

- [ ] **Step 3: 写 `login.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>登录 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div class="login-wrap">
  <div class="card login-card">
    <h1>AI 模拟面试</h1>
    <p class="muted small">用图编排驱动的多轮技术面试</p>

    <div class="field">
      <label>用户名</label>
      <input v-model="username" @keyup.enter="submit" placeholder="admin">
    </div>
    <div class="field">
      <label>密码</label>
      <input v-model="password" type="password" @keyup.enter="submit" placeholder="admin123">
    </div>

    <button class="primary" style="width:100%" :disabled="loading" @click="submit">
      <span v-if="loading" class="spinner"></span>{{ loading ? '登录中…' : '登录' }}
    </button>

    <p class="muted small center" style="margin-top:14px">默认账号 admin / admin123</p>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return { username: 'admin', password: '', loading: false };
  },
  methods: {
    async submit() {
      if (this.loading) return;
      this.loading = true;
      try {
        const data = await api('/api/auth/login', {
          method: 'POST',
          body: { username: this.username, password: this.password }
        });
        setToken(data.token);
        location.replace('index.html');
      } catch (e) {
        toast(e.message, true);
      } finally {
        this.loading = false;
      }
    }
  }
}).mount('.login-wrap');
</script>
</body>
</html>
```

> **CDN 说明**：国际网络用 `cdn.jsdelivr.net`，国内如果加载慢或不通，
> 把 `script src` 换成 `https://cdn.bootcdn.net/ajax/libs/vue/3.5.13/vue.global.prod.js`。
> 六个页面用的是同一个 URL，全局替换即可。

- [ ] **Step 4: 验证登录页**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml spring-boot:run
```
浏览器打开 `http://localhost:8080/login.html`。
Expected：
1. 页面正常渲染（不是一片空白——空白说明 Vue CDN 没加载成功）
2. 输入 admin / admin123 点登录 → 跳到 `index.html`（此时会 404，因为还没写，正常）
3. 打开浏览器控制台执行 `localStorage.getItem('ai-mianshi-token')` 应能看到 token

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/
git commit -m "feat(web): 前端公共资源（api 封装/样式）与登录页"
```

---

## Task 25: 前端 —— 首页与简历管理

**Files:**
- Create: `src/main/resources/static/index.html`
- Create: `src/main/resources/static/resume.html`

- [ ] **Step 1: 写 `index.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>开始面试 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div class="topbar">
  <span class="brand">AI 模拟面试</span>
  <nav>
    <a href="index.html" class="active">开始面试</a>
    <a href="history.html">面试记录</a>
    <a href="resume.html">我的简历</a>
  </nav>
  <span class="user">{{ nickname }} <button class="small" @click="logout">退出</button></span>
</div>

<div class="container">
  <div class="card">
    <h2>面试配置</h2>

    <div class="field">
      <label>简历（决定出题的个性化程度）</label>
      <select v-model="form.resumeId">
        <option :value="null">不使用简历</option>
        <option v-for="r in resumes" :key="r.id" :value="r.id">
          {{ r.filename }}{{ r.isDefault ? '（默认）' : '' }}
        </option>
      </select>
      <p class="muted small" v-if="resumes.length === 0">
        还没有简历，<a href="resume.html">去上传一份</a>能让问题更贴合你的经历。
      </p>
    </div>

    <div class="row">
      <div class="field">
        <label>岗位</label>
        <input v-model="form.position" placeholder="例如：Java 后端开发">
      </div>
      <div class="field">
        <label>目标公司（可留空）</label>
        <input v-model="form.company" placeholder="例如：某互联网公司">
      </div>
    </div>

    <div class="row">
      <div class="field">
        <label>技术方向（决定话题池）</label>
        <select v-model="form.domain">
          <option v-for="d in domains" :key="d" :value="d">{{ d }}</option>
        </select>
      </div>
      <div class="field">
        <label>初始难度</label>
        <select v-model="form.difficulty">
          <option v-for="d in difficulties" :key="d" :value="d">{{ d }}</option>
        </select>
      </div>
    </div>

    <div class="actions">
      <button class="primary" :disabled="loading" @click="start">
        <span v-if="loading" class="spinner"></span>{{ loading ? 'AI 面试官正在准备第一个问题…' : '开始面试' }}
      </button>
    </div>
    <p class="muted small" v-if="loading" style="margin-top:10px">
      AI 服务繁忙时会自动重试，最长约 1.5 分钟，请勿刷新页面。
    </p>
  </div>

  <div class="card" v-if="inProgress.length">
    <h2>未完成的面试</h2>
    <p class="muted small">关掉页面不会结束面试，这里可以随时接着答。</p>
    <table>
      <thead>
        <tr><th>岗位</th><th>方向</th><th>难度</th><th>题目数</th><th>开始时间</th><th></th></tr>
      </thead>
      <tbody>
        <tr v-for="r in inProgress" :key="r.id">
          <td>{{ r.position || '-' }}</td>
          <td>{{ r.domain || '-' }}</td>
          <td>{{ r.difficulty || '-' }}</td>
          <td>{{ r.dialogueCount }}</td>
          <td>{{ fmtTime(r.createdAt) }}</td>
          <td><button class="primary small" @click="go(r.id)">继续面试</button></td>
        </tr>
      </tbody>
    </table>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return {
      nickname: '',
      resumes: [],
      inProgress: [],
      domains: ['Java', 'Go', 'Python'],
      difficulties: DIFFICULTY_OPTIONS,
      loading: false,
      form: { resumeId: null, position: 'Java 后端开发', company: '', domain: 'Java', difficulty: '中等' }
    };
  },
  async mounted() {
    if (!requireLogin()) return;
    try {
      const me = await api('/api/auth/me');
      this.nickname = me.nickname || me.username;

      this.resumes = await api('/api/resume/list');
      const def = this.resumes.find(r => r.isDefault);
      if (def) this.form.resumeId = def.id;

      const records = await api('/api/interview/list?page=1&size=50');
      this.inProgress = records.filter(r => r.status === 'in_progress');
    } catch (e) {
      toast(e.message, true);
    }
  },
  methods: {
    logout,
    fmtTime,
    go(id) { location.href = 'interview.html?id=' + id; },
    async start() {
      if (this.loading) return;
      this.loading = true;
      try {
        const data = await api('/api/interview/start', { method: 'POST', body: this.form });
        location.href = 'interview.html?id=' + data.recordId;
      } catch (e) {
        toast(e.message, true);
        this.loading = false;
      }
    }
  }
}).mount('.topbar') || createApp({}).mount('body');
</script>
</body>
</html>
```

> **注意**：上面最后一行 `mount('.topbar') || ...` 是错的——Vue 的 `mount()` 返回组件实例，
> 一个 app 只能挂一个根节点。改成把整页包进一个根 `div` 再挂它。见下面修正版。

- [ ] **Step 2: 修正挂载方式**

把 `<body>` 的内容改成：

```html
<body>
<div id="app">
  <div class="topbar"> … 保持不变 … </div>
  <div class="container"> … 保持不变 … </div>
</div>
<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return {
      nickname: '',
      resumes: [],
      inProgress: [],
      domains: ['Java', 'Go', 'Python'],
      difficulties: DIFFICULTY_OPTIONS,
      loading: false,
      form: { resumeId: null, position: 'Java 后端开发', company: '', domain: 'Java', difficulty: '中等' }
    };
  },
  async mounted() {
    if (!requireLogin()) return;
    try {
      const me = await api('/api/auth/me');
      this.nickname = me.nickname || me.username;

      this.resumes = await api('/api/resume/list');
      const def = this.resumes.find(r => r.isDefault);
      if (def) this.form.resumeId = def.id;

      const records = await api('/api/interview/list?page=1&size=50');
      this.inProgress = records.filter(r => r.status === 'in_progress');
    } catch (e) {
      toast(e.message, true);
    }
  },
  methods: {
    logout,
    fmtTime,
    go(id) { location.href = 'interview.html?id=' + id; },
    async start() {
      if (this.loading) return;
      this.loading = true;
      try {
        const data = await api('/api/interview/start', { method: 'POST', body: this.form });
        location.href = 'interview.html?id=' + data.recordId;
      } catch (e) {
        toast(e.message, true);
        this.loading = false;
      }
    }
  }
}).mount('#app');
</script>
</body>
```

**这个「整页包一个 `#app` 再挂载」的写法是后面四个页面的统一模板**，别再出现挂 `.topbar` 这种写法。

- [ ] **Step 3: 写 `resume.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>我的简历 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div id="app">
  <div class="topbar">
    <span class="brand">AI 模拟面试</span>
    <nav>
      <a href="index.html">开始面试</a>
      <a href="history.html">面试记录</a>
      <a href="resume.html" class="active">我的简历</a>
    </nav>
    <span class="user">{{ nickname }} <button class="small" @click="logout">退出</button></span>
  </div>

  <div class="container">
    <div class="card">
      <h2>上传简历</h2>
      <p class="muted small">支持 PDF，不超过 10MB。扫描件（图片型 PDF）无法提取文字。</p>
      <div class="field">
        <input type="file" accept="application/pdf,.pdf" ref="fileInput" @change="onPick">
      </div>
      <button class="primary" :disabled="uploading || !picked" @click="upload">
        <span v-if="uploading" class="spinner"></span>{{ uploading ? '上传解析中…' : '上传' }}
      </button>
    </div>

    <div class="card">
      <h2>已上传的简历</h2>
      <div v-if="list.length === 0" class="empty">还没有简历</div>
      <table v-else>
        <thead>
          <tr><th>文件名</th><th>内容摘要</th><th>状态</th><th>上传时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id">
            <td>{{ r.filename }}</td>
            <td class="muted small" style="max-width:340px">{{ r.preview }}</td>
            <td><span v-if="r.isDefault" class="tag primary">默认</span><span v-else class="muted">-</span></td>
            <td class="muted small">{{ fmtTime(r.createdAt) }}</td>
            <td>
              <button v-if="!r.isDefault" class="small" @click="setDefault(r.id)">设为默认</button>
              <button class="small danger" @click="remove(r.id)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return { nickname: '', list: [], picked: null, uploading: false };
  },
  async mounted() {
    if (!requireLogin()) return;
    try {
      const me = await api('/api/auth/me');
      this.nickname = me.nickname || me.username;
      await this.reload();
    } catch (e) {
      toast(e.message, true);
    }
  },
  methods: {
    logout,
    fmtTime,
    async reload() { this.list = await api('/api/resume/list'); },
    onPick(event) { this.picked = event.target.files[0] || null; },
    async upload() {
      if (!this.picked) return;
      this.uploading = true;
      try {
        const form = new FormData();
        form.append('file', this.picked);
        await api('/api/resume/upload', { method: 'POST', body: form });
        toast('上传成功');
        this.picked = null;
        this.$refs.fileInput.value = '';
        await this.reload();
      } catch (e) {
        toast(e.message, true);
      } finally {
        this.uploading = false;
      }
    },
    async setDefault(id) {
      try { await api('/api/resume/' + id + '/default', { method: 'POST' }); await this.reload(); }
      catch (e) { toast(e.message, true); }
    },
    async remove(id) {
      if (!confirm('确定删除这份简历？')) return;
      try { await api('/api/resume/' + id, { method: 'DELETE' }); await this.reload(); }
      catch (e) { toast(e.message, true); }
    }
  }
}).mount('#app');
</script>
</body>
</html>
```

- [ ] **Step 4: 验证**

重启服务，浏览器访问 `http://localhost:8080/index.html`。
Expected：
1. 顶部显示昵称，导航可点
2. 简历下拉里有已上传的简历并自动选中默认那份
3. 点「开始面试」→ 按钮变 loading 文案 → 跳到 `interview.html?id=1`（此时 404 正常）

再访问 `resume.html`，上传一个 PDF。
Expected：上传成功后列表出现该文件，标着「默认」；删除、设为默认都能用。

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/
git commit -m "feat(web): 首页面试配置与简历管理页"
```

---

## Task 26: 前端 —— 面试进行页与记录列表

**Files:**
- Create: `src/main/resources/static/interview.html`
- Create: `src/main/resources/static/history.html`

- [ ] **Step 1: 写 `interview.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>面试进行中 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div id="app">
  <div class="topbar">
    <span class="brand">AI 模拟面试</span>
    <nav><a href="history.html">面试记录</a></nav>
    <span class="user">
      <span v-if="turn && !turn.finished">
        第 {{ turn.questionIndex }}/{{ turn.total }} 题 ·
        均分 {{ fmtScore(turn.averageScore) }}
      </span>
      <button class="small danger" v-if="turn && !turn.finished" @click="finish">结束面试</button>
    </span>
  </div>

  <div class="container">
    <div class="progress-bar" v-if="turn && !turn.finished" style="margin-bottom:16px">
      <div :style="{ width: ((turn.questionIndex - 1) / turn.total * 100) + '%' }"></div>
    </div>

    <div v-if="booting" class="card center muted">正在载入面试现场…</div>

    <template v-else-if="turn">
      <div class="card">
        <div class="chat">
          <!-- 最近一轮的反馈：评分 + 分支决策 -->
          <div class="feedback" v-if="turn.lastScore !== null && turn.lastScore !== undefined">
            <div>
              <span class="score" :class="scoreClass(turn.lastScore)">{{ fmtScore(turn.lastScore) }}</span>
              <span class="tag" style="margin-left:8px">
                {{ NEXT_ACTION_LABELS[turn.nextAction] || turn.nextAction }}
              </span>
            </div>
            <div style="margin-top:6px">{{ turn.lastComment }}</div>
            <div class="dim-grid" v-if="turn.lastDimensions">
              <div class="dim-item" v-for="(v, k) in turn.lastDimensions" :key="k">
                <div class="k">{{ DIMENSION_LABELS[k] || k }}</div>
                <div class="v">{{ fmtScore(v) }}</div>
              </div>
            </div>
          </div>

          <div class="bubble interviewer" v-if="turn.question">
            <span class="muted small">
              第 {{ turn.questionIndex }} 题 · {{ turn.topic }} · {{ turn.difficulty }}
            </span>
            <div style="margin-top:6px">{{ turn.question }}</div>
          </div>

          <div class="bubble candidate" v-if="lastAnswer">{{ lastAnswer }}</div>
        </div>

        <div v-if="turn.error" class="feedback" style="background:#fdecec;border-color:#f5c2c2;margin-top:14px">
          {{ turn.error }}
        </div>

        <div v-if="!turn.finished" style="margin-top:18px">
          <textarea v-model="answer" :disabled="submitting"
                    placeholder="写下你的回答…（Ctrl+Enter 提交）"
                    @keydown.ctrl.enter="submit"></textarea>
          <div class="actions">
            <button class="primary" :disabled="submitting || !answer.trim()" @click="submit">
              <span v-if="submitting" class="spinner"></span>{{ submitting ? '面试官正在评估…' : '提交回答' }}
            </button>
          </div>
          <p class="muted small" v-if="submitting" style="margin-top:10px">
            AI 服务繁忙时会自动重试，最长约 1.5 分钟，请勿刷新页面。
          </p>
        </div>
      </div>
    </template>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return {
      recordId: null,
      turn: null,
      answer: '',
      lastAnswer: '',
      submitting: false,
      booting: true,
      DIMENSION_LABELS, NEXT_ACTION_LABELS
    };
  },
  async mounted() {
    if (!requireLogin()) return;
    const id = new URLSearchParams(location.search).get('id');
    if (!id) { location.replace('index.html'); return; }
    this.recordId = id;

    try {
      // resume 是幂等的：游标停在 wait_answer 且没有答案时会立刻挂起并返回当前题目。
      // 所以「刷新页面」「关掉再回来」「上次 LLM 失败后重试」走的都是这一条路径。
      this.apply(await api('/api/interview/' + id + '/resume', { method: 'POST' }));
    } catch (e) {
      toast(e.message, true);
      this.booting = false;
    }
  },
  methods: {
    fmtScore,
    scoreClass(score) {
      if (score >= 8) return 'good';
      if (score >= 4) return 'mid';
      return 'bad';
    },
    apply(turn) {
      this.turn = turn;
      this.booting = false;
      if (turn.finished) {
        location.replace('report.html?id=' + turn.recordId);
      }
    },
    async submit() {
      if (this.submitting || !this.answer.trim()) return;
      this.submitting = true;
      const submitted = this.answer.trim();
      try {
        const turn = await api('/api/interview/' + this.recordId + '/answer', {
          method: 'POST',
          body: { answer: submitted }
        });
        this.lastAnswer = submitted;
        this.answer = '';
        this.apply(turn);
      } catch (e) {
        toast(e.message, true);
      } finally {
        this.submitting = false;
      }
    },
    async finish() {
      if (!confirm('确定结束这场面试？结束后会生成本次的综合报告。')) return;
      this.submitting = true;
      try {
        this.apply(await api('/api/interview/' + this.recordId + '/finish', { method: 'POST' }));
      } catch (e) {
        toast(e.message, true);
      } finally {
        this.submitting = false;
      }
    }
  }
}).mount('#app');
</script>
</body>
</html>
```

- [ ] **Step 2: 写 `history.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>面试记录 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div id="app">
  <div class="topbar">
    <span class="brand">AI 模拟面试</span>
    <nav>
      <a href="index.html">开始面试</a>
      <a href="history.html" class="active">面试记录</a>
      <a href="resume.html">我的简历</a>
    </nav>
    <span class="user">{{ nickname }} <button class="small" @click="logout">退出</button></span>
  </div>

  <div class="container-wide">
    <div class="card">
      <h2>面试记录</h2>
      <div v-if="list.length === 0" class="empty">还没有面试记录，<a href="index.html">去开始一场</a></div>
      <table v-else>
        <thead>
          <tr>
            <th>岗位</th><th>公司</th><th>方向</th><th>难度</th>
            <th>题目数</th><th>均分</th><th>状态</th><th>开始时间</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id">
            <td>{{ r.position || '-' }}</td>
            <td>{{ r.company || '-' }}</td>
            <td>{{ r.domain || '-' }}</td>
            <td>{{ r.difficulty || '-' }}</td>
            <td>{{ r.dialogueCount }}</td>
            <td><span class="score" :class="scoreClass(r.totalScore)" style="font-size:15px">{{ fmtScore(r.totalScore) }}</span></td>
            <td>
              <span v-if="r.status === 'finished'" class="tag success">已结束</span>
              <span v-else class="tag warning">进行中</span>
            </td>
            <td class="muted small">{{ fmtTime(r.createdAt) }}</td>
            <td>
              <button v-if="r.status === 'finished'" class="small" @click="go('report.html', r.id)">查看复盘</button>
              <button v-else class="small primary" @click="go('interview.html', r.id)">继续面试</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return { nickname: '', list: [] };
  },
  async mounted() {
    if (!requireLogin()) return;
    try {
      const me = await api('/api/auth/me');
      this.nickname = me.nickname || me.username;
      this.list = await api('/api/interview/list?page=1&size=100');
    } catch (e) {
      toast(e.message, true);
    }
  },
  methods: {
    logout,
    fmtTime,
    fmtScore,
    scoreClass(score) {
      if (score === null || score === undefined) return '';
      if (score >= 8) return 'good';
      if (score >= 4) return 'mid';
      return 'bad';
    },
    go(page, id) { location.href = page + '?id=' + id; }
  }
}).mount('#app');
</script>
</body>
</html>
```

- [ ] **Step 3: 验证完整面试流程**

重启服务，浏览器从 `index.html` 点「开始面试」。
Expected：
1. loading 文案出现，等 5-15 秒后显示第一道题
2. 顶部显示「第 1/10 题 · 均分 -」
3. 输入一段回答点提交 → loading → 出现黄色反馈卡（分数 + 分支标签 + 五维小格子）+ 下一题
4. 答 3-4 题后按 `F5` 刷新页面 → **回到当前那道题，进度和均分都还在**（这就是断点续传）
5. 点「结束面试」→ 跳 `report.html?id=x`（此时 404 正常）

再访问 `history.html`。
Expected：列出刚才那场，状态「已结束」，有均分，点「查看复盘」。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/
git commit -m "feat(web): 面试进行页（对话流 + 实时评分）与记录列表"
```

---

## Task 27: 前端 —— 复盘页

**Files:**
- Create: `src/main/resources/static/report.html`

- [ ] **Step 1: 写 `report.html`**

一期只做「逐题详情 + 决策链表格 + AI 综合报告」。图表（雷达图、趋势图、图路径）
是二期的事，但数据一期就采全了，接口形状不用改。

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>面试复盘 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>
<div id="app">
  <div class="topbar">
    <span class="brand">AI 模拟面试</span>
    <nav>
      <a href="index.html">开始面试</a>
      <a href="history.html" class="active">面试记录</a>
      <a href="resume.html">我的简历</a>
    </nav>
    <span class="user">{{ nickname }} <button class="small" @click="logout">退出</button></span>
  </div>

  <div class="container-wide">
    <div v-if="loading" class="card center muted">正在载入复盘数据…</div>

    <template v-else-if="detail">
      <!-- 概览 -->
      <div class="card">
        <div style="display:flex;align-items:baseline;gap:16px;flex-wrap:wrap">
          <h2 style="margin:0">{{ detail.position || '技术面试' }}
            <span class="muted small" v-if="detail.company">· {{ detail.company }}</span>
          </h2>
          <span class="tag">{{ detail.domain }}</span>
          <span class="tag">{{ detail.difficulty }}</span>
          <span class="muted small">{{ fmtTime(detail.createdAt) }}</span>
          <span style="flex:1"></span>
          <span>
            <span class="score" :class="scoreClass(detail.totalScore)">{{ fmtScore(detail.totalScore) }}</span>
            <span class="muted"> / 10</span>
          </span>
        </div>
        <p class="muted small" style="margin:10px 0 0">
          共 {{ detail.dialogues.length }} 题 ·
          覆盖 {{ coveredTopicCount }} 个话题 ·
          {{ detail.status === 'finished' ? '已结束' : '进行中' }}
        </p>
        <div v-if="detail.error" class="feedback" style="background:#fdecec;border-color:#f5c2c2;margin-top:12px">
          {{ detail.error }}
        </div>
      </div>

      <!-- 决策链：图是怎么走的 -->
      <div class="card">
        <h2>图的分支决策链</h2>
        <p class="muted small">
          每一轮评分后，图根据得分和话题覆盖度决定下一步。这张表就是那次决策的记录。
        </p>
        <table>
          <thead>
            <tr>
              <th>题号</th><th>话题</th><th>难度</th><th>得分</th>
              <th>图的分支</th><th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in detail.dialogues" :key="d.seq">
              <td>{{ d.seq }}</td>
              <td>{{ d.topic || '-' }}</td>
              <td>{{ d.difficulty || '-' }}</td>
              <td><span class="score" :class="scoreClass(d.score)" style="font-size:15px">{{ fmtScore(d.score) }}</span></td>
              <td>
                <span class="tag" :class="routingTagClass(d.nextAction)">
                  → {{ NEXT_ACTION_LABELS[d.nextAction] || d.nextAction }}
                </span>
              </td>
              <td class="muted small">
                {{ d.comment }}
                <span v-if="d.nextTopic">（换到：{{ d.nextTopic }}）</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 逐题详情 -->
      <div class="card">
        <h2>逐题详情</h2>
        <div v-for="d in detail.dialogues" :key="d.seq"
             style="border-bottom:1px solid var(--border);padding:14px 0">
          <div style="display:flex;align-items:baseline;gap:10px;flex-wrap:wrap">
            <strong>第 {{ d.seq }} 题</strong>
            <span class="tag">{{ d.topic }}</span>
            <span class="tag">{{ d.difficulty }}</span>
            <span style="flex:1"></span>
            <span class="score" :class="scoreClass(d.score)" style="font-size:16px">{{ fmtScore(d.score) }}</span>
          </div>

          <p style="margin:8px 0 4px"><span class="muted small">问：</span>{{ d.question }}</p>
          <p style="margin:4px 0;white-space:pre-wrap"><span class="muted small">答：</span>{{ d.answer }}</p>
          <p style="margin:4px 0" v-if="d.comment"><span class="muted small">评语：</span>{{ d.comment }}</p>

          <div class="dim-grid" v-if="d.dimensions">
            <div class="dim-item" v-for="(v, k) in d.dimensions" :key="k">
              <div class="k">{{ DIMENSION_LABELS[k] || k }}</div>
              <div class="v">{{ fmtScore(v) }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 综合报告 -->
      <div class="card">
        <h2>AI 综合报告</h2>
        <div v-if="detail.report" v-html="renderMarkdown(detail.report)"></div>
        <div v-else class="muted">这场面试还没结束，结束之后才会有综合报告。</div>
      </div>

      <!-- 图执行轨迹（折叠） -->
      <div class="card">
        <h2 style="cursor:pointer" @click="showTrace = !showTrace">
          图执行轨迹（{{ detail.traces.length }} 条）{{ showTrace ? '▾' : '▸' }}
        </h2>
        <p class="muted small">
          引擎每进出一个节点、每做一次分支决策都会记一条。二期用它画路径可视化图。
        </p>
        <table v-if="showTrace">
          <thead>
            <tr><th>#</th><th>轮次</th><th>节点</th><th>类型</th><th>去向</th><th>耗时</th><th>状态</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in detail.traces" :key="t.seq">
              <td>{{ t.seq }}</td>
              <td>{{ t.round }}</td>
              <td>{{ t.nodeName }}</td>
              <td><span class="tag">{{ t.nodeType }}</span></td>
              <td>{{ t.toNode || '-' }}</td>
              <td class="muted small">{{ t.costMs }} ms</td>
              <td>
                <span v-if="t.status === 'error'" class="tag" style="color:var(--danger)">error</span>
                <span v-else-if="t.status === 'suspend'" class="tag warning">挂起</span>
                <span v-else class="muted">ok</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</div>

<script src="https://cdn.jsdelivr.net/npm/vue@3.5.13/dist/vue.global.prod.js"></script>
<script src="js/app.js"></script>
<script>
const { createApp } = Vue;

createApp({
  data() {
    return {
      nickname: '',
      detail: null,
      loading: true,
      showTrace: false,
      DIMENSION_LABELS, NEXT_ACTION_LABELS, renderMarkdown
    };
  },
  computed: {
    coveredTopicCount() {
      if (!this.detail) return 0;
      return new Set(this.detail.dialogues.map(d => d.topic).filter(Boolean)).size;
    }
  },
  async mounted() {
    if (!requireLogin()) return;
    const id = new URLSearchParams(location.search).get('id');
    if (!id) { location.replace('history.html'); return; }
    try {
      const me = await api('/api/auth/me');
      this.nickname = me.nickname || me.username;
      this.detail = await api('/api/interview/' + id + '/detail');
    } catch (e) {
      toast(e.message, true);
    } finally {
      this.loading = false;
    }
  },
  methods: {
    logout,
    fmtTime,
    fmtScore,
    renderMarkdown,
    scoreClass(score) {
      if (score === null || score === undefined) return '';
      if (score >= 8) return 'good';
      if (score >= 4) return 'mid';
      return 'bad';
    },
    routingTagClass(action) {
      if (action === 'deepen') return 'success';
      if (action === 'lower') return 'warning';
      if (action === 'end') return 'primary';
      return '';
    }
  }
}).mount('#app');
</script>
</body>
</html>
```

- [ ] **Step 2: 端到端验收**

重启服务，从 `history.html` 点「查看复盘」进 `report.html`。
Expected：
1. 顶部概览显示总分、题数、话题数
2. 「图的分支决策链」表格里每题的 `nextAction` 有值且和得分对得上
   （高分 → 深入追问，低分 → 降低难度，连续追同话题 3 次后 → 更换话题）
3. 逐题详情展开正常，五维小格子有数
4. AI 综合报告正常渲染成 markdown（标题、列表、粗体）
5. 点开「图执行轨迹」，能看到 `start / question / wait_answer / evaluate / deepen(branch)` 这样的序列

**验收标准（设计文档 11.1）：能真实完成一场 10 题面试，中途关页面再回来能继续，结束后能看到逐题评分与决策链。** 到这一步应该全部满足。

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/
git commit -m "feat(web): 复盘页（概览 + 决策链 + 逐题详情 + 综合报告 + 执行轨迹）"
```

---

## Task 28: README.md

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写 `README.md`**

````markdown
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
./mvnw spring-boot:run
```

> 如果你的 Maven 用了公司私服，加上 `-s 你的settings.xml`。
> 本地仓库位置在 settings.xml 的 `<localRepository>` 里配。

### 3. 打开

浏览器访问 <http://localhost:8080/login.html>

默认账号：`admin` / `admin123`（首次启动自动创建）

### 4. 走一遍

1. 「我的简历」上传一份 PDF（可选，但传了出题会更贴合你的经历）
2. 「开始面试」选岗位、方向、难度 → 点开始
3. 等 5-15 秒出第一题，回答后提交，看评分和下一题
4. 中途可以直接关掉页面 —— 回「面试记录」点「继续面试」能接着答
5. 答完 10 题或点「结束面试」→ 自动跳复盘页

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

## 目录结构

```
src/main/java/com/ke/nhservice/aimianshi/
├── graph/          图引擎（纯通用，禁止 import biz/controller/wrapper）
├── wrapper/        第三方封装（llm / pdf）
├── biz/            业务（interview / resume / user / knowledge）
├── controller/     REST 接口
└── common/         工具、异常、常量、配置、DTO

src/main/resources/
├── application.yml 配置（含话题池）
├── schema.sql      建表脚本，启动自动执行
├── prompts/        8 个提示词（改提示词不用改 Java）
└── static/         6 个页面
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
GET    /api/interview/{id}/state
GET    /api/interview/list
GET    /api/interview/{id}/detail   复盘详情
GET    /api/interview/{id}/trace    节点轨迹
```

## 几个已知的取舍

| 取舍 | 说明 |
|---|---|
| LLM 失败重试 4 次，间隔 5s/10s/20s/40s | 最坏情况光等待 75 秒。前端 loading 文案已写明 |
| 评分失败不中断面试 | 兜底为 `CONTINUE`，本题按 0 分记，已答的题都保住 |
| 出题失败 → 游标停在 `question` | 状态已落库，修好后调 `/resume` 就能接着跑 |
| 无流式输出 | 事件驱动架构的必然结果。要加就让 `/answer` 单独返回 SSE |
| token 无法主动失效 | 登出只是前端删 token。单用户自用够用 |
| 知识库只留接口不实现 | 一期 YAGNI，二期接国产 embedding API |
| 无注册流程 | 账号直接建库 |

## 二期计划

复盘页的雷达图、分数趋势图、图路径可视化、话题覆盖度。
数据（五维评分、`t_graph_trace`）一期就已经采全了，二期只负责画。
````

- [ ] **Step 2: 提交**

```bash
git add README.md
git commit -m "docs: 项目 README"
```

---

## Task 29: 收尾 —— 全量验收与推送

- [ ] **Step 1: 确认没有临时验证类残留**

```bash
git ls-files | grep -iE "smoketest|Test\.java" || echo "干净：没有残留的临时验证类"
```
Expected: `干净：没有残留的临时验证类`

- [ ] **Step 2: 确认分层约束没被破坏**

`graph/` 包不许 import `biz/`、`controller/`、`wrapper/`：

```bash
grep -rn "import com.ke.nhservice.aimianshi.\(biz\|controller\|wrapper\)" src/main/java/com/ke/nhservice/aimianshi/graph/ \
  && echo "❌ 违规" || echo "✅ graph 包保持纯净"
```
Expected: `✅ graph 包保持纯净`

- [ ] **Step 3: 确认没有密钥被提交**

```bash
git ls-files -z | xargs -0 grep -lE "sk-[a-zA-Z0-9]{20,}" 2>/dev/null && echo "❌ 发现疑似 key" || echo "✅ 无硬编码密钥"
```
Expected: `✅ 无硬编码密钥`

- [ ] **Step 4: 完整打包**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml clean package
```
Expected: `BUILD SUCCESS`，`target/ai-mianshi-0.0.1-SNAPSHOT.jar` 生成。

- [ ] **Step 5: 用打出来的 jar 独立跑一遍**

```bash
java -jar target/ai-mianshi-0.0.1-SNAPSHOT.jar
```
浏览器完整走一场面试。
Expected: 静态页面、接口、数据库读写全部正常 —— 说明打成 jar 之后提示词和前端资源都被正确打进去了。

- [ ] **Step 6: 推送**

```bash
git add -A
git commit -m "chore: 一期完成" --allow-empty
git push origin main
```

- [ ] **Step 7: 对照验收标准自查**

设计文档 11.1 的验收标准：

- [ ] 能真实完成一场 10 题面试
- [ ] 中途关页面再回来能继续
- [ ] 结束后能看到逐题评分与决策链

三条都满足，一期即完成。