# 简历修改建议 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 加一个「改简历」功能：对着目标岗位（可多条）给出逐条修改建议 + 一份改写后的参考稿，存下来可回看、可导出 Markdown。

**Architecture:** 一次 LLM 调用产出一份**纯 Markdown**（主稿 + 每岗位一节差异建议，批注写成 `> 建议：` 行贴在原位），落库一份；页面上的「建议列表」和「干净参考稿」都由后端从它**现派生**（`ResumeReviewParser` 一处实现）。新增一张 `t_resume_review`，一张新页面 `resume-review.html`，入口在简历页的行按钮。`graph/` 包一行不动。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / SQLite + JdbcTemplate / Vue 3 CDN 版 / SLF4J

**设计文档：** `docs/superpowers/specs/2026-09-30-resume-review-design.md`

**用户既有约束（必须遵守）：**

- 仓库里**不写 JUnit**。验证程序是能直接 `main` 跑的，放 `src/test/java/.../checks/`
- **只 commit，不 push**。每个 Task 一条本地提交，**只列显式文件路径**（永不 `git add -A`）
- **`src/main/resources/application.yml` 永不提交**（里面有刻意保留的本地 `base-url` / `model` / `max-questions` 改动）。本计划**不需要改它**——`max-tokens` 只在读的那一端加默认值（见 Task 1）
- **不为了验证起服务**。改完只编译一次
- 全程中文

---

## Context

现在系统只能**读**简历（PDF 抽文本 → 出题时当背景资料），不能帮他**改**。而简历是面试的第一道关。

**这条链上最容易踩的三件事（前两件已在设计阶段核实过）：**

1. **产物是纯 Markdown，不是 JSON。** `EvalResultParser.extractJson` 取的是**第一个 `{` 到最后一个 `}`**（`EvalResultParser.java:43-44`），响应被截掉尾巴时整次生成全废。而「一整份改写稿」恰恰是最容易撞输出长度上限的输出。Markdown 的截断只是**降级**（前面的建议都在）。
2. **`max_tokens` 现在根本没发。** `LlmProperties` 没有这个字段，`OpenAiCompatibleClient:48-52` 的 body 只有 `model`/`messages`/`temperature`/`stream`，所以输出上限是网关默认值——一个未知数。（200k 那个上下文窗口管的是**输入**能塞多少，输出多长是另一个参数。）
3. **`finish_reason` 拿到了也传不出来。** `LlmClient.chat()` 只返回 `String`（`OpenAiCompatibleClient:89-93` 拿完 `firstContent()` 就把整个响应丢了），`ChatCompletionResponse.Choice` 也只声明了 `message`。要报「被截断」就得把这条信息从最底层带到业务层——这是 Task 1 存在的全部理由。

**写计划时发现的两处设计文档偏差（以本计划为准）：**

- **不需要给 `InterviewEngine` 加只读方法。** 设计文档说「加只读方法复用 `listFinishedIdsWithAnswers` + `listDialoguesByRecords`」，实际上现成的 `engine.requireRecord(userId, id)` 和 `engine.dialogues(recordId)` 就够了——而且**这两个自带归属校验**（别人的 id 直接 404），比新写一个方法更安全。`listDialoguesByRecords` 的 SELECT 里**没有 `record_id` 列**（`InterviewDao:57-59` 的 `SELECT_DIALOGUE`），拿它拼不出「哪条属于哪场」。
- **`/api/resume-review` 这个独立前缀的理由要说准。** 设计文档写的是「避免和 `DELETE /api/resume/{id}` 抢路径」，严格说 `/api/resume/{id}` 和 `/api/resume/{id}/review` 并不冲突。真实理由是**语义分离**：`DELETE /api/resume-review/{id}` 和 `DELETE /api/resume/{id}` 是两个完全不同的东西（删改稿 vs 删简历），放同一个前缀下早晚看错。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `wrapper/llm/LlmClient.java` | 改 | 加 `Reply(content, finishReason)` + `chatDetailed()`；`chat()` 退化成 default |
| `wrapper/llm/ChatCompletionResponse.java` | 改 | `Choice` 加 `finish_reason` + `firstFinishReason()` |
| `wrapper/llm/OpenAiCompatibleClient.java` | 改 | 实现 `chatDetailed`；`maxTokens > 0` 才发 `max_tokens` |
| `wrapper/llm/RetryableLlmClient.java` | 改 | 实现 `chatDetailed`（重试逻辑只有一份） |
| `wrapper/llm/LlmProperties.java` | 改 | 加 `maxTokens`，**默认 0 = 不发** |
| `resources/schema.sql` | 改 | 新表 `t_resume_review` + 索引 |
| `biz/resume/ResumeReview.java` | 新建 | record，`t_resume_review` 一行的 Java 形态 |
| `biz/resume/ResumeReviewDao.java` | 新建 | insert / listByResume / findById / delete / deleteByResume |
| `biz/resume/ResumeReviewParser.java` | 新建 | **纯函数**：产物 → 建议列表 / 干净正文 / 条数；`targets_json` 读写 |
| `biz/resume/ResumeReviewMaterial.java` | 新建 | **纯函数**：目标岗位 + 几场面试 → 提示词里的两段文字 |
| `biz/resume/ResumeReviewService.java` | 新建 | 拼素材 → 调 LLM → 落库 → 派生详情 |
| `biz/resume/ResumeService.java` | 改 | `delete` 加事务 + 连带删改稿 |
| `controller/ResumeReviewController.java` | 新建 | 四个接口 |
| `common/dto/ResumeReviewTarget.java` | 新建 | `{title, jd}`，请求体和 `targets_json` 共用一个形状 |
| `common/dto/ResumeReviewRequest.java` | 新建 | 生成请求体 |
| `common/dto/ResumeReviewItemVO.java` | 新建 | 历史列表一行（不含 markdown） |
| `common/dto/ResumeReviewVO.java` | 新建 | 详情（含三个派生视图） |
| `resources/prompts/resume_review.md` | 新建 | 产物契约 |
| `resources/static/resume.html` | 改 | 行里加「改简历」按钮 |
| `resources/static/resume-review.html` | 新建 | 生成表单 + 历史 + 建议面板 + 参考稿 + 导出 |
| `resources/static/css/app.css` | 改 | `.target-row`（岗位名 + ✕ + JD 换行） |
| `src/test/java/.../checks/ResumeReviewCheck.java` | 新建 | 验「写错了不报错」的两件事：占位符漏传、产物解析 |
| `src/test/README.md` | 改 | 登记上面这个程序 |
| `README.md` | 改 | 接口 / 表 / 页面数 / 提示词数 / 取舍 |

---

## Task 1: 把 `finish_reason` 带出来 + `max-tokens` 旋钮

**Files:**
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmClient.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/ChatCompletionResponse.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/OpenAiCompatibleClient.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/RetryableLlmClient.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmProperties.java`

> Step 2 到 Step 4 之间**编译不过是正常的**：接口加了抽象方法，两个实现要一起改完。
> 实现类全项目正好只有这两个（grep 过），`src/test/` 里没有任何 `LlmClient` 实现。

- [ ] **Step 1: `ChatCompletionResponse` 把 `finish_reason` 收进来**

整个文件改成：

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ke.nhservice.aimianshi.common.exception.NonRetryableException;

import java.util.List;

/**
 * OpenAI 兼容的 /chat/completions 响应。
 * 只声明用得到的字段，其余靠 @JsonIgnoreProperties 忽略——
 * 各家厂商都会塞一堆自己的扩展字段。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(List<Choice> choices) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(Message message,

                         // ★ 必须显式写 @JsonProperty。项目里没有配 property-naming-strategy
                         //   （grep 过：全项目一个 @JsonProperty 都没有），Jackson 走默认的 camelCase，
                         //   声明成 finishReason() 是**绑不上** "finish_reason" 的——
                         //   结果是 truncated 永远是 0、截断提示永不出现，而且不报任何错。
                         //   这个类现有的 role / content / message 都是单个词，看不出这个问题。
                         @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content) {
    }

    /** 取第一个 choice 的正文；结构不对就抛 NonRetryableException */
    public String firstContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).message() == null) {
            throw new NonRetryableException("LLM 响应结构异常：没有 choices[0].message", null);
        }
        return choices.get(0).message().content();
    }

    /**
     * 为什么停下。"length" = 撞到输出长度上限被截断。
     * 网关不给这个字段时返回 null，那种情况按「没截断」处理（不能反过来假设被截断了）。
     */
    public String firstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).finishReason();
    }
}
```

- [ ] **Step 2: `LlmClient` 加 `Reply` 和 `chatDetailed`**

整个文件改成：

```java
package com.ke.nhservice.aimianshi.wrapper.llm;

import java.util.List;

/**
 * 大模型客户端。业务代码只依赖这个接口，
 * 换厂商 = 换实现类，不动业务代码。
 */
public interface LlmClient {

    /**
     * 一次调用的完整结果：正文 + 为什么停下。
     *
     * 为什么要多这个方法：改简历那条链的产物长（一整份改写稿），最容易撞输出长度上限，
     * 而「被截断」必须能报出来——否则现象是「参考稿戛然而止」，会以为是模型笨。
     * 原先 chat() 只返回 String，这个信息在最底层就被丢掉了。
     */
    record Reply(String content, String finishReason) {

        /** 撞到输出长度上限。网关不一定给 finish_reason，给 null 时按「没截断」处理 */
        public boolean truncated() {
            return "length".equalsIgnoreCase(finishReason);
        }
    }

    Reply chatDetailed(List<ChatMessage> messages);

    default String chat(List<ChatMessage> messages) {
        return chatDetailed(messages).content();
    }

    /** 单轮对话的便捷方法：把 prompt 当作一条 user 消息 */
    default String chat(String prompt) {
        return chat(List.of(ChatMessage.user(prompt)));
    }
}
```

- [ ] **Step 3: `OpenAiCompatibleClient` 实现它，并支持 `max_tokens`**

改动两处。第一处是 payload（原 `OpenAiCompatibleClient.java:48-52`）：

```java
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", props.getModel());
        payload.put("messages", messages);
        payload.put("temperature", props.getTemperature());
        // 默认不传 max_tokens：网关的默认上限是多少我们不知道，硬设一个反而可能把它改小。
        // 要限制就在 yml 里显式配 app.llm.max-tokens
        if (props.getMaxTokens() > 0) {
            payload.put("max_tokens", props.getMaxTokens());
        }
        payload.put("stream", false);
```

第二处是方法签名和返回（原 `OpenAiCompatibleClient.java:41-42` 和 `89-93`）：

```java
    @Override
    public Reply chatDetailed(List<ChatMessage> messages) {
```
（方法体不变，直到最后几行）改成：

```java
        ChatCompletionResponse parsed = JsonUtil.fromJson(response.body(), ChatCompletionResponse.class);
        String content = parsed.firstContent();
        if (content == null || content.isBlank()) {
            throw new RetryableException("LLM 返回内容为空", null);
        }

        String finishReason = parsed.firstFinishReason();
        if ("length".equalsIgnoreCase(finishReason)) {
            // 这条日志要留着：页面上那条黄条就是靠这个字段，网关哪天改了行为这里能看出来
            log.warn("LLM 输出被长度上限截断 | prompt {} 字符 | completion {} 字符",
                    JsonUtil.toJson(messages).length(), content.length());
        }
        return new Reply(content, finishReason);
    }
```

- [ ] **Step 4: `RetryableLlmClient` 实现它，并删掉旧的 `chat` 覆写**

把 `RetryableLlmClient.java:31-46` 那个 `chat` 方法整个换成：

```java
    /**
     * 重试逻辑只有这一份。★ 不要在这里再覆写 chat(List)：
     * 接口上那个 default chat() 就是转发到这里的，
     * 另写一份等于同一个类里有两套重试代码，改一处漏一处。
     */
    @Override
    public Reply chatDetailed(List<ChatMessage> messages) {
        for (int attempt = 0; ; attempt++) {
            try {
                return delegate.chatDetailed(messages);
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
```

- [ ] **Step 5: `LlmProperties` 加 `maxTokens`**

字段区（原 `LlmProperties.java:10-15`）加一行：

```java
    /** 输出长度上限。0 = 不发这个字段（用网关的默认值）。见 OpenAiCompatibleClient 里的说明 */
    private int maxTokens = 0;
```

getter/setter 加在 `getTemperature` 那对旁边：

```java
    public int getMaxTokens() { return maxTokens; }

    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
```

**不要**动 `application.yml`（那个文件永不提交）。默认 0 意味着行为一点不变，需要时本地加一行 `app.llm.max-tokens: 8192` 即可。

- [ ] **Step 6: 编译**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q clean compile
```

预期：无输出（`-q` 下只有错误才打印），`target/classes` 生成。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmClient.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/ChatCompletionResponse.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/OpenAiCompatibleClient.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/RetryableLlmClient.java \
        src/main/java/com/ke/nhservice/aimianshi/wrapper/llm/LlmProperties.java
git commit -m "feat(llm): 带出 finish_reason 并加 max-tokens 旋钮（默认不发）"
```

---

## Task 2: 新表 + 模型 + DAO

**Files:**
- Modify: `src/main/resources/schema.sql`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReview.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewDao.java`

> **这张表不需要迁移**：它本来就不存在，`CREATE TABLE IF NOT EXISTS` 直接建。
> （上次给 `t_interview_record` 加 `deleted` 列是另一回事——那是对**已存在**的表加列，
> `CREATE TABLE IF NOT EXISTS` 整条跳过，才要靠 `SqliteInitializer` 的 `ALTER` 补。）

- [ ] **Step 1: `schema.sql` 加表**

`t_resume` 那段之后插入：

```sql
-- 简历改稿。一次生成一行，按简历留历史。
-- ★ 这一张是新表：CREATE TABLE IF NOT EXISTS 直接建就行，不需要像 deleted 列那样补 ALTER
CREATE TABLE IF NOT EXISTS t_resume_review (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER NOT NULL,
    resume_id        INTEGER NOT NULL,             -- 改的是哪份简历
    markdown         TEXT    NOT NULL,             -- LLM 全文，批注（> 建议：）留在原位
    suggestion_count INTEGER NOT NULL DEFAULT 0,   -- 写入时算一次，列表页不用拖全文
    targets_json     TEXT,                         -- [{"title":"Java 后端","jd":"…"}]，至少一条
    interview_ids    TEXT,                         -- 参考了哪几场（逗号分隔），没选为 NULL
    model            TEXT,                         -- 换模型后能看出这条是哪个生成的
    truncated        INTEGER NOT NULL DEFAULT 0,   -- 1 = 输出被长度上限截断
    created_at       INTEGER NOT NULL
);
```

索引加在文件末尾那几行索引旁边：

```sql
CREATE INDEX IF NOT EXISTS idx_review_resume ON t_resume_review(resume_id, created_at DESC);
```

- [ ] **Step 2: `ResumeReview.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

/**
 * 一次简历改稿。t_resume_review 一行的 Java 形态。
 *
 * markdown 是原始产物（批注在原位）；页面上的「建议列表」和「干净参考稿」都是拿它现派生的，
 * 派生逻辑只有 ResumeReviewParser 一处，这个 record 不掺和。
 *
 * targetsJson / interviewIds 在这层保持「库里的原样」（JSON 串、逗号分隔串），
 * 拆开是 parser 和 service 的事——DAO 只管存取，不做解释。
 */
public record ResumeReview(Long id,
                           Long userId,
                           Long resumeId,
                           String markdown,
                           int suggestionCount,
                           String targetsJson,
                           String interviewIds,
                           String model,
                           boolean truncated,
                           long createdAt) {
}
```

- [ ] **Step 3: `ResumeReviewDao.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/** 简历改稿的读写。和 t_resume 一样物理删：改稿是派生产物，没有任何聚合依赖它 */
@Repository
public class ResumeReviewDao {

    private static final RowMapper<ResumeReview> MAPPER = (rs, rowNum) -> new ResumeReview(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("resume_id"),
            rs.getString("markdown"),
            rs.getInt("suggestion_count"),
            rs.getString("targets_json"),
            rs.getString("interview_ids"),
            rs.getString("model"),
            rs.getInt("truncated") == 1,
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ResumeReviewDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long userId, Long resumeId, String markdown, int suggestionCount,
                       String targetsJson, String interviewIds, String model, boolean truncated) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO t_resume_review
                        (user_id, resume_id, markdown, suggestion_count, targets_json,
                         interview_ids, model, truncated, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, resumeId);
            ps.setString(3, markdown);
            ps.setInt(4, suggestionCount);
            ps.setString(5, targetsJson);
            ps.setString(6, interviewIds);
            ps.setString(7, model);
            ps.setInt(8, truncated ? 1 : 0);
            ps.setLong(9, System.currentTimeMillis());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入简历改稿后没有拿到自增主键");
        }
        return key.longValue();
    }

    /**
     * 某份简历的历史，新的在前。
     * ★ 不取 markdown（NULL AS markdown）：一行的全文可能上万字，
     *   而列表只要元信息。要全文走 findById。
     */
    public List<ResumeReview> listByResume(Long resumeId) {
        return jdbc.query("""
                SELECT id, user_id, resume_id, NULL AS markdown, suggestion_count,
                       targets_json, interview_ids, model, truncated, created_at
                FROM t_resume_review WHERE resume_id = ? ORDER BY created_at DESC
                """, MAPPER, resumeId);
    }

    public Optional<ResumeReview> findById(Long id) {
        return jdbc.query("""
                SELECT id, user_id, resume_id, markdown, suggestion_count,
                       targets_json, interview_ids, model, truncated, created_at
                FROM t_resume_review WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    /**
     * 删一份简历时连带删它的全部改稿。
     * ★ t_resume 是物理删、没有外键级联，不这么删就会留下孤儿行：
     *   按 resumeId 查永远查不到它们，但行还在库里占着地方。
     */
    public int deleteByResume(Long resumeId) {
        return jdbc.update("DELETE FROM t_resume_review WHERE resume_id = ?", resumeId);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM t_resume_review WHERE id = ?", id);
    }
}
```

- [ ] **Step 4: 编译**

同 Task 1 Step 6。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/schema.sql \
        src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReview.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewDao.java
git commit -m "feat(resume): 简历改稿的表、模型与 DAO"
```

---

## Task 3: 产物契约（提示词 + 解析）+ 检查程序

**Files:**
- Create: `src/main/resources/prompts/resume_review.md`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewParser.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewTarget.java`
- Create: `src/test/java/com/ke/nhservice/aimianshi/checks/ResumeReviewCheck.java`
- Modify: `src/test/README.md`

- [ ] **Step 1: `prompts/resume_review.md`**

```markdown
你是一位帮候选人改技术简历的资深面试官。请对着下面这些目标岗位，把这份简历改一遍。

【目标岗位】
{targets}

【候选人最近的面试表现】
{interviewMaterial}

【简历原文】
{resume}

输出要求（严格遵守）：

1. 先输出**一份主稿**：把简历按建议整体改写一遍，保持原有的段落顺序和结构
   （不要重排项目、不要合并章节）。主稿按所有目标岗位的**共性**来改。
   每个大块用 `## 章节名` 起标题（个人信息 / 教育背景 / 技能 / 工作经历 / 项目经历 …），
   顺序和原文一致 —— 简历原文是 PDF 抽出来的纯文本，本来就没有标题层级。

2. 每条修改建议单独成行，以 `> 建议：` 开头，**紧贴在它要改的那一段之前**。
   不要用代码块包裹，不要编号。

3. ★ 凡是你不确定的数字、指标、项目名、技术栈、职责范围，一律写成【方括号】占位，
   让候选人自己填真实的。**绝对不要编造看起来合理的数字** ——
   这份简历是要拿去投的，编出来的东西面试一问就穿帮。

4. 主稿之后加一节 `## 这份简历最该先改的三件事`，不超过三条，每条一行。

5. 然后**每个目标岗位各加一节** `## 投「<岗位名>」要额外改什么`，每节 2-4 条，
   同样是 `> 建议：` 开头的行。只写这个岗位特有的调整，不要重复主稿里已经说过的。
   岗位名用上面【目标岗位】里给的那个名字。

6. 不要开场白（「以下是改写后的简历」这类），直接从主稿正文开始。

各节顺序：主稿 → `## 这份简历最该先改的三件事` → 各岗位的额外调整节。
```

> 三个占位符 `{targets}` / `{interviewMaterial}` / `{resume}` **一个都不能漏传**：
> `PromptLoader.render` 对没传的占位符是**原样留着**的（`PromptLoader.java:36` 的注释写着
> 「方便发现漏传」），漏了不会报错——只是模型收到一个字面量 `{resume}`，输出变差而页面看不出来。
> 所以 Step 4 有一个程序专门钉这件事。

- [ ] **Step 2: `common/dto/ResumeReviewTarget.java`**

```java
package com.ke.nhservice.aimianshi.common.dto;

/**
 * 一个目标岗位：岗位名 + 可空的 JD。
 * 既是请求体里的一项，也是 targets_json 里的一项——形状一样就不定义两份。
 */
public record ResumeReviewTarget(String title, String jd) {
}
```

- [ ] **Step 3: `biz/resume/ResumeReviewParser.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物的解析：一份 Markdown → 建议列表 / 干净正文 / 建议条数。
 *
 * ★ 这是「怎么从产物里抽建议」的**唯一实现处**。页面上那两个视图（建议面板、参考稿）
 *   都由它派生，Java 和 JS 不各写一份——同理 InterviewStats 一个人管着「null 维度怎么算」。
 *
 * 纯函数：不碰库、不碰 LLM，所以分行、空节、没有标题这些边界离线就能验。
 */
public final class ResumeReviewParser {

    /** 批注行的标记。提示词里就是这么约定的 */
    private static final String QUOTE = ">";

    /** 批注正文的前缀，只是给机器认的标记，展示时要摘掉 */
    private static final String PREFIX = "建议：";

    /** 第一个 ## 之前的批注归到这一组 */
    public static final String SECTION_HEAD = "简历开头";

    private ResumeReviewParser() {
    }

    /** 一条建议：它属于哪一节 + 正文 */
    public record Suggestion(String section, String text) {
    }

    /**
     * targets_json 的载荷。包一层是为了复用 JsonUtil.fromJson(String, Class)——
     * 直接反序列化 List&lt;ResumeReviewTarget&gt; 要 TypeReference，不值得为这一个字段加一套 API。
     * （InterviewDao 里的 EvalJson 是同一个写法。）
     */
    record TargetList(List<ResumeReviewTarget> items) {
    }

    /** 所有以 > 开头的行。section 是它上面最近的那个 ## 标题 */
    public static List<Suggestion> suggestions(String markdown) {
        return scan(markdown).suggestions();
    }

    /** 建议条数。写入时算一次存进 suggestion_count，列表页就不用拖全文了 */
    public static int count(String markdown) {
        return suggestions(markdown).size();
    }

    /**
     * 去掉批注行之后的干净正文，给页面渲染参考稿用。
     *
     * 两步不只是「少几行」：
     *  1. 岗位节和「最该先改的三件事」这些节的批注全被抽走了，标题会剩下一个空壳
     *  2. 批注被抽走之后会留下成片的空行
     */
    public static String document(String markdown) {
        List<Line> body = scan(markdown).body();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            Line line = body.get(i);
            // ★ 空掉的小节标题也去掉。不去的话参考稿里会出现
            //   「## 投「Go 后端」要额外改什么」下面空无一物——按提示词约定，
            //   岗位节里只该有 > 建议： 行，抽掉之后就什么都不剩了
            if (line.sectionHead() && emptyUntilNextHead(body, i + 1)) {
                continue;
            }
            kept.add(line.text());
        }
        return squeeze(kept);
    }

    // ────────────────────────── targets_json ──────────────────────────

    public static String writeTargets(List<ResumeReviewTarget> targets) {
        return JsonUtil.toJson(new TargetList(targets == null ? List.of() : targets));
    }

    public static List<ResumeReviewTarget> parseTargets(String json) {
        if (json == null || json.isBlank()) {
            // 老数据或没写成功时为 null，不能让它把整个列表页带崩
            return List.of();
        }
        List<ResumeReviewTarget> items = JsonUtil.fromJson(json, TargetList.class).items();
        return items == null ? List.of() : items;
    }

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 走一遍拿到两样东西：建议列表 + 去掉批注行之后的正文行。
     * 走一遍而不是两遍，是因为「这一行属于哪一节」两边都要知道。
     */
    private static Scan scan(String markdown) {
        List<Suggestion> suggestions = new ArrayList<>();
        List<Line> body = new ArrayList<>();
        String section = SECTION_HEAD;

        for (String raw : lines(markdown)) {
            String line = raw.strip();
            if (isHeading(line)) {
                // 只把 ## 当分节边界。### 之类照收进正文，但不改 section——
                // 简历里 ### 是项目名，拿它当分组名会把「项目经历那几条」拆成一堆单条
                boolean sectionHead = line.startsWith("##") && !line.startsWith("###");
                if (sectionHead) {
                    section = line.replaceFirst("^#+\\s*", "").strip();
                }
                body.add(new Line(line, sectionHead));
            } else if (line.startsWith(QUOTE)) {
                String text = line.substring(QUOTE.length()).strip();
                if (text.startsWith(PREFIX)) {
                    text = text.substring(PREFIX.length()).strip();
                }
                if (!text.isEmpty()) {
                    suggestions.add(new Suggestion(section, text));
                }
            } else {
                // 正文保留行首缩进（列表项的缩进有用），只清掉行尾空白
                body.add(new Line(raw.stripTrailing(), false));
            }
        }
        return new Scan(suggestions, body);
    }

    /** 从 from 往后直到下一个分节标题，中间是不是全是空行（是 → 这一节是空的） */
    private static boolean emptyUntilNextHead(List<Line> body, int from) {
        for (int i = from; i < body.size(); i++) {
            if (body.get(i).sectionHead()) {
                return true;
            }
            if (!body.get(i).text().isBlank()) {
                return false;
            }
        }
        // 到结尾都没撞上下一个标题：只看最后一个标题之后的内容
        return true;
    }

    /** 连续空行压成一个、去掉首尾空行。批注抽走之后会留下成片的空行 */
    private static String squeeze(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                if (out.isEmpty() || out.get(out.size() - 1).isEmpty()) {
                    continue;
                }
                out.add("");
            } else {
                out.add(line);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return String.join("\n", out);
    }

    /**
     * 行首有几个 # 就算标题。
     *
     * 故意不按 CommonMark 要求「# 后面必须有空格」：这里要认的是**模型的意图**，
     * 它少写一个空格不该让整节的建议掉进「简历开头」那一组。
     */
    private static boolean isHeading(String line) {
        return line.startsWith("#");
    }

    private static List<String> lines(String markdown) {
        return markdown == null ? List.of() : List.of(markdown.split("\r?\n", -1));
    }

    private record Line(String text, boolean sectionHead) {
    }

    private record Scan(List<Suggestion> suggestions, List<Line> body) {
    }
}
```

- [ ] **Step 4: `ResumeReviewCheck.java`**

```java
package com.ke.nhservice.aimianshi.checks;

import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewParser;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历改稿那条链上「写错了不报错、只是行为不对」的两件事（不是 JUnit，是能直接 main 跑的）。
 *
 * 1. **提示词占位符有没有漏传。** PromptLoader 对没传的 {key} 是原样留着的，漏了不报错——
 *    只是模型收到的 prompt 里带着一个字面量 "{resume}"，输出变差，
 *    而页面上一点异常都看不出来。这是这个程序存在的唯一理由。
 * 2. **产物解析。** 抽建议 / 去批注 / 分节只有一处实现，边界（没有 ## 标题、
 *    岗位节只剩批注行、批注在最前面）离线几条断言就能覆盖。
 *
 * 不起 Spring 容器、不调 LLM、不碰库。
 *
 * 跑法（在仓库根目录）：
 *   export JAVA_HOME="/c/Program Files/Java/jdk-21"
 *   ./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q test-compile
 *   CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
 *   "$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
 *     com.ke.nhservice.aimianshi.checks.ResumeReviewCheck
 *
 * 要 cp.txt（PromptLoader 用 spring-core 的 ClassPathResource，JsonUtil 用 Jackson 3），
 * 但**不起 Spring 容器**。
 */
public final class ResumeReviewCheck {

    private static int failed = 0;

    public static void main(String[] args) {
        checkPrompt();
        checkParser();
        checkTargetsJson();

        System.out.println();
        if (failed > 0) {
            System.out.println("FAIL —— " + failed + " 条没过");
            System.exit(1);
        }
        System.out.println("PASS —— 全部通过");
    }

    // ────────────────────────── 提示词 ──────────────────────────

    private static void checkPrompt() {
        System.out.println("【提示词占位符】");
        PromptLoader loader = new PromptLoader();
        String template = loader.load("resume_review");

        // 先钉模板本身：三个占位符一个都不能少（少一个就等于模型看不到那部分素材）
        for (String key : List.of("{targets}", "{interviewMaterial}", "{resume}")) {
            check("模板里有 " + key, template.contains(key));
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("targets", "① Java 后端\n   JD：（未提供，按该岗位通用标准）");
        vars.put("interviewMaterial", "（未提供）");
        vars.put("resume", "张三 · 3 年 Java 后端");
        String rendered = loader.render("resume_review", vars);

        // ★ 这条错了就是真出事了：带着 {resume} 这种字面量发给模型，页面上完全看不出来
        check("★ 三个占位符全被替换掉（渲染结果里不含任何 {）", !rendered.contains("{"));
        check("素材真的进去了", rendered.contains("张三 · 3 年 Java 后端"));
        check("没选面试记录时那句话也在", rendered.contains("（未提供）"));

        // 反证：漏传时占位符是会留着的。不做这一条，上面那条可能是恒真的
        Map<String, String> missing = new HashMap<>(vars);
        missing.remove("resume");
        check("反证：漏传 resume 时 {resume} 原样留着",
                loader.render("resume_review", missing).contains("{resume}"));
    }

    // ────────────────────────── 产物解析 ──────────────────────────

    private static final String SAMPLE = """
            > 建议：整份简历的动词太弱，把「参与」都换成做了什么。
            ## 个人信息
            > 建议：开头缺一句「几年经验 + 什么方向」，HR 三秒内看不到重点。
            张三 · 3 年 Java 后端
            ## 项目经历
            > 建议：订单中台那段写清楚你具体做了什么。
            ### 订单中台
            将下单链路的 3 次 RPC 合并为批量查询，P99 从【380ms】降到【120ms】。
            ## 这份简历最该先改的三件事
            补指标、项目往前放、删掉与岗位无关的技能。
            ## 投「Java 后端」要额外改什么
            > 建议：这个岗位强调交易链路，把订单中台那段往前放。
            ## 投「Go 后端」要额外改什么
            > 建议：Go 岗位看 channel 和调度，把并发调优那段提到显眼位置。
            """;

    private static void checkParser() {
        System.out.println();
        System.out.println("【产物解析】");
        List<ResumeReviewParser.Suggestion> s = ResumeReviewParser.suggestions(SAMPLE);

        check("★ 抽出 5 条建议（4 条在各节里 + 1 条在第一个标题之前）", s.size() == 5);
        check("第一个标题之前的批注归到「简历开头」",
                ResumeReviewParser.SECTION_HEAD.equals(s.get(0).section()));
        check("正文节的批注归到自己的章节", "个人信息".equals(s.get(1).section()));
        check("子标题（###）不改分组", "项目经历".equals(s.get(2).section()));
        check("岗位节的批注归到岗位节", "投「Go 后端」要额外改什么".equals(s.get(4).section()));
        check("★ 摘掉了「> 建议：」标记，只留正文",
                s.get(0).text().startsWith("整份简历的动词太弱"));
        check("反证：所有建议里都不含 > 或「建议：」",
                s.stream().noneMatch(x -> x.text().contains("建议：") || x.text().startsWith(">")));

        String doc = ResumeReviewParser.document(SAMPLE);
        check("★ 干净正文里一行批注都没有",
                doc.lines().noneMatch(l -> l.strip().startsWith(">")));
        check("★ 空掉的岗位节标题被去掉（不去的话参考稿里是一串空标题）",
                !doc.contains("投「Java 后端」要额外改什么")
                        && !doc.contains("投「Go 后端」要额外改什么"));
        check("有内容的小节标题都留着",
                doc.contains("## 个人信息") && doc.contains("## 项目经历")
                        && doc.contains("## 这份简历最该先改的三件事"));
        check("正文和子标题都在",
                doc.contains("张三 · 3 年 Java 后端") && doc.contains("### 订单中台")
                        && doc.contains("P99 从【380ms】降到【120ms】。"));
        check("没有连续空行", !doc.contains("\n\n\n"));
        check("首尾没有空行", doc.equals(doc.strip()));

        check("★ 空产物不炸", ResumeReviewParser.suggestions(null).isEmpty()
                && ResumeReviewParser.document(null).isEmpty()
                && ResumeReviewParser.count(null) == 0);

        String noHeading = "> 建议：A\n看看这里\n> 建议：B";
        List<ResumeReviewParser.Suggestion> flat = ResumeReviewParser.suggestions(noHeading);
        check("模型没写 ## 标题时全归「简历开头」（不报错，也不丢建议）",
                flat.size() == 2
                        && flat.stream().allMatch(x -> ResumeReviewParser.SECTION_HEAD.equals(x.section())));
        check("没标题时正文不被吃掉", ResumeReviewParser.document(noHeading).contains("看看这里"));
    }

    // ────────────────────────── targets_json ──────────────────────────

    private static void checkTargetsJson() {
        System.out.println();
        System.out.println("【targets_json 往返】");
        List<ResumeReviewTarget> targets = List.of(
                new ResumeReviewTarget("Java 后端", "负责交易链路高并发"),
                new ResumeReviewTarget("Go 后端", null));
        List<ResumeReviewTarget> back =
                ResumeReviewParser.parseTargets(ResumeReviewParser.writeTargets(targets));
        check("★ 写进去再读出来是同一份（JD 为 null 的那个也在）", targets.equals(back));
        check("空 json 读出来是空列表，不抛异常", ResumeReviewParser.parseTargets(null).isEmpty());
    }

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label);
        if (!ok) {
            failed++;
        }
    }
}
```

- [ ] **Step 5: `src/test/README.md` 登记**

这个程序要依赖清单（`PromptLoader` 用 spring-core 的 `ClassPathResource`、`JsonUtil` 用 Jackson 3），
但**不起 Spring 容器**，所以它既不属于「一、不依赖 Spring 容器的」也不属于
「二、要起 Spring 容器的」。在 `## Java 验证程序` 下新增一节，
放在 `### 一、不依赖 Spring 容器的（直接 java -cp）` 那节之后、`### 二、要起 Spring 容器的` 之前，
**并把原来的「二、」改成「三、」**（不改的话两个「二、」）：

```markdown
### 要依赖清单、但不起 Spring 容器的

```bash
CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.ResumeReviewCheck
```

| 程序 | 验什么 | 需要什么 |
|---|---|---|
| `ResumeReviewCheck` | 两件「写错了不报错」的事：① 改简历提示词的三个占位符有没有漏传（`PromptLoader` 对漏传的占位符是**原样留着**的，错的表现是模型收到一个字面量 `{resume}`，输出变差而页面看不出来）② 产物的抽建议 / 去批注 / 分节 | `$TEMP/cp.txt`（`PromptLoader` 要 spring-core、`JsonUtil` 要 Jackson 3） |

`cp.txt` 已经生成过就不用再来一次；没有的话：

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q \
  dependency:build-classpath -Dmdep.outputFile=$TEMP/cp.txt
```
```

- [ ] **Step 6: 编译 + 跑检查**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q test-compile
CP="target/classes;target/test-classes;$(cat $TEMP/cp.txt)"
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "$CP" \
  com.ke.nhservice.aimianshi.checks.ResumeReviewCheck
```

预期：每一行都是 `PASS`，最后一行 `PASS —— 全部通过`，退出码 0。
有一条 `FAIL` 就说明提示词缺了占位符、或者解析的边界没走对——**照输出改，别绕过去**。

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/prompts/resume_review.md \
        src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewTarget.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewParser.java \
        src/test/java/com/ke/nhservice/aimianshi/checks/ResumeReviewCheck.java \
        src/test/README.md
git commit -m "feat(resume): 改稿提示词契约与产物解析（含占位符/解析检查程序）"
```

---

## Task 4: 拼提示词素材（纯函数）

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewMaterial.java`

> 这个 Task **不动 `InterviewEngine`**（和设计文档不同，理由见本计划 Context 那节）：
> `requireRecord(userId, id)` 和 `dialogues(recordId)` 都已经有了，而且自带归属校验。
> 素材怎么摆进 prompt 是 resume 侧的事，interview 不认识「简历改稿」这回事。

- [ ] **Step 1: `ResumeReviewMaterial.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.EvalResult;
import com.ke.nhservice.aimianshi.biz.interview.InterviewStats;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把「目标岗位」和「挑中的几场面试」拼成提示词里的两段文字。
 *
 * 单独一个纯函数类：interview 那边只提供数据（RecordRow + Dialogue），不认识「简历改稿」这回事。
 * 无状态、不碰库、不碰 LLM，所以边界（一场没选、选了但一题没答、practice 是 null）离线就能想清楚。
 */
public final class ResumeReviewMaterial {

    /** 序号用 ①②③… 好看；超过十个退回 "11)" 这种写法，不为边角情况堆一整个数组 */
    private static final String CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩";

    /** 一场面试记录都没选 */
    static final String NO_INTERVIEW = "（未提供）";

    /** 选了，但一场都没答过题（图跑挂过的那种场次） */
    static final String NO_ANSWERED = "（还没有已完成的面试）";

    private ResumeReviewMaterial() {
    }

    /** 目标岗位。岗位名必填（service 已经校验过），JD 可空 */
    public static String targets(List<ResumeReviewTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return NO_INTERVIEW;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            ResumeReviewTarget t = targets.get(i);
            String jd = t.jd() == null ? "" : t.jd().strip();
            sb.append(index(i + 1)).append(' ').append(t.title()).append('\n');
            sb.append("   JD：")
              .append(jd.isEmpty() ? "（未提供，按该岗位通用标准）" : jd)
              .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 面试表现素材。
     *
     * ★ 一题没答过的场次要**跳过**：图跑挂过的场次也会是 finished 但没有任何对话，
     *   摆进来对模型没信息量，「这题 0 分」还会误导它（那是引擎挂了，不是候选人答砸了）。
     *   全部场次都跳过了才说一句「还没有已完成的面试」。
     */
    public static String interviews(List<RecordRow> records,
                                    Map<Long, List<Dialogue>> dialoguesByRecord) {
        if (records == null || records.isEmpty()) {
            return NO_INTERVIEW;
        }
        Map<Long, List<Dialogue>> byRecord =
                dialoguesByRecord == null ? Map.of() : dialoguesByRecord;

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RecordRow row : records) {
            List<Dialogue> dialogues = byRecord.getOrDefault(row.id(), List.of());
            if (dialogues.isEmpty()) {
                continue;
            }
            shown++;
            sb.append("第 ").append(shown).append(" 场 · ")
              .append(text(row.position(), "未填岗位")).append(" / ")
              .append(text(row.domain(), "未填方向")).append(" · ")
              .append(text(row.difficulty(), "未填难度")).append(" · 总分 ")
              .append(score(row.totalScore())).append(" · 已答 ")
              .append(dialogues.size()).append(" 题\n");
            for (Dialogue d : dialogues) {
                sb.append("  第").append(d.getSeq()).append("题 ")
                  .append(text(d.getTopic(), "综合")).append(' ')
                  .append(score(d.getScore())).append('\n');
            }
            sb.append("  ").append(dimensions(dialogues)).append('\n');
        }
        return shown == 0 ? NO_ANSWERED : sb.toString().stripTrailing();
    }

    /**
     * 这一场的五维均分。
     * ★ 算法**不在这里重写一遍**：InterviewStats 一个人管着「null 维度既不进分子也不进分母」，
     *   这里只是把它算出来的结果摆成一行字。
     *   finishedCount 传 1 是占位（那个字段是给「历史几场」用的），
     *   和 InterviewController.detail 里的用法一致。
     */
    private static String dimensions(List<Dialogue> dialogues) {
        Map<String, Double> averages = InterviewStats.of(1, dialogues).dimensions();
        if (averages.isEmpty()) {
            return "五维均分：（本场没有可统计的维度）";
        }
        StringBuilder sb = new StringBuilder("五维均分：");
        boolean first = true;
        for (Map.Entry<String, Double> entry : averages.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            first = false;
            sb.append(EvalResult.DIMENSION_LABELS.getOrDefault(entry.getKey(), entry.getKey()))
              .append(' ').append(score(entry.getValue()));
        }
        return sb.toString();
    }

    private static String index(int n) {
        return n <= CIRCLED.length() ? String.valueOf(CIRCLED.charAt(n - 1)) : n + ")";
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 分数统一一位小数；null（没打分）显示成 -，**不能显示成 0**（那是「打了 0 分」） */
    private static String score(Double value) {
        // 用 Locale.ROOT 而不是默认 locale：这是 prompt 里的数字，
        // 落到某些 locale 会变成 "7,2" 这种小数点
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f", value);
    }
}
```

- [ ] **Step 2: 编译**

同 Task 1 Step 6。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewMaterial.java
git commit -m "feat(resume): 改稿的提示词素材拼装（目标岗位 + 挑中的几场面试）"
```

---

## Task 5: Service + Controller + DTO + 删简历连带删

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewRequest.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewItemVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewVO.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewService.java`
- Create: `src/main/java/com/ke/nhservice/aimianshi/controller/ResumeReviewController.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeService.java`

- [ ] **Step 1: 三个 DTO**

`ResumeReviewRequest.java`：

```java
package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;

/** 生成一次改稿的请求体。interviewIds 可以空（不看面试记录也能改） */
public record ResumeReviewRequest(Long resumeId,
                                  List<ResumeReviewTarget> targets,
                                  List<Long> interviewIds) {
}
```

`ResumeReviewItemVO.java`：

```java
package com.ke.nhservice.aimianshi.common.dto;

import java.util.List;

/** 历史列表一行。★ 不含 markdown：一行的全文可能上万字，列表用不上 */
public record ResumeReviewItemVO(Long id,
                                 long createdAt,
                                 int suggestionCount,
                                 List<ResumeReviewTarget> targets,
                                 int interviewCount,
                                 String model,
                                 boolean truncated) {
}
```

`ResumeReviewVO.java`：

```java
package com.ke.nhservice.aimianshi.common.dto;

import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewParser;

import java.util.List;

/**
 * 一次改稿的详情。
 *
 * markdown / document / suggestions 是**同一份产物的三个视图**，都不落库、每次现派生：
 *   markdown    原始全文，批注在原位 —— **导出用这个**
 *   document    去掉批注的干净正文 —— 页面渲染参考稿用这个
 *   suggestions 抽出来的建议列表（带分组）
 */
public record ResumeReviewVO(Long id,
                             Long resumeId,
                             String filename,
                             long createdAt,
                             String model,
                             boolean truncated,
                             List<ResumeReviewTarget> targets,
                             List<Long> interviewIds,
                             String markdown,
                             String document,
                             List<ResumeReviewParser.Suggestion> suggestions) {
}
```

- [ ] **Step 2: `ResumeReviewService.java`**

```java
package com.ke.nhservice.aimianshi.biz.resume;

import com.ke.nhservice.aimianshi.biz.interview.Dialogue;
import com.ke.nhservice.aimianshi.biz.interview.InterviewEngine;
import com.ke.nhservice.aimianshi.biz.interview.RecordRow;
import com.ke.nhservice.aimianshi.biz.interview.prompt.PromptLoader;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewItemVO;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewRequest;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewTarget;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewVO;
import com.ke.nhservice.aimianshi.common.exception.BizException;
import com.ke.nhservice.aimianshi.wrapper.llm.ChatMessage;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmClient;
import com.ke.nhservice.aimianshi.wrapper.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ResumeReviewService {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewService.class);

    /** 提示词文件名，对应 resources/prompts/resume_review.md */
    private static final String PROMPT = "resume_review";

    private final ResumeReviewDao dao;
    private final ResumeService resumeService;
    private final InterviewEngine engine;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final LlmProperties llmProps;

    public ResumeReviewService(ResumeReviewDao dao,
                               ResumeService resumeService,
                               InterviewEngine engine,
                               LlmClient llm,
                               PromptLoader prompts,
                               LlmProperties llmProps) {
        this.dao = dao;
        this.resumeService = resumeService;
        this.engine = engine;
        this.llm = llm;
        this.prompts = prompts;
        this.llmProps = llmProps;
    }

    /**
     * 生成一次改稿。一次 LLM 调用，产物是纯 Markdown（见 prompts/resume_review.md）。
     *
     * 失败不落库：LLM 挂了就抛出去让页面 toast，历史里不留半成品。
     * （和面试那条链一致——评分挂了也不会写一条空对话。）
     */
    public ResumeReviewVO generate(Long userId, ResumeReviewRequest request) {
        if (request == null || request.resumeId() == null) {
            throw new BizException("请先选一份简历");
        }
        List<ResumeReviewTarget> targets = cleanTargets(request.targets());
        Resume resume = resumeService.requireOwned(userId, request.resumeId());
        List<Long> interviewIds = request.interviewIds() == null
                ? List.of() : request.interviewIds();

        // 归属校验走 engine.requireRecord（内部是 loadOwned）：别人的 id 在这里就 404 了，
        // 不会有机会被喂进 prompt —— 把别人的面试记录拼进 prompt 是最不该发生的事
        List<RecordRow> records = new ArrayList<>();
        Map<Long, List<Dialogue>> byRecord = new LinkedHashMap<>();
        for (Long id : interviewIds) {
            records.add(engine.requireRecord(userId, id));
            // 逐场一条查询。选中的场次是个位数，且 listDialogues 走 record_id 索引，
            // 不值得为它引入一个批量方法（listDialoguesByRecords 的 SELECT 里没有 record_id 列，
            // 拿它拼不出「哪条属于哪场」）
            byRecord.put(id, engine.dialogues(id));
        }

        String prompt = prompts.render(PROMPT, Map.of(
                "targets", ResumeReviewMaterial.targets(targets),
                "interviewMaterial", ResumeReviewMaterial.interviews(records, byRecord),
                "resume", resume.content()));

        // 简历正文和 prompt 都不打：简历是候选人的隐私内容，和 RequestLogFilter 的口径一致
        log.info("开始生成简历改稿 | 简历={} 用户={} | 目标岗位={} 参考面试={} 场 | 简历 {} 字符",
                resume.id(), userId, targets.size(), interviewIds.size(),
                resume.content().length());

        long startedAt = System.currentTimeMillis();
        LlmClient.Reply reply = llm.chatDetailed(List.of(ChatMessage.user(prompt)));
        long cost = System.currentTimeMillis() - startedAt;

        String markdown = reply.content().strip();
        int suggestionCount = ResumeReviewParser.count(markdown);
        long reviewId = dao.insert(userId, resume.id(), markdown, suggestionCount,
                ResumeReviewParser.writeTargets(targets),
                interviewIds.isEmpty() ? null : joinIds(interviewIds),
                llmProps.getModel(),
                reply.truncated());

        log.info("简历改稿完成 | 改稿={} 简历={} 用户={} | {} ms | 建议 {} 条 | {} 字符 | 模型={}{}",
                reviewId, resume.id(), userId, cost, suggestionCount, markdown.length(),
                llmProps.getModel(),
                reply.truncated() ? " | ★ 输出被长度上限截断（app.llm.max-tokens 可调大）" : "");

        // 返回库里那份，不是内存里那份：页面上看到的和存下来的必然一致
        return detail(userId, reviewId);
    }

    /** 某份简历的历史。先校验简历归属——别人的 resumeId 应该 404，而不是返回一个空数组 */
    public List<ResumeReviewItemVO> list(Long userId, Long resumeId) {
        if (resumeId == null) {
            throw new BizException("请先选一份简历");
        }
        resumeService.requireOwned(userId, resumeId);
        return dao.listByResume(resumeId).stream()
                .map(r -> new ResumeReviewItemVO(
                        r.id(), r.createdAt(), r.suggestionCount(),
                        ResumeReviewParser.parseTargets(r.targetsJson()),
                        parseIds(r.interviewIds()).size(), r.model(), r.truncated()))
                .toList();
    }

    public ResumeReviewVO detail(Long userId, Long reviewId) {
        ResumeReview r = requireOwned(userId, reviewId);
        Resume resume = resumeService.requireOwned(userId, r.resumeId());
        return new ResumeReviewVO(
                r.id(), r.resumeId(), resume.filename(), r.createdAt(), r.model(), r.truncated(),
                ResumeReviewParser.parseTargets(r.targetsJson()),
                parseIds(r.interviewIds()),
                r.markdown(),
                ResumeReviewParser.document(r.markdown()),
                ResumeReviewParser.suggestions(r.markdown()));
    }

    public void delete(Long userId, Long reviewId) {
        requireOwned(userId, reviewId);
        dao.delete(reviewId);
        log.info("删除简历改稿 | 改稿={} 用户={}", reviewId, userId);
    }

    // ────────────────────────── 内部 ──────────────────────────

    /**
     * 归属校验。不存在和不是你的都统一 notFound，和 ResumeService.requireOwned 一个口径
     * （不区分「没有」和「不是你的」，避免探测别人的 id）。
     */
    private ResumeReview requireOwned(Long userId, Long reviewId) {
        ResumeReview review = dao.findById(reviewId)
                .orElseThrow(() -> BizException.notFound("这份改稿不存在"));
        if (!review.userId().equals(userId)) {
            throw BizException.notFound("这份改稿不存在");
        }
        return review;
    }

    /**
     * 岗位名必填、两头去空白、JD 空白算没填。
     * 前端也拦一道，但后端不能指望前端——一个空岗位名会让整次生成白跑 15-40 秒。
     */
    private static List<ResumeReviewTarget> cleanTargets(List<ResumeReviewTarget> raw) {
        List<ResumeReviewTarget> out = new ArrayList<>();
        for (ResumeReviewTarget t : raw == null ? List.<ResumeReviewTarget>of() : raw) {
            String title = t == null || t.title() == null ? "" : t.title().strip();
            if (title.isEmpty()) {
                continue;
            }
            String jd = t.jd() == null || t.jd().isBlank() ? null : t.jd().strip();
            out.add(new ResumeReviewTarget(title, jd));
        }
        if (out.isEmpty()) {
            throw new BizException("至少填一个目标岗位");
        }
        return out;
    }

    /** interview_ids 存成逗号分隔串（和 t_resume 那些列一样，不值得为它开一张关联表） */
    private static String joinIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static List<Long> parseIds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}
```

- [ ] **Step 3: `ResumeReviewController.java`**

```java
package com.ke.nhservice.aimianshi.controller;

import com.ke.nhservice.aimianshi.biz.resume.ResumeReviewService;
import com.ke.nhservice.aimianshi.common.auth.UserContext;
import com.ke.nhservice.aimianshi.common.dto.ApiResponse;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewItemVO;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewRequest;
import com.ke.nhservice.aimianshi.common.dto.ResumeReviewVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 简历改稿。
 *
 * ★ 用独立前缀 /api/resume-review，不挂在 /api/resume/{id} 下面：
 *   这两个前缀下都有一条 DELETE /{id}，删的却是完全不同的东西（删改稿 vs 删简历），
 *   放一起早晚看错。getter 是 /{id}、list 是 ?resumeId= 也是这个缘故——
 *   路径段留给「改稿的 id」，简历的 id 走查询参数。
 */
@RestController
@RequestMapping("/api/resume-review")
public class ResumeReviewController {

    private final ResumeReviewService service;

    public ResumeReviewController(ResumeReviewService service) {
        this.service = service;
    }

    /** 生成。失败不落库，历史里不留半成品 */
    @PostMapping
    public ApiResponse<ResumeReviewVO> generate(@RequestBody ResumeReviewRequest request) {
        return ApiResponse.ok(service.generate(UserContext.get(), request));
    }

    /** 某份简历的历史改稿 */
    @GetMapping
    public ApiResponse<List<ResumeReviewItemVO>> list(@RequestParam Long resumeId) {
        return ApiResponse.ok(service.list(UserContext.get(), resumeId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeReviewVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.detail(UserContext.get(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 4: `ResumeService` 删简历时连带删改稿**

`ResumeService.java` 的改动：加 import、加字段、加构造器参数、改 `delete`：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
```

```java
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final ResumeDao resumeDao;
    private final ResumeReviewDao reviewDao;

    public ResumeService(ResumeDao resumeDao, ResumeReviewDao reviewDao) {
        this.resumeDao = resumeDao;
        this.reviewDao = reviewDao;
    }
```

```java
    /**
     * 删一份简历，连带删它的全部改稿。
     *
     * ★ 事务必须在 service 这一层：t_resume 是物理删、**没有外键级联**，
     *   两张表的删除要一起成功（否则简历没了、改稿还在）。
     *   ResumeDao.setDefault 那个 @Transactional 是「一张表的两条语句」，这里是两张表。
     */
    @Transactional
    public void delete(Long userId, Long resumeId) {
        requireOwned(userId, resumeId);
        int removed = reviewDao.deleteByResume(resumeId);
        resumeDao.delete(resumeId);
        log.info("删除简历 | 简历={} 用户={} | 连带删掉 {} 条改稿", resumeId, userId, removed);
    }
```

- [ ] **Step 5: 编译**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q clean compile
```

预期：无输出。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewRequest.java \
        src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewItemVO.java \
        src/main/java/com/ke/nhservice/aimianshi/common/dto/ResumeReviewVO.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeReviewService.java \
        src/main/java/com/ke/nhservice/aimianshi/controller/ResumeReviewController.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/resume/ResumeService.java
git commit -m "feat(resume): 改稿的服务与接口，删简历连带删改稿"
```

---

## Task 6: 前端（入口按钮 + 新页面）

**Files:**
- Modify: `src/main/resources/static/resume.html`
- Create: `src/main/resources/static/resume-review.html`
- Modify: `src/main/resources/static/css/app.css`

- [ ] **Step 1: `resume.html` 加「改简历」按钮**

那一行的操作格（`resume.html:48-51`）改成：

```html
            <td class="right">
              <button class="small ghost" @click="goReview(r.id)">改简历</button>
              <button v-if="!r.isDefault" class="small ghost" @click="setDefault(r.id)">设为默认</button>
              <button class="small danger" @click="remove(r.id)">删除</button>
            </td>
```

`methods` 里加一个（挨着 `setDefault`）：

```js
      goReview(id) { location.href = 'resume-review.html?resumeId=' + id; },
```

**顶部导航那三项不动**：这页需要 `resumeId`，塞不进 `topbarHtml` 那个写死的数组（`app.js:156-160`）。

- [ ] **Step 2: `css/app.css` 加目标岗位那一行的样式**

追加到「面试对话」那一段之后：

```css
/* ── 改简历：目标岗位那一行（岗位名 + ✕ 在第一行，JD 独占第二行） ── */

.target-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  margin-bottom: 10px;
}

.target-row textarea { grid-column: 1 / -1; min-height: 64px; }
```

- [ ] **Step 3: `static/resume-review.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>改简历 · AI 模拟面试</title>
  <link rel="stylesheet" href="css/app.css">
</head>
<body>

<div id="app">
  <div v-html="topbar"></div>

  <div class="container-wide">
    <!-- 生成表单 -->
    <div class="card">
      <h2>改简历</h2>
      <p class="sub">
        对着目标岗位，给出逐条修改建议和一份改写的参考稿。
        模型一次要写出整份稿子，一般 15-40 秒，期间别关页面。
      </p>

      <div class="field">
        <label>目标岗位（至少一条，岗位名必填）</label>
        <div class="target-row" v-for="(t, i) in form.targets" :key="i">
          <input v-model.trim="t.title" placeholder="岗位名，例如：Java 后端开发">
          <button class="small danger" :disabled="form.targets.length <= 1"
                  @click="removeTarget(i)">✕</button>
          <textarea v-model="t.jd"
                    placeholder="JD 原文，可留空（留空就按这个岗位的通用标准来改）"></textarea>
        </div>
        <button class="small ghost" @click="addTarget">+ 再加一条</button>
      </div>

      <div class="field">
        <label>参考哪些面试记录（可不选）</label>
        <p class="sub">
          选中的场次会作为「你实际答成什么样」的证据喂给模型，
          它给出的建议就会点到你的弱项话题上。
        </p>
        <div v-if="interviews.length === 0" class="empty">还没有面试记录</div>
        <table v-else>
          <thead>
            <tr>
              <th style="width:36px"></th><th>岗位</th><th>方向</th><th>已答</th>
              <th>均分</th><th>状态</th><th>开始时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in interviews" :key="r.id">
              <td><input type="checkbox" :value="r.id" v-model="form.interviewIds"></td>
              <td>{{ r.position || '-' }}</td>
              <td>{{ r.domain || '-' }}</td>
              <td>{{ r.dialogueCount }} 题</td>
              <td>
                <span class="score" :class="scoreClass(r.totalScore)">
                  {{ fmtScore(r.totalScore) }}
                </span>
              </td>
              <td>
                <span v-if="r.status === 'finished'" class="tag success">已结束</span>
                <span v-else class="tag warning">进行中</span>
              </td>
              <td class="muted">{{ fmtTime(r.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <button :disabled="generating" @click="generate">
        <span v-if="generating" class="spinner"></span>
        {{ generating ? '生成中…一般 15-40 秒' : '生成修改建议' }}
      </button>
    </div>

    <!-- 历史 -->
    <div class="card">
      <h2>历史改稿</h2>
      <p class="sub">按简历各留一份历史。批注和参考稿都存着，随时回看、可导出。</p>

      <div v-if="history.length === 0" class="empty">这份简历还没生成过</div>
      <table v-else>
        <thead>
          <tr><th>时间</th><th>建议</th><th>目标岗位</th><th>参考面试</th><th>模型</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="h in history" :key="h.id">
            <td class="muted">{{ fmtTime(h.createdAt) }}</td>
            <td>
              {{ h.suggestionCount }} 条
              <span v-if="h.truncated" class="tag warning">被截断</span>
            </td>
            <td>{{ targetText(h.targets) }}</td>
            <td>{{ h.interviewCount ? h.interviewCount + ' 场' : '-' }}</td>
            <td class="muted">{{ h.model || '-' }}</td>
            <td class="right">
              <button class="small" @click="view(h.id)">查看</button>
              <button class="small danger" @click="remove(h.id)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <template v-if="detail">
      <!-- 截断提示。没有这一条的话，现象是「参考稿戛然而止」，会以为是模型笨 -->
      <div v-if="detail.truncated" class="card"
           style="background:#fdf3e3;border-color:#f6e0bd">
        <strong>这次输出被长度上限截断了。</strong>
        下面的建议和参考稿只到断掉的地方为止（前面的建议都存下来了）。
        想拿到完整的，在 <code>application.yml</code> 里加一行
        <code>app.llm.max-tokens: 8192</code>（或更大）再生成一次。
      </div>

      <!-- 修改建议 -->
      <div class="card">
        <div class="row" style="align-items:baseline">
          <h2 style="margin:0">
            修改建议 <span class="muted">{{ detail.suggestions.length }} 条</span>
          </h2>
          <span style="flex:1"></span>
          <span>
            <button class="ghost small" @click="exportMarkdown">导出 Markdown（批注在原位）</button>
          </span>
        </div>
        <p class="sub">
          {{ detail.filename }} · {{ fmtTime(detail.createdAt) }} ·
          {{ targetText(detail.targets) }}
        </p>

        <div v-if="detail.suggestions.length === 0" class="empty">
          这次一行批注都没抽到（生成失败，或者模型没按约定格式输出）。参考稿还能看。
        </div>
        <template v-else v-for="(group, section) in groupedSuggestions" :key="section">
          <h3>{{ section }}（{{ group.length }} 条）</h3>
          <ol>
            <li v-for="(s, i) in group" :key="i">{{ s.text }}</li>
          </ol>
        </template>
      </div>

      <!-- 参考稿 -->
      <div class="card">
        <h2>参考稿</h2>
        <p class="sub">
          按建议改写过的一版。<strong>【方括号】里是模型不敢替你编的数字或细节，
          投出去之前必须换成真的。</strong>
        </p>
        <div class="report" v-html="renderMarkdown(detail.document)"></div>
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
        // 高亮「我的简历」：这一页是从简历页进来的
        topbar: topbarHtml('resume.html'),
        resumeId: null,
        form: { targets: [{ title: '', jd: '' }], interviewIds: [] },
        interviews: [],
        history: [],
        detail: null,
        generating: false
      };
    },
    computed: {
      /**
       * 按 section 分组。键的插入顺序就是产物里的出现顺序
       * （主稿各章节在前，「投「X」的额外调整」在后），所以渲染出来是有序的。
       */
      groupedSuggestions() {
        const out = {};
        for (const s of (this.detail && this.detail.suggestions) || []) {
          const key = s.section || '其他';
          (out[key] = out[key] || []).push(s);
        }
        return out;
      }
    },
    async mounted() {
      if (!requireLogin()) return;
      fillNickname();
      this.resumeId = new URLSearchParams(location.search).get('resumeId');
      if (!this.resumeId) {
        toast('没指定简历，回「我的简历」点「改简历」进来', true);
        return;
      }
      try {
        await Promise.all([this.loadInterviews(), this.loadHistory()]);
      } catch (e) {
        toast(e.message, true);
      }
    },
    methods: {
      fmtTime,
      fmtScore,
      scoreClass,
      renderMarkdown,

      targetText(targets) {
        return (targets || []).map(t => t.title).filter(Boolean).join('、') || '-';
      },
      addTarget() {
        this.form.targets.push({ title: '', jd: '' });
      },
      removeTarget(i) {
        // 至少留一条，否则「+ 再加一条」是唯一出路
        if (this.form.targets.length <= 1) return;
        this.form.targets.splice(i, 1);
      },
      async loadInterviews() {
        this.interviews = await api('/api/interview/list?page=1&size=100');
      },
      async loadHistory() {
        this.history = await api('/api/resume-review?resumeId=' + this.resumeId);
      },
      async generate() {
        // 空岗位名不进请求：后端也会拦，但没必要白等一次往返
        const targets = this.form.targets.filter(t => (t.title || '').trim());
        if (targets.length === 0) {
          toast('至少填一个目标岗位', true);
          return;
        }
        this.generating = true;
        try {
          this.detail = await api('/api/resume-review', {
            method: 'POST',
            body: {
              resumeId: Number(this.resumeId),
              targets: targets.map(t => ({
                title: t.title.trim(),
                jd: (t.jd || '').trim() || null
              })),
              interviewIds: this.form.interviewIds
            }
          });
          await this.loadHistory();
          toast('生成完成');
        } catch (e) {
          toast(e.message, true);
        } finally {
          this.generating = false;
        }
      },
      async view(id) {
        try {
          this.detail = await api('/api/resume-review/' + id);
        } catch (e) {
          toast(e.message, true);
        }
      },
      async remove(id) {
        if (!confirm('确定删掉这次改稿？')) return;
        try {
          await api('/api/resume-review/' + id, { method: 'DELETE' });
          // 删的正好是正在看的那份就把详情收掉，否则页面上留着一份已经不在库里的东西
          if (this.detail && this.detail.id === id) this.detail = null;
          await this.loadHistory();
          toast('已删除');
        } catch (e) {
          toast(e.message, true);
        }
      },
      exportMarkdown() {
        const d = this.detail;
        if (!d) return;
        const stamp = fmtTime(d.createdAt).replace(/[-: ]/g, '');
        const name = safeFileName('简历改稿-' + (d.filename || '').replace(/\.pdf$/i, '') + '-' + stamp);
        // ★ 导出原始 markdown（批注在原位），不是页面上那份去掉批注的 document：
        //   拿去对照着改的时候，建议必须贴着它要改的那一段
        downloadTextFile(name + '.md', d.markdown);
        toast('已导出 ' + name + '.md');
      }
    }
  }).mount('#app');
</script>

</body>
</html>
```

> 一个**和设计文档不完全一致**的地方，故意的：设计文档里 `{interviewMaterial}` 的例子写的是
> 「问题解决 6.8」，这里用的是 `EvalResult.DIMENSION_LABELS` 里的「解题思路」。
> 设计文档那段是手写的示例，五维名字的真源在 Java 里（`ReportNode` / 复盘页用的就是它），
> **别在素材里另起一套名字**——名称不一致会让模型把同一个维度的两次出现当成两件事。

> `renderMarkdown` / `safeFileName` / `downloadTextFile` 都是 `app.js` 里的全局函数，
> 在 `methods` 里列一下就能在模板里用（`report.html:255` 就是这么做的）。
> **不要**像早先 `renderMarkdown` 那样在 `data` 和 `methods` 里重复挂两遍。

- [ ] **Step 4: 编译（资源要进 `target/classes`）**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q clean compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/resume.html \
        src/main/resources/static/resume-review.html \
        src/main/resources/static/css/app.css
git commit -m "feat(web): 改简历页面（建议面板 + 参考稿 + 导出），入口在简历页"
```

---

## Task 7: README 与收尾

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-09-30-resume-review.md`（这份计划本身）

- [ ] **Step 1: 先把这份计划提交掉**

它不属于任何一个 Task 的提交清单，单独一条：

```bash
git add docs/superpowers/plans/2026-09-30-resume-review.md
git commit -m "docs: 简历修改建议功能的实施计划"
```

- [ ] **Step 2: README 的计数改掉（三处，容易漏）**

| 位置 | 原来 | 改成 |
|---|---|---|
| 目录结构里的提示词 | `prompts/        9 个提示词` | `10 个提示词` |
| 目录结构里的页面 | `static/         6 个页面` | `7 个页面` |
| 数据库那节的第一句 | `五张表，都在 schema.sql 里` | `六张表，都在 schema.sql 里` |

- [ ] **Step 3: README 数据库表加一行**

表格里 `| t_graph_trace | 节点级执行轨迹… |` 那一行之后加：

```
| `t_resume_review` | 简历改稿。`markdown` 是 LLM 全文（批注在原位），其余是元信息 |
```

然后在 `### 删除是软删` 那一节的**结尾之后**（那节以「…record 这层挡住了子表就查不出来。」结束、
`### 出题从哪来` 之前）新增一节，正好和上面那节形成对照：

```markdown
### 改稿是物理删

`t_resume_review` 没有软删标记：改稿是派生产物，没有任何聚合依赖它
（不像面试记录——`/stats` 的历史均分按场次聚合，真删会顺手改掉「历史水平」）。
删一份简历时会**连带删掉它的全部改稿**：`t_resume` 是物理删、没有外键级联，
靠 `ResumeService.delete` 上的 `@Transactional` 先删改稿再删简历。
```

- [ ] **Step 4: README 接口清单补四个**

接口清单里 `GET    /api/interview/stats         历史五维均分，给雷达图做对比` 那一行之后加：

```
POST   /api/resume-review              {resumeId, targets:[{title,jd}], interviewIds:[…]} → 详情
GET    /api/resume-review?resumeId=X   某份简历的历史改稿（不含全文）
GET    /api/resume-review/{id}         详情（markdown + document + suggestions）
DELETE /api/resume-review/{id}         删掉一次改稿
```

并加一句：

```markdown
改稿用**独立前缀** `/api/resume-review`，不挂在 `/api/resume/{id}` 下面：
这两个前缀下各有一条 `DELETE /{id}`，删的却是完全不同的东西（删改稿 vs 删简历）。
```

- [ ] **Step 5: README 加一节「改简历怎么改的」**

放在 `### 出题从哪来` 那一节的**结尾之后**、`## 接口` 之前（两节都在讲「产物是怎么来的」，
挨着读顺）：

```markdown
### 改简历

对着目标岗位（可以加多条，每条 = 岗位名必填 + JD 可空）和**可选**的几场面试记录，
一次 LLM 调用出一份纯 Markdown：一份主稿 + 每个岗位一节「投「X」要额外改什么」。
建议写成 `> 建议：` 行**贴在它要改的那一段之前**，所以批注是在原位的。

**为什么是 Markdown 而不是 JSON**：这份产物很长，最容易撞输出长度上限。
`EvalResultParser.extractJson` 取的是第一个 `{` 到最后一个 `}`，响应被截掉尾巴时
整次生成全废；Markdown 的截断只是降级——参考稿写到一半，前面的建议都在库里。
（`max_tokens` 现在**没有发**，输出上限是网关的默认值；要限制就在 yml 里加
`app.llm.max-tokens`，默认 0 表示不传这个字段。`ChatCompletionResponse` 会读
`finish_reason`，是 `length` 就把 `truncated` 存成 1，页面上给一条黄条。）

**页面上的两个视图都是现派生的**：`markdown`（原始的，导出用）、`document`（去掉批注行，
渲染参考稿用）、`suggestions`（抽出来的，按小节分组）。
派生只有 `ResumeReviewParser` 一处实现——不然「怎么抽建议」会在 Java 和 JS 里各写一份。
顺带好处是 `document` 里没有 `>` 行了，页面直接用现成的 `renderMarkdown` 就行。

**模型不许编数字**：提示词里明确要求，凡是不确定的数字、指标、项目名、技术栈，
一律写成【方括号】占位让他自己填。参考稿里出现【】是**正常的**，那是在提醒你填真的
——这份简历是要拿去投的，编出来的东西面试一问就穿帮。

没有 ## 标题、岗位名没填、一场面试都没完成，这些都不报错：批注会归到「简历开头」，
素材会写「（还没有已完成的面试）」，提示词里也让模型别硬编。
```

- [ ] **Step 6: README 的取舍表加三行**

```
| 改稿可能被长度上限截断 | 页面上给一条黄条 + 历史里标「被截断」。想更长就 `app.llm.max-tokens`（默认不发这个字段，因为我们不知道网关的默认值是多少） |
| 多岗位是「一份主稿 + 每岗位一节补丁」 | 不是每个岗位一份完整稿：输出 ×N 太容易截断。这也正是简历真实的维护方式——一份主简历 + 每个岗位的补丁 |
| 改稿是物理删，删简历连带删 | 派生出来的产物，没有聚合依赖它。真删就是真删，不做回收站 |
```

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: 改简历的 README 同步"
```

---

## 验证清单（全部做完后自己过一次）

按用户既有约定：**这些是给他手动跑的，不是让 agent 起服务去跑**。

| 检查 | 怎么验 | 期望 |
|---|---|---|
| 入口 | 「我的简历」每行点「改简历」 | 跳 `resume-review.html?resumeId=X`，顶部高亮「我的简历」 |
| 生成 | 填一条岗位（只填岗位名，JD 留空）→ 生成 | 15-40 秒后出现「修改建议 N 条」+「参考稿」 |
| **没瞎编** | 看参考稿 | **出现【】占位符** ← 这是「它没替你编数字」的证据，不是 bug |
| 条数对得上 | 面板的条数 vs 历史列表的「N 条」 | 一致 |
| 分组 | 看建议面板的小标题 | 是「项目经历（3 条）」这种**简历自己的章节名**，岗位节单独一组 |
| 多岗位 | 加**两个**岗位再生成 | 参考稿里多出两节 `## 投「X」要额外改什么`；面板按节分组 |
| 参考稿干净 | 看参考稿 | **没有** `>` 开头的内容；**没有**空标题（「## 投「Go 后端」…」下面空无一物那种） |
| 导出 | 点「导出 Markdown」→ 用编辑器打开 | 批注在**原位**（紧贴要改的那一段），中文不乱码 |
| 面试素材 | 勾上几场（尤其有低分话题的）再生成 | 建议里出现针对弱项话题的话（如「你并发编程均分 5.0」） |
| 截断提示 | 日志里 `grep "简历改稿完成"` | 有耗时、建议条数、字符数、模型；被截断时那行带 `★ 输出被长度上限截断` |
| 连带删 | 删一份简历后查库 | `SELECT COUNT(*) FROM t_resume_review WHERE resume_id = ?` 为 0 |
| 老库不炸 | 起服务后看启动日志 | 没有 `t_resume_review` 相关的报错（新表，`CREATE TABLE IF NOT EXISTS` 直接建） |
| 不回归 | `/c/Program Files/nodejs/node` 跑 `%TEMP%\PageCheck.js` | 六个老页面全 PASS（`resume.html` 只加了一个按钮，模板事实没动）。**新页面不在它的覆盖里** |

---

## 明确不做的事

- **不做「每个岗位一份完整稿」**：输出 ×N 太容易撞长度上限。主稿 + 岗位补丁已经够用
- **不做软删 / 回收站**：改稿是派生产物，物理删
- **不做版本对比界面**：「改前 vs 改后」靠历史列表自己翻，不专门做 diff
- **不动 `renderMarkdown`**：派生出的 `document` 不需要引用块支持，`app.js` 这次一行不改
- **不给 `InterviewEngine` 加新方法**：`requireRecord` + `dialogues` 已经够了，而且自带归属校验
- **不改 `application.yml`**：`max-tokens` 只在读的那一端加默认值（0 = 不发）
- **不做「改稿时顺手更新简历原文」**：改稿是建议，不是覆盖。要改原件重新上传