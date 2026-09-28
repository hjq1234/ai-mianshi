# AI 面试系统 · 设计文档

> 日期：2026-09-28
> 项目路径：`D:\ideaProjects\ai-mianshi`
> 状态：待实现

---

## 一、项目概述

一个基于 **Spring Boot + 手写图引擎** 的多轮 AI 模拟面试系统。核心是用「图编排」的方式表达面试流程：出题 → 答题 → 评分 → 根据评分动态决定下一步（追问 / 继续 / 降难度 / 换话题）→ 循环 → 生成报告。

### 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 框架 | Java 21 + Spring Boot 4.1.1 |
| Web | `spring-boot-starter-webmvc` |
| 数据库 | SQLite（`sqlite-jdbc`） |
| 数据访问 | `JdbcTemplate`（手写 SQL，零魔法） |
| 大模型 | DeepSeek（OpenAI 兼容协议，可配置切换厂商） |
| PDF 解析 | Apache PDFBox |
| 密码 | `spring-security-crypto`（仅 BCrypt，不引 Spring Security 全家桶） |
| 前端 | Vue 3 CDN 版（无 node/npm 构建链） |
| 图表（二期） | ECharts CDN 版 |
| 测试 | JUnit 5 + Mockito + `@SpringBootTest` |

### 核心特性

1. **图编排的面试流程** —— 面试走向由实时评分驱动，不是固定题库
2. **事件驱动 + 状态落库** —— 无阻塞线程、无心跳、服务重启不丢数据
3. **天然断点续传** —— 用户随时可以离开，随时可以继续
4. **完整复盘** —— 记录图在每一轮的决策，可视化展示面试如何被表现影响
5. **简历个性化** —— 基于上传的 PDF 简历出题

---

## 二、关键决策记录

| # | 决策 | 理由 |
|---|---|---|
| 1 | 模型用 DeepSeek（OpenAI 兼容协议） | 国内直连、便宜、支持 function calling。只改 `base_url` + `model` 即可换通义/豆包/Kimi |
| 2 | **手写轻量图引擎**，不引现成库 | Java 生态没有 Eino Graph 的对等物；spring-ai-alibaba-graph 依赖重、学习成本高。手写 400 行可完全掌控，学的是本质不是用法 |
| 3 | **事件推进 + 状态落库**，不做阻塞式长连接 | Java 线程比 Go goroutine 贵；阻塞式需心跳 + 内存 session + 并发保护，且重启丢数据 |
| 4 | 知识库**只留接口不实现** | YAGNI。选型定国产 embedding API，但一期不接 |
| 5 | 完整多轮模拟面试 | 一次性生成预测题用不上图编排 |
| 6 | 简单登录（BCrypt + HMAC 自签 token） | 登录是样板代码，与图编排学习目标无关，做到够用即可 |
| 7 | 前端 Vue 3 CDN 版 | 「前后端写一起」+「简单点」，避免引入 node 构建链，一个 `mvn package` 出一个 jar |
| 8 | 简历 PDF 上传 + PDFBox 解析 | 最贴近真实场景 |
| 9 | 数据访问用 JdbcTemplate | 零魔法、零额外依赖、SQLite 兼容性最好、每条 SQL 可见 |
| 10 | 包结构 `controller` / `wrapper` / `biz` / `graph` / `common` | 沿用使用者熟悉的 Java 分层习惯 |
| 11 | 复盘分两期 | 一期做核心主流程 + 基础复盘；二期加图表。但**数据一期就采全**，二期只画图 |
| 12 | LLM 重试：首次 + 4 次重试，间隔 5s/10s/20s/40s | 指数退避，只重试可重试错误 |
| 13 | 「退出」与「结束」拆成两个操作 | 关页面 ≠ 结束面试，前者状态完好可继续 |

---

## 三、整体架构

### 包结构

```
com.ke.nhservice.aimianshi
├── controller/                 REST 接口（最外层）
├── wrapper/                    第三方封装（业务代码不直接依赖外部 SDK）
│   ├── llm/                      DeepSeek 客户端 + 重试
│   └── pdf/                      PDFBox 封装
├── biz/                        业务层
│   ├── interview/
│   │   ├── flow/                  流程图定义
│   │   ├── node/                  各节点实现
│   │   ├── prompt/                提示词加载
│   │   ├── trace/                 GraphListener 的落库实现
│   │   └── InterviewState         业务状态
│   ├── resume/
│   ├── knowledge/                 Retriever 接口 + EmptyRetriever
│   └── user/
├── graph/                      图引擎（纯通用）
└── common/                     工具、异常、常量、配置
    └── dto/                       ← DTO 按使用者要求放这里
```

### 分层约束（硬性）

> **`graph/` 包不得 import `biz/`、`controller/`、`wrapper/` 的任何类型。**

`graph/` 只认识泛型 `S`，不认识「面试」。这保证：
- 图引擎可脱离 Spring 单独测试
- 将来做别的流程（如简历预测题）可直接复用

跨切面需求（如记录执行轨迹）通过 `GraphListener` 接口反转依赖，而非让引擎依赖业务。

---

## 四、图引擎设计

### 核心 API

```java
// 节点：进状态，出结果
public interface Node<S> {
    NodeResult execute(NodeContext ctx, S state);
}

// 节点返回给引擎的指令（只有 2 种）
public sealed interface NodeResult {
    record Next()    implements NodeResult {}   // 继续沿边走
    record Suspend() implements NodeResult {}   // 挂起：存状态，退出
}

// 条件分支：给定状态，决定下一个节点名
public interface BranchCondition<S> {
    String decide(S state);
}

// 执行期上下文：携带服务依赖（LLM 客户端等），与状态无关
public class NodeContext {
    // 注入 LlmClient、Retriever、PromptLoader 等
}

// 图构建器
public class Graph<S> {
    Graph<S> addNode(String name, Node<S> node);
    Graph<S> addEdge(String from, String to);                        // 无条件边
    Graph<S> addBranch(String from, BranchCondition<S> cond, Set<String> targets);
    Graph<S> startAt(String name);
    Graph<S> endAt(String name);
    Graph<S> listener(GraphListener<S> listener);                    // 可选
    CompiledGraph<S> compile(int maxSteps);                          // 防死循环
}

// 执行游标：引擎的「走到哪了」，与业务状态分开存
public class Execution<S> {
    S state;         // 业务状态（序列化为 JSON 存库）
    String cursor;   // 引擎游标：下次从哪个节点继续
}

public enum RunStatus { FINISHED, SUSPENDED, FAILED, STEP_LIMIT }

public record RunResult<S>(RunStatus status, String stoppedAt, S state, Throwable error) {
    public static <S> RunResult<S> finished(String at, S state) { ... }
    public static <S> RunResult<S> suspended(String at, S state) { ... }
    public static <S> RunResult<S> failed(String at, S state, Throwable e) { ... }
    public static <S> RunResult<S> stepLimit(String at, S state) { ... }
}
```

### ★ 挂起机制（事件驱动的核心）

**挂起 = 保存现场然后退出，不是停在那儿等。**

节点自己判断「能不能继续」：

```java
public class WaitAnswerNode implements Node<InterviewState> {
    @Override
    public NodeResult execute(NodeContext ctx, InterviewState state) {
        if (state.getAnswer() == null || state.getAnswer().isBlank()) {
            return new NodeResult.Suspend();    // 还没答案 → 挂起
        }
        return new NodeResult.Next();           // 有答案了 → 往下走
    }
}
```

引擎主循环：

```java
for (int step = 0; step < maxSteps; step++) {
    Node<S> node = nodes.get(execution.cursor);
    listener.onNodeEnter(execution.cursor, execution.state);
    long t0 = System.currentTimeMillis();

    NodeResult r = node.execute(ctx, execution.state);   // 抛异常则捕获 → FAILED

    listener.onNodeExit(execution.cursor, r, System.currentTimeMillis() - t0, execution.state);

    if (r instanceof NodeResult.Suspend) {
        return RunResult.suspended(execution);           // 调用方负责存库
    }
    String next = nextOf(node, execution.state);         // 走边 or 问分支
    listener.onBranchDecided(execution.cursor, next, execution.state);
    execution.cursor = next;
}
// 循环跑满仍未结束 → 疑似死循环。不抛异常，显式返回状态由调用方处理
return RunResult.stepLimit(execution);
```

**三个好处：**
1. **没有线程被占住** —— 请求结束即释放
2. **天然幂等** —— 节点是「看状态决定」，不是「计数器」，重复执行安全
3. **不需要心跳、session 管理器、并发锁** —— 代码量比阻塞式少一半

### 错误处理

| 场景 | 处理 |
|---|---|
| 节点抛异常 | 引擎捕获 → `RunStatus.FAILED` → **状态已存库，不丢** |
| 超 `maxSteps` | 返回 `RunStatus.STEP_LIMIT`，不抛异常；trace 表可见卡在哪个节点 |
| 分支返回未声明的目标 | **直接抛 `GraphException`**，不转成 `FAILED` |

最后一行是刻意的：分支条件返回了 `addBranch` 未声明的目标，属于**代码 bug**（条件和目标集不同步），
`compile()` 没法提前校验（它无法预知条件会返回什么）。若转成 `FAILED`，接口层会告诉用户
「进度已保存，可稍后继续」，但重试多少次都是同一个 bug，等于误导。快速失败更好排查。
| 终止 | 不用 `NodeResult`，用 `state.shouldStop` + 分支路由到 end |

### 外部干预流程

`Execution.cursor` 存在库中且外部可改，因此可以直接跳转节点。例如「用户点结束面试」：

```java
Execution<InterviewState> exec = dao.load(recordId);
exec.setCursor("end_loop");                 // 直接跳到结束节点
exec.getState().setShouldStop(true);
graph.run(exec);
```

### GraphListener（轨迹记录，不污染引擎）

```java
public interface GraphListener<S> {
    default void onNodeEnter(String node, S state) {}
    default void onNodeExit(String node, NodeResult result, long costMs, S state) {}
    default void onBranchDecided(String from, String decided, S state) {}
    default void onSuspend(String node, S state) {}
}
```

默认空实现，业务层注入写库实现（`biz/interview/trace/TraceRecorder`）。

---

## 五、面试流程图

### 图结构

```
START
  │
  ▼
start ──────────── 初始化：questionIndex = 1，塞入简历摘要
  │
  ▼
question ───────── 调 LLM 生成问题
  │                 （prompt = nextActionHint + 简历摘要 + 最近2题历史）
  ▼
wait_answer ────── ★ 全图唯一的挂起点
  │
  ▼
evaluate ───────── 调 LLM 评分 → EvalResult（含 nextAction）
  │
  ├──[ EvaluateBranch ]──┬──→ deepen    ┐
  │                      ├──→ continue  │
  │                      ├──→ lower     ├──→ question   ← 普通边，形成循环
  │                      ├──→ switch    ┘
  │                      └──→ end ──→ END
```

### 节点清单

| 节点 | 类型 | 职责 |
|---|---|---|
| `start` | 普通 | `questionIndex = 1`；把简历摘要写入 state |
| `question` | 普通 | 拼 prompt → 调 LLM 生成问题 → `state.questionText` |
| `wait_answer` | **挂起** | `answer` 空 → `Suspend`；有值 → `Next` |
| `evaluate` | 普通 | 调 LLM 评分 → `state.evalResult`；写一条 `t_interview_dialogue` |
| `deepen` / `continue` / `lower` / `switch` | 普通 | 设 `nextActionHint` + `questionIndex++` |
| `end` | 普通 | 调 LLM 生成综合报告；标 `finished` |

### 分支条件（全图仅一处）

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

*抽成独立纯函数是因为 `EvaluateNode` 落库 `next_action` 时要用同一套判断——各写一份会导致 DB 记录与实际分支不一致。*

**设计说明：** 相比参考项目（Go 版 Eino 实现）的两处分支判断（一处 `>=`、一处 `>`，易被误读为 off-by-one），本设计**只保留一处判断**。题数检查放在 `evaluate` 之后（此时 `questionIndex` 恰为刚问完那题）；`deepen` 等分支节点只递增 `questionIndex`、不设 `shouldStop`，因此回到 `question` 无需再判断。

### 评分阈值兜底

`nextAction` 优先用 LLM 的判断；LLM 未给或给的不合法时按分数兜底：

```java
public NextAction inferNextAction(double score) {
    if (score >= 8.0) return NextAction.DEEPEN;   // 答得好 → 往深里追
    if (score <  4.0) return NextAction.LOWER;    // 答得差 → 降难度
    return NextAction.CONTINUE;                    // 中等 → 同话题换角度
}
```

`SWITCH`（换话题）不靠分数触发，靠 `TopicTracker`——当前话题覆盖度足够或连续追问次数达上限时触发。

**话题池从哪来**：`start` 节点根据 `domain` 从配置文件读取预定义的话题列表，写入 `TopicTracker`：

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

*实现时把话题池合并进了 `application.yml`，避免为读一个独立 yml 引入额外依赖。*

**为什么从配置读而不是让 LLM 现场生成**：话题池决定了整场面试的覆盖面，需要稳定可控、可人工调整，而且省掉一次 LLM 调用。`switch` 时 `TopicTracker` 从未覆盖的话题中挑选。

`TopicTracker` 的累积状态（已覆盖话题、当前话题、连续追问计数）随 `InterviewState` 一起序列化进 `state_json`，因此跨请求保持。

### 四个分支节点合并为一个类

```java
public class SetHintNode implements Node<InterviewState> {
    private final Function<InterviewState, String> hintBuilder;
    private final int difficultyDelta;      // +1 升档 / -1 降档 / 0 不变

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
        state.setQuestionIndex(state.getQuestionIndex() + 1);
        return new NodeResult.Next();
    }
}
```

图定义：

```java
graph.addNode("deepen",   new SetHintNode(s -> promptLoader.load("hint_deepen"),   +1));
graph.addNode("continue", new SetHintNode(s -> promptLoader.load("hint_continue"),  0));
graph.addNode("lower",    new SetHintNode(s -> promptLoader.load("hint_lower"),    -1));
graph.addNode("switch",   new SetHintNode(s -> {
    String topic = s.getTopicTracker().suggestNextTopic();
    s.getTopicTracker().setCurrentTopic(topic);
    return promptLoader.load("hint_switch").formatted(topic);
}, 0));
```

**难度档位规则**：三档 `简单 < 中等 < 困难`，`Difficulty.shift()` 会自动夹紧边界（困难再升仍是困难，简单再降仍是简单）。初始值取会话配置的难度。

**注意时序**：`evaluate` 节点写 `t_interview_dialogue` 时，分支节点**尚未执行**，因此记录的 `difficulty` 是**本题**的难度，而非下一题的。这正是复盘需要的信息。

**对比参考项目的四个近似重复函数（约 80 行），本设计约 20 行**，且新增分支只需加一行 `addNode` + 一个提示词文件。

### 提示词外置

放 `src/main/resources/prompts/`，不硬编码进 Java 文件：

```
prompts/
├── question_first.md      第一题
├── question_followup.md   后续题（占位符 {nextActionHint}）
├── hint_deepen.md
├── hint_continue.md
├── hint_lower.md
├── hint_switch.md         占位符 {topic}
├── evaluate.md            评分（要求返回 JSON）
└── report.md              综合报告
```

改提示词无需重新编译。**提示词本质是配置，不是代码。**

### 各维度评分

`EvalResult`：

```java
public class EvalResult {
    double overall;                       // 总分 1-10
    Map<String, Double> dimensions;       // 各维度得分
    List<String> coveredTopics;           // 覆盖的知识点
    String comment;                       // LLM 评语
    NextAction nextAction;                // 下一步决策
}
```

五个维度（决定了 `evaluate.md` 的输出格式，也是二期雷达图的数据源）：

| 维度 key | 考察什么 |
|---|---|
| `accuracy` | 技术点是否正确，有无硬伤 |
| `depth` | 是否讲到原理层面 |
| `clarity` | 表达是否清晰有条理 |
| `practice` | 是否结合真实项目经验 |
| `problemSolving` | 分析问题的思路是否正确 |

### InterviewState（业务状态）

```java
public class InterviewState {
    // 会话
    Long recordId;
    String sessionId;

    // 当前轮次
    int questionIndex;                // 从 1 开始
    String currentDifficulty;         // 本题难度，初始 = 会话难度，被 deepen/lower 调整
    String questionText;
    String answer;                    // 挂起后由事件塞入
    EvalResult evalResult;

    // 分支节点写入的「下一题方向」
    String nextActionHint;

    // 累积状态（跨轮次）
    List<Dialogue> dialogues;
    ScoreHistory scoreHistory;
    TopicTracker topicTracker;
    List<HistoryItem> recentHistory;  // 滑动窗口，保留最近 2 题

    // 控制
    boolean shouldStop;
    int maxQuestions;                 // 默认 10
    String error;
}
```

**滑动窗口说明**：`recentHistory` 只保留最近 2 题作为上下文。**Token 是按量计费的**，历史全带上会让 prompt 随轮次线性膨胀。

---

## 六、数据模型

### 表结构

```sql
CREATE TABLE t_user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT    NOT NULL UNIQUE,
    password_hash TEXT    NOT NULL,          -- BCrypt
    nickname      TEXT,
    created_at    INTEGER NOT NULL           -- epoch millis
);

CREATE TABLE t_resume (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    filename    TEXT    NOT NULL,
    content     TEXT    NOT NULL,            -- PDF 解析出的纯文本
    is_default  INTEGER NOT NULL DEFAULT 0,  -- 0/1
    created_at  INTEGER NOT NULL
);

CREATE TABLE t_interview_record (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id      INTEGER NOT NULL,
    resume_id    INTEGER,
    position     TEXT,                       -- 岗位，如「Java 后端」
    company      TEXT,                       -- 目标公司
    domain       TEXT,                       -- 技术方向，如「Java」
    difficulty   TEXT,                       -- 简单 / 中等 / 困难
    status       TEXT    NOT NULL,           -- in_progress | finished
    total_score  REAL,
    report       TEXT,                       -- 综合报告（end 节点生成）

    -- ★★ 图引擎的两个关键字段
    state_json   TEXT,                       -- InterviewState 序列化快照
    cursor       TEXT,                       -- 引擎游标

    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL
);

CREATE TABLE t_interview_dialogue (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    record_id     INTEGER NOT NULL,
    seq           INTEGER NOT NULL,          -- 第几题
    topic         TEXT,                      -- 本题话题
    difficulty    TEXT,                      -- 本题难度
    question      TEXT    NOT NULL,
    answer        TEXT,
    score         REAL,
    eval_json     TEXT,                      -- 五维明细 + 评语

    -- ★★ 图的状态流转
    next_action   TEXT,                      -- deepen|continue|lower|switch|end
    next_topic    TEXT,                      -- 走 switch 时换到的话题

    created_at    INTEGER NOT NULL
);

CREATE TABLE t_graph_trace (
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

CREATE INDEX idx_dialogue_record ON t_interview_dialogue(record_id, seq);
CREATE INDEX idx_trace_record    ON t_graph_trace(record_id, seq);
CREATE INDEX idx_record_user     ON t_interview_record(user_id, created_at DESC);
```

### 冗余设计说明：`t_interview_dialogue` 与 `state_json`

`state_json` 的 `dialogues` 字段与 `t_interview_dialogue` 存在数据重叠，**这是刻意为之**：

| | `state_json` | `t_interview_dialogue` |
|---|---|---|
| 使用者 | 只有图引擎 | 展示层、历史列表、复盘 |
| 用途 | 恢复执行现场 | 查询、排序、分页 |
| 结构 | 随引擎演进而变 | 稳定 |
| 对外暴露 | 否 | 是 |

若只保留 `state_json`，查询面试记录列表需反序列化每场的完整 JSON（慢），且展示层会被引擎内部结构绑死——状态类一改，页面全崩。代价是每轮写两次库，单用户场景下可忽略。

### `next_action` 的写入时机

`evaluate` 节点写这条记录。**图的决策不需要额外计算**——`nextAction` 本就是评分结果的一部分（`EvalResult.nextAction`）。

`EvaluateBranch` 也是读 `state.evalResult.getNextAction()` 做路由的，因此**落库值与实际走的分支不可能不一致**，同时分支条件保持纯函数（不写库）。

### SQLite 注意事项

- **开 WAL 模式**：`PRAGMA journal_mode=WAL`（连接时执行一次），否则写是全库锁
- **时间存 `INTEGER`**（epoch 毫秒）：排序、比较、索引更方便，无时区格式问题
- **布尔存 `INTEGER` 0/1**：SQLite 无布尔类型，在 `RowMapper` 中转换
- **建表用 `resources/schema.sql`** + `spring.sql.init.mode=always`，启动自动建表。不引 Flyway/Liquibase（此规模属过度设计）

### 知识库

一期**不建表**，只提供接口：

```java
public interface Retriever {
    List<Chunk> retrieve(String query, int topK);
}
```

`EmptyRetriever` 实现永远返回空列表。二期接入国产 embedding API 时新增实现类，业务代码不动。

---

## 七、API 接口

```
── 认证 ──
POST   /api/auth/login          {username, password}  → {token, nickname}
POST   /api/auth/logout
GET    /api/auth/me

── 简历 ──
POST   /api/resume/upload       multipart/form-data   → {resumeId, preview}
GET    /api/resume/list
POST   /api/resume/{id}/default
DELETE /api/resume/{id}

── 面试 ──
POST   /api/interview/start     {resumeId, position, company, domain, difficulty}
                                → {recordId, question, questionIndex, total}
POST   /api/interview/{id}/answer   {answer}
                                → {score, dimensions, comment, nextAction, question, ...}
                                → 或 {finished: true, report: "..."}
POST   /api/interview/{id}/finish   → {report}          # 用户主动结束
POST   /api/interview/{id}/resume   → 从 cursor 推进，无需新答案
GET    /api/interview/{id}/state    → 当前状态（断点续传 / 刷新页面）

── 记录与复盘 ──
GET    /api/interview/list          → 面试记录列表（分页）
GET    /api/interview/{id}/detail   → 复盘详情：逐题 + 评分 + 决策链   [一期]
GET    /api/interview/{id}/trace    → 节点轨迹                        [二期图表用]
```

### 认证方案

不引 Spring Security，不建 token 表。用 JDK 自带的 HMAC 自签：

```
token = base64( userId + "." + expireAt + "." + hmacSha256(userId + "." + expireAt, secret) )
```

约 15 行，零依赖，服务重启后 token 依然有效（密钥在配置中）。

**已知限制**：无法主动失效（登出仅前端删除 token）。单用户自用场景可接受。

### 「退出」与「结束」的区分

| 用户行为 | 系统动作 | 记录状态 |
|---|---|---|
| 关闭页面 / 浏览器 | 什么都不做 | `in_progress`，状态完好，可继续 |
| 点「结束面试」 | `cursor` → `end`，跑 end 节点生成报告 | `finished` |
| LLM 挂 / 请求失败 | 游标推进到失败节点并落库，状态完好 | `in_progress`，可继续 |

**「继续面试」不需要专门的恢复逻辑**——现场全在 `state_json` + `cursor` 里，打开页面读一条记录即可。

前端判断逻辑：

```javascript
// 进面试页无条件调一次 /resume，它本身是幂等的：
//   游标在 wait_answer 且没答案 → 立刻挂起，原样返回当前题目（刷新页面走这条）
//   游标在 question / evaluate  → 真的往下推一步（上次 LLM 失败后重试走这条）
//   已结束                      → 返回 finished，前端跳复盘页
const turn = await resume(recordId);
if (turn.finished) → 跳复盘页
else               → 渲染答题界面
```

---

## 八、页面设计

| 页面 | 一期 | 二期 |
|---|---|---|
| `login.html` 登录 | ✅ | |
| `index.html` 首页：选简历/岗位/难度 → 开始面试 | ✅ | |
| `interview.html` 面试进行中（对话式） | ✅ | |
| `history.html` 面试记录列表（含「继续面试」入口） | ✅ | |
| `report.html` 复盘详情 | ✅ 基础版：逐题详情 + 决策链表格 + AI 综合报告 | ➕ 雷达图、分数趋势图、图路径可视化、话题覆盖度 |
| `resume.html` 简历管理 | ✅ | |

### 面试进行页交互（一期）

```
点「开始面试」
  → loading（「AI 面试官正在准备第一个问题...」）
  → 显示问题
  → 用户输入答案 → 点「提交」
  → loading（「面试官正在评估...」）
  → 显示本题得分 + 评语 + 下一个问题
  → 循环至 10 题
  → 跳复盘页

顶部常驻：第 3/10 题 · 累计得分 · 「结束面试」按钮
```

**已知体验取舍**：LLM 生成问题需 5~15 秒，期间无流式打字效果（选择事件驱动而非 SSE 长连接的必然结果）。

若后续要加，两个办法且都不影响接口形状：
- 让 `/answer` 单独返回 SSE 流（外层仍事件驱动，仅单次请求内部流式）
- 或前端加打字机动画

### 复盘页（二期完整形态）

```
┌─────────────────────────────────────────────────────────────┐
│  Java 后端 · 中等难度 · 2026-09-28       总分 7.2 / 10       │
│  10 题 · 用时 23 分钟 · 覆盖 4 个话题                        │
├──────────────────────┬──────────────────────────────────────┤
│  ① 能力雷达图        │  ② 分数趋势折线图                     │
│     五个维度         │     每题得分曲线 + 分支决策标记        │
├──────────────────────┴──────────────────────────────────────┤
│  ③ 图执行路径可视化（由 t_graph_trace 还原真实路径）         │
│     START → start → question → wait ⟲ evaluate → deepen → … │
├─────────────────────────────────────────────────────────────┤
│  ④ 话题覆盖度                                                │
│     JVM内存 ████████░░ 6题 均分7.1                          │
├─────────────────────────────────────────────────────────────┤
│  ⑤ 逐题详情（可展开）：问题 / 回答 / 五维得分 / 评语 / 分支  │
├─────────────────────────────────────────────────────────────┤
│  ⑥ AI 综合报告 + 改进建议                                    │
└─────────────────────────────────────────────────────────────┘
```

图表用 ECharts CDN 版：`radar`（①）、`line`（②）、`graph`（③）三个系列，一个库全包。

### 一期复盘页形态

逐题详情 + 决策链表格 + AI 综合报告（③⑥ 的文字版）：

```
题号  话题        难度  得分  图的分支决策
 1    JVM 内存   中等  8.5   → deepen    答得好，往原理深挖
 2    JVM 内存   困难  3.0   → lower     答得差，降回基础
 3    JVM 内存   中等  6.0   → continue  中等，同话题换角度
 4    JVM 内存   中等  6.5   → switch    话题聊透，换到并发
```

---

## 九、错误处理与重试

### LLM 重试策略

**首次失败后重试 4 次，共最多 5 次调用**，间隔指数退避：

```java
private static final long[] BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L};

for (int i = 0; ; i++) {
    try {
        return delegate.chat(prompt);
    } catch (RetryableException e) {
        if (i >= BACKOFF_MS.length) throw e;
        sleep(BACKOFF_MS[i]);      // 5s → 10s → 20s → 40s
    }
}
```

**只重试可重试错误**：超时、连接失败、5xx。**4xx 不重试**（参数错误重试无意义）。

**已知影响**：4 次重试光等待即 75 秒，加上调用耗时，最坏情况单次请求超 2 分钟。一期缓解办法：前端 loading 文案写明「AI 服务繁忙时会自动重试，最长约 1.5 分钟，请勿刷新」。

### 各场景错误处理

| 场景 | 处理 |
|---|---|
| LLM 调用超时/失败 | 按上述策略重试；全部失败则节点内降级 |
| 评分节点失败 | `nextAction` 兜底为 `CONTINUE`，继续下一题，不中断面试 |
| 出题节点失败 | 记 `state.error`，`shouldStop = true` → 路由到 `end` → 生成部分报告 |
| 图执行抛异常 | 引擎捕获 → `RunStatus.FAILED` → 状态已存库，不丢 |
| 超 `maxSteps` | 返回 `RunStatus.STEP_LIMIT`；trace 表可见卡在哪个节点 |
| SQLite 写冲突 | 已开 WAL；写失败重试 2 次 |

**贯穿原则：任何一步出错，已产生的前几轮问答必须保住。** 由「每次挂起即落库 `state_json`」实现——最坏情况用户答了 5 题崩溃，重新打开仍能看到那 5 题记录并继续。

---

## 十、测试策略

**一期不写自动化测试**（使用者明确要求）。验证方式是编译通过 + 启动应用 + 手工走完一场面试。

图引擎本身是纯逻辑、无外部依赖，是最适合补测试的部分。若将来要补，优先级为：图引擎主循环（挂起/恢复/超步数）> 分支条件 > 各节点（Mock `LlmClient`）。

---

## 十一、实施分期

### 一期（核心主流程）

1. 图引擎（`graph/` 包）
2. 数据表 + `schema.sql` + DAO
3. LLM 客户端封装（DeepSeek + 重试）
4. 面试流程：`InterviewState` + 8 个节点 + 图定义 + 提示词
5. 面试相关接口（start / answer / finish / resume / state）
6. 登录 + 简历上传解析
7. 基础复盘页（逐题详情 + 决策链表格 + 综合报告）
8. 页面：login / index / interview / history / resume / report(基础版)

**验收标准**：能真实完成一场 10 题面试，中途关页面再回来能继续，结束后能看到逐题评分与决策链。

### 二期（复盘增强）

1. `report.html` 加雷达图、分数趋势图、图路径可视化、话题覆盖度
2. `/api/interview/{id}/trace` 接口
3. （可选）知识库接入国产 embedding API
4. （可选）流式输出

**数据一期就采全**（`t_graph_trace`、五维评分），二期只负责画图。

---

## 十二、明确不做的事

| 不做 | 原因 |
|---|---|
| 知识库向量检索 | 一期留接口，二期再说 |
| Spring Security 全家桶 | 登录只需 BCrypt + HMAC token |
| node/npm 构建链 | Vue 3 CDN 版足够 |
| Flyway / Liquibase | `schema.sql` 对当前规模够用 |
| 权限模型 / RBAC / 角色管理 | 登录只做身份识别（`t_user` 一表足够），不做角色与授权 |
| 注册流程 / 邮箱验证 / 找回密码 | 账号直接建库，够自己用即可 |
| SSE / WebSocket 流式 | 与事件驱动架构冲突，一期不做 |
| 阻塞式长连接 + 心跳 | 本设计的核心就是替代它 |