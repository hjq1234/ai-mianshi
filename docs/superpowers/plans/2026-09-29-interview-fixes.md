# 面试系统四问题修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉真实跑完一场面试后暴露的四个问题：上一题回答挂在新题下面、后端日志太少、出题全是八股导致实践分失真且重复换角度、复盘页没有导出。

**Architecture:** 改动集中在「出题/评分/路由」这条链和两个页面上，图引擎（`graph/` 包）一行不动。
出题来源从「纯概念话题池」改成「项目经历优先 + 话题轮次上限」；上一轮原文由后端从已落库的
`t_interview_dialogue` 带出来（刷新页面也不丢）；导出纯前端 Blob，不新增接口。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / SQLite + JdbcTemplate / Vue 3 CDN 版 / SLF4J + Logback

**用户既有约束（必须遵守）：**
- 仓库里**不写测试类**。验证一律放 `%TEMP%` 的临时程序（见 `no-tests-in-repo` 记忆）
- **只 commit，不 push**
- 全程中文
- `src/main/resources/application.yml` 里**不得出现真实 api-key**。核查过：key 现在已不在文件里，
  且全历史 `git log -S` 搜不到，没进过 git。Task 2 会把 `api-key` 恢复成 `${DEEPSEEK_API_KEY:}` 形式

---

## Context

用户完整跑了一场真实面试（记录 1，Java 后端开发，10 题，总分 7.2），报回四个问题。核过库里的数据：

| 现象 | 根因（已定位到文件行） |
|---|---|
| ① 新题出来，上一题的回答还挂在下面 | `interview.html:62` 的 `lastAnswer` 气泡渲染在**当前题目气泡之后**，前面还有 `interview.html:38` 的评分块。于是页面读起来是「[上一题评分] [第 N+1 题] [第 N 题的回答]」，像新题已经被答过了 |
| ② 日志太少 | 全项目约 33 条日志、面试链路上只有 8 条；**没有任何请求日志**，项目里连一个 Filter 都没有 |
| ③ 八股 + 实践分失真 + 一直换角度 | 四个独立原因，见下 |
| ④ 复盘页不能导出 | `static/` 和 Java 里没有任何导出/下载代码 |

③ 的四个原因（数据佐证：10 题全是 `JVM 内存模型`，`next_action` 是 `continue ×9 + end`，
practice 维度 `1.5 / 5.0 / 6.0 ×5`）：

1. **`EvaluateNode.java:64` 的强制换话题守卫从来没触发过。** `followUpCount` 只在
   `EvaluateNode.java:103` 判断 `nextAction == DEEPEN` 时 +1，答「中规中矩」时 LLM 给的是
   CONTINUE，计数器一直归零 → 10 题全在一个话题上。`max-follow-up: 3` 形同虚设。
2. **去重视野只有 2 题。** `EvaluateNode.java:43` `HISTORY_WINDOW = 2`，`QuestionNode.renderHistory()`
   只把最近 2 题喂给出题提示词。库里 seq5≈seq8（final 域/this 逸出）、seq3≈seq7（内存屏障）就是这么来的。
3. **路由 100% 听 LLM 的。** `InterviewRouting.decide()` 没有任何分数阈值；而 `evaluate.md:42`
   明确告诉 LLM「4-8 分 → CONTINUE（同话题换个角度）」。用户那场分数 5.5–7.5，全落在这个区间，
   `NextAction.infer()` 的兜底（≥8 DEEPEN / <4 LOWER）也还是 CONTINUE。
   **结论：加分数阈值改不动结果，真正该修的是话题轮次上限。**
4. **话题池全是纯概念，practice 无从打分。** `evaluate.md:36` 要求 LLM 判「是否结合真实项目经验」，
   但话题池是 `JVM 内存模型 / 并发编程 / …`；而且用户那场 `resume_id` 是 NULL、
   `state_json.resumeSummary` 为空，提示词里「优先结合简历项目」也无从发挥。LLM 只能硬凑 5-6 分。

**目标产出：** 一场面试 = 项目/场景题打底（practice 有依据）→ 每个话题最多 3 题、问满就换 →
题目不重复 → 每一步都有日志可查 → 面试页上一轮可折叠回看 → 复盘页能导出 Markdown。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `common/log/RequestLogFilter.java` | 新建 | 每个 `/api` 请求一行访问日志 |
| `common/config/WebConfig.java` | 改 | 注册上面这个 Filter（只挂 `/api/*`） |
| `common/auth/AuthInterceptor.java` | 改 | 把 userId 存到 request 属性，供访问日志读 |
| `biz/interview/node/QuestionNode.java` | 改 | 出题日志 + `{practiceHint}` + `{askedQuestions}` |
| `biz/interview/node/EvaluateNode.java` | 改 | 评分日志 + 话题轮次语义修正 |
| `biz/interview/trace/TraceRecorder.java` | 改 | 分支决策日志（「图下一步是哪个」） |
| `biz/interview/InterviewEngine.java` | 改 | 四个入口 + 每次跑图结束的汇总日志 |
| `biz/interview/TopicTracker.java` | 改 | `followUpCount` 注释改语义（字段本身不动） |
| `common/config/InterviewProperties.java` | 改 | 新增 `practiceTopic` |
| `biz/interview/EvalResult.java` | 改 | 新增 `NULLABLE_DIMENSIONS` |
| `biz/interview/EvalResultParser.java` | 改 | `practice` 允许保留 null |
| `biz/interview/flow/InterviewRouting.java` | 不改 | 决策点保持唯一，本次不动 |
| `common/dto/InterviewTurnVO.java` | 改 | 补上一轮的原文（题号/话题/难度/题目/回答） |
| `controller/InterviewController.java` | 改 | `toTurnVO` 填上面这些字段 |
| `resources/static/interview.html` | 改 | 「上一轮回顾」折叠块，删掉 `lastAnswer` |
| `resources/static/css/app.css` | 改 | `details.prior` 样式 |
| `resources/static/report.html` | 改 | 导出按钮 |
| `resources/static/js/app.js` | 改 | `buildReportMarkdown` / `downloadTextFile` / `safeFileName` |
| `resources/prompts/hint_practice.md` | 新建 | 项目/场景出题要求 |
| `resources/prompts/question_first.md` | 改 | 插入 `{practiceHint}` |
| `resources/prompts/question_followup.md` | 改 | 插入 `{practiceHint}` + `{askedQuestions}` |
| `resources/prompts/evaluate.md` | 改 | practice 可为 null + 告知话题轮次 |
| `resources/application.yml` | 改 | 话题池加「项目经历」、`practice-topic`、`api-key` 恢复环境变量形式 |
| `README.md` | 改 | 同步新行为 |

---

## Task 1: 请求日志 + 出题/评分/分支日志（问题 ②）

**Files:**
- Create: `src/main/java/com/ke/nhservice/aimianshi/common/log/RequestLogFilter.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/config/WebConfig.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/auth/AuthInterceptor.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/EvaluateNode.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/trace/TraceRecorder.java`

- [x] **Step 1: 新建访问日志 Filter**

为什么用 Filter 而不是拦截器：`AuthInterceptor` 只对「映射到 handler」的请求生效，`/api/xxx` 这种
没映射上的路径压根不会进拦截器；而之前正好踩过一次「静态 404 走了兜底分支」的坑。Filter 在最外层，
谁都漏不掉。

```java
package com.ke.nhservice.aimianshi.common.log;

import com.ke.nhservice.aimianshi.common.auth.AuthInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每个 /api 请求打一行访问日志。
 *
 * 用 Filter 而不是 HandlerInterceptor：拦截器只对「映射到 handler」的请求生效，
 * /api 下没映射上的路径（拼错的 id、被删掉的接口）不会进拦截器，而那正是最需要日志的时候。
 *
 * 只打方法和耗时这类元数据，不打请求体——回答和简历是候选人的隐私内容。
 */
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        Exception failure = null;
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            // userId 从 request 属性读，不从 UserContext 读：UserContext 是 ThreadLocal，
            // 请求结束时已经被 AuthInterceptor.afterCompletion 清掉了
            Object userId = request.getAttribute(AuthInterceptor.ATTR_USER_ID);
            String query = request.getQueryString();
            log.info("HTTP {} {}{} → {} | {} ms | user={}{}",
                    request.getMethod(),
                    request.getRequestURI(),
                    query == null ? "" : "?" + query,
                    response.getStatus(),
                    System.currentTimeMillis() - startedAt,
                    userId == null ? "-" : userId,
                    failure == null ? "" : " | 异常: " + failure.getMessage());
        }
    }
}
```

- [x] **Step 2: 注册 Filter（只挂 /api/*）**

`WebConfig.java` 改成：

```java
package com.ke.nhservice.aimianshi.common.config;

import com.ke.nhservice.aimianshi.common.auth.AuthInterceptor;
import com.ke.nhservice.aimianshi.common.log.RequestLogFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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

    /**
     * 访问日志。只挂 /api/*：每个静态页面都要拉 css/js，记进来只会把日志冲淡。
     * order 放最低，让它成为最内层的 filter——这样读到的是已经处理完的状态码，
     * 而不是还没被异常处理器改写过的 200。
     */
    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter() {
        FilterRegistrationBean<RequestLogFilter> bean =
                new FilterRegistrationBean<>(new RequestLogFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.LOWEST_PRECEDENCE);
        return bean;
    }
}
```

- [x] **Step 3: AuthInterceptor 把 userId 交出去**

```java
    /** 访问日志过滤器靠这个 request 属性拿到「请求是谁发的」 */
    public static final String ATTR_USER_ID = "app.userId";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HEADER);
        String token = header != null && header.startsWith(PREFIX)
                ? header.substring(PREFIX.length()).trim()
                : null;
        // verify 失败会抛 BizException(401)，由 GlobalExceptionHandler 统一转成响应
        Long userId = tokenUtil.verify(token);
        UserContext.set(userId);
        // 顺手存一份到 request 上：ThreadLocal 在 afterCompletion 就被清了，
        // 而 Filter 是在整条链路之外读的，读不到 ThreadLocal
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }
```

- [x] **Step 4: QuestionNode 出题日志**

`execute()` 里，替换原来的 `log.debug("第 {} 题生成完成，{} 字符", ...)`：

```java
        long startedAt = System.currentTimeMillis();
        String question = llm.chat(prompts.render(template, vars)).trim();
        long cost = System.currentTimeMillis() - startedAt;

        // 题目全文要打：出题是整条链上最贵的一步，事后判断「这题为什么这么问」只能靠它。
        // 但 prompt 本身不能打——简历摘要就拼在里面。
        log.info("第 {} 题出题完成 | 话题={} 难度={} | {} ms | {} 字符 | 题目：{}",
                state.getQuestionIndex(),
                state.getTopicTracker() == null ? "-" : state.getTopicTracker().getCurrentTopic(),
                state.getCurrentDifficulty().getLabel(),
                cost, question.length(), question);
```

- [x] **Step 5: EvaluateNode 评分日志 + 话题轮次**

把 `execute()` 里从 `EvalResult result = evaluate(...)` 到 `tracker.setFollowUpCount(...)` 这段换成：

```java
        long startedAt = System.currentTimeMillis();
        EvalResult result = evaluate(llm, prompts, state, question, answer);
        NextAction suggested = result.getNextAction();

        // 同一个话题问满轮数就强制换话题，不完全交给 LLM 判断。
        // ★ followUpCount 现在是「本话题已经答过几轮」，不是「连续 deepen 了几次」——
        // 旧写法只在 DEEPEN 时 +1，而答「中规中矩」时 LLM 给的是 CONTINUE，
        // 计数器永远是 0，这个守卫一次都没触发过（实测 10 题全问在同一个话题上）。
        boolean forced = false;
        if (tracker.getFollowUpCount() >= props.getMaxFollowUp()) {
            forced = suggested != NextAction.SWITCH;
            if (forced) {
                log.info("话题「{}」已问满 {} 轮，把 LLM 给的 {} 改判为 SWITCH",
                        tracker.getCurrentTopic(), props.getMaxFollowUp(), suggested);
            }
            result.setNextAction(NextAction.SWITCH);
        }
        state.setEvalResult(result);

        String routing = InterviewRouting.decide(state);
        state.setLastRouting(routing);

        tracker.markCovered(tracker.getCurrentTopic());

        String nextTopic = InterviewRouting.SWITCH.equals(routing)
                ? tracker.suggestNextTopic() : null;
        state.setNextTopic(nextTopic);

        Dialogue dialogue = new Dialogue();
        dialogue.setSeq(state.getQuestionIndex());
        dialogue.setTopic(tracker.getCurrentTopic());
        dialogue.setDifficulty(state.getCurrentDifficulty().getLabel());
        dialogue.setQuestion(question);
        dialogue.setAnswer(answer);
        dialogue.setScore(result.getOverall());
        dialogue.setDimensions(result.getDimensions());
        dialogue.setComment(result.getComment());
        dialogue.setNextAction(InterviewRouting.END.equals(routing)
                ? RecordStatus.NEXT_ACTION_END : routing);
        dialogue.setNextTopic(nextTopic);
        dao.upsertDialogue(dialogue, state.getRecordId());

        state.getDialogues().add(dialogue);
        state.getScoreHistory().add(result.getOverall());
        state.pushHistory(new HistoryItem(question, answer, result.getOverall()), HISTORY_WINDOW);

        // 换话题就重新数；不换就累加。DEEPEN 和 CONTINUE 都算「还在这个话题上」
        tracker.setFollowUpCount(InterviewRouting.SWITCH.equals(routing)
                ? 0 : tracker.getFollowUpCount() + 1);

        // 一次评分要写 4 行日志才够复盘：分数、五维、LLM 建议 vs 实际分支、耗时
        log.info("第 {} 题评分 | 话题={} 难度={} | {} ms | 总分={} | LLM 建议={} 实际分支={}{}",
                state.getQuestionIndex(), dialogue.getTopic(), dialogue.getDifficulty(),
                System.currentTimeMillis() - startedAt, result.getOverall(),
                suggested, routing, forced ? "（话题问满，已改判）" : "");
        log.info("第 {} 题五维 | {}", state.getQuestionIndex(), result.getDimensions());
        log.info("第 {} 题评语 | {}", state.getQuestionIndex(), result.getComment());

        // 答案已消费，清掉。这样下一轮回到 wait_answer 才会正确挂起
        state.setAnswer(null);
        return NodeResult.NEXT;
```

同时新增 import：`com.ke.nhservice.aimianshi.common.constant.NextAction`（已有）、
`java.util.Map`（已有）。另外把类注释里「同一话题连续追问计数」的描述改成「本话题已答轮数」。

- [x] **Step 6: TraceRecorder 打分支决策**

`onBranchDecided` 里，`write(...)` 之前加：

```java
        log.info("图分支决策 | 面试={} 第 {} 题 | {} → {}",
                state.getRecordId(), state.getQuestionIndex(), from, decided);
```

`onNodeExit` 里加一行 DEBUG（节点进出太密，INFO 会把上面那些有用的冲淡）：

```java
        log.debug("图节点 | 面试={} 第 {} 题 | {} | {} ms | {}",
                state.getRecordId(), state.getQuestionIndex(), node, costMs, status);
```

- [x] **Step 7: InterviewEngine 入口日志 + 每次跑图汇总**

四个入口各加一行，`run()` 里加一行：

```java
    public RunResult<InterviewState> start(Long userId, Long resumeId, String position,
                                           String company, String domain, String difficulty) {
        long recordId = dao.insertRecord(userId, resumeId, position, company, domain, difficulty);
        ...
        log.info("开始面试 | 面试={} 用户={} | 岗位={} 公司={} 方向={} 难度={} 简历={}",
                recordId, userId, position, company, domain, difficulty,
                resumeId == null ? "未使用" : resumeId);
```

```java
        log.info("提交回答 | 面试={} 第 {} 题 | 回答 {} 字符",
                recordId, loaded.state().getQuestionIndex(), answer.trim().length());
```

```java
        log.info("主动结束面试 | 面试={} 已答 {} 题",
                recordId, loaded.state().getDialogues().size());
```

```java
        log.info("继续面试 | 面试={} 游标={}", recordId, loaded.cursor());
```

```java
    private RunResult<InterviewState> run(InterviewState state, String cursor) {
        Execution<InterviewState> execution = new Execution<>(state, cursor);
        long startedAt = System.currentTimeMillis();
        RunResult<InterviewState> result = graph.run(execution);
        log.info("图执行结束 | 面试={} 状态={} 停在={} 游标={} | {} ms",
                state.getRecordId(), result.status(), result.stoppedAt(),
                execution.getCursor(), System.currentTimeMillis() - startedAt);

        persist(state, execution.getCursor(), result);
        return result;
    }
```

- [x] **Step 8: 编译**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q clean compile
```

预期：无输出（`-q` 下只有错误才打印），`target/classes` 生成。

- [x] **Step 9: 起服务看日志**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o spring-boot:run > /c/Users/huangjinqing001/AppData/Local/Temp/run.log 2>&1 &
```

等起来后（curl 被权限规则禁了，用已有的 `%TEMP%` 下的单文件 Java HttpClient 程序，或直接在浏览器打开）
访问一次 `login.html`、登录、进「面试记录」列表页，然后：

```bash
grep "HTTP " /c/Users/huangjinqing001/AppData/Local/Temp/run.log
```

预期：出现 `/api/auth/login`、`/api/resume/list`、`/api/interview/list?page=1&size=50` 等若干行，
且登录后的那几行 `user=1`，登录那行是 `user=-`（登录接口被 AuthInterceptor 排除，没设属性）。

- [x] **Step 10: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/common/log/RequestLogFilter.java \
        src/main/java/com/ke/nhservice/aimianshi/common/config/WebConfig.java \
        src/main/java/com/ke/nhservice/aimianshi/common/auth/AuthInterceptor.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/EvaluateNode.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/trace/TraceRecorder.java
git commit -m "feat(log): 补请求日志与出题/评分/分支/图执行日志"
```

---

## Task 2: 让「实践分」有依据（问题 ③ 之 practice）

**Files:**
- Create: `src/main/resources/prompts/hint_practice.md`
- Modify: `src/main/resources/prompts/question_first.md`
- Modify: `src/main/resources/prompts/question_followup.md`
- Modify: `src/main/resources/prompts/evaluate.md`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/config/InterviewProperties.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResult.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResultParser.java`

思路：不去做「LLM 抽取简历项目」那一步（多一次调用、多 10 秒等待）。
改成**话题池里加一个「项目经历」话题**，这个话题的出题走专门的 `hint_practice` 要求：
有简历就点名问简历里的项目，没有简历就问一个真实业务场景。这样 practice 维度两头都有着落，
而且用户不传简历时也不会退化回纯八股。

- [x] **Step 1: 新建 hint_practice.md**

`src/main/resources/prompts/hint_practice.md`：

```markdown
本题话题是「项目经历」，出题必须落到候选人的真实经历上，不能问成知识点。

- 简历摘要里能看到具体项目或系统 → 直接点名那个项目提问，问他的取舍和踩过的坑。
  例：你简历里的 XX 系统，当时为什么选 A 方案而不是 B？
- 简历摘要为空，或看不出具体项目 → 假设一个真实业务场景来问。
  例：线上接口的 P99 从 200ms 涨到 2s，你从哪一步开始查？
  不要问「请介绍一下你的项目」这种没有信息量的问题。
- 无论哪种情况，都要问出「你实际是怎么做的」，而不是「这个知识点是什么」。
```

- [x] **Step 2: 两个出题提示词插入 {practiceHint}**

`question_first.md`，「本题话题：{topic}」之后空一行插入：

```
本题话题：{topic}

{practiceHint}
```

`question_followup.md`，「本题难度：{difficulty}」之后空一行插入：

```
- 本题难度：{difficulty}

{practiceHint}
```

注意：`PromptLoader.render` 对**没传的**占位符会原样保留（方便发现漏传），所以
Step 5 必须无条件 put `practiceHint`，哪怕是空串。

- [x] **Step 3: evaluate.md 放开 practice**

把 `- practice：是否结合真实项目经验` 那行换成：

```
  - practice：是否结合真实项目经验。只有当本题确实在考察项目或线上场景、且候选人谈了自己的实际做法时才打分；
    纯概念题、候选人也没结合实际经历时，输出 null（宁可不打，也不要凑一个中间分）
```

并在 `nextAction` 字段说明之前加一行上下文：

```
- 本题话题「{topic}」已经问过 {topicRounds} 轮，最多 {maxFollowUp} 轮；达到上限时 nextAction 请直接给 "SWITCH"
```

- [x] **Step 4: application.yml 加 practice-topic 和话题池**

`app.interview` 段改成（同时把 `api-key` 恢复成环境变量形式 —— 核查过 key 现在不在文件里、
也从未进过 git 历史，但恢复后能彻底杜绝以后手滑提交）：

```yaml
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
    # 同一话题最多问几题，问满强制换话题（算的是本话题答过几轮，不只是 deepen）
    max-follow-up: 3
    max-steps: 200
    # 这个话题的出题会带上 hint_practice：问真实项目/场景，practice 维度才有依据
    practice-topic: 项目经历
    # 话题池：switch 节点从这里挑没聊过的话题
    topics:
      Java:
        - 项目经历
        - JVM 内存模型
        - 并发编程
        - 集合框架
        - Spring 原理
        - MySQL
        - Redis
      Go:
        - 项目经历
        - GMP 调度模型
        - 内存管理与 GC
        - channel 与并发
        - 运行时与逃逸分析
      Python:
        - 项目经历
        - GIL 与并发模型
        - 装饰器与元编程
        - 异步 asyncio
        - 内存管理与垃圾回收
    默认:
      - 项目经历
      - 基础知识
      - 系统设计
      - 排查与优化
```

**⚠️ 不要**把你本地的 key 写回 `api-key`。本地调试用环境变量：
`export DEEPSEEK_API_KEY=你的key`。

- [x] **Step 5: InterviewProperties 加 practiceTopic**

```java
    /** 这个话题的出题会带上 hint_practice。取自 application.yml 的 app.interview.practice-topic */
    private String practiceTopic = "项目经历";
```

加 getter/setter：

```java
    public String getPracticeTopic() { return practiceTopic; }

    public void setPracticeTopic(String practiceTopic) { this.practiceTopic = practiceTopic; }
```

- [x] **Step 6: QuestionNode 填 practiceHint**

`execute()` 里，取 props：

```java
        LlmClient llm = ctx.get(LlmClient.class);
        PromptLoader prompts = ctx.get(PromptLoader.class);
        InterviewProperties props = ctx.get(InterviewProperties.class);
```

拼 vars 时加：

```java
        vars.put("practiceHint", practiceHint(prompts, props, state));
```

新增私有方法：

```java
    /**
     * 话题是「项目经历」时附上专门的要求，其余话题给空串。
     * 空串不是偷懒：PromptLoader 对没传的占位符会原样留着 {practiceHint}，必须显式覆盖掉。
     */
    private String practiceHint(PromptLoader prompts, InterviewProperties props, InterviewState state) {
        String topic = state.getTopicTracker() == null
                ? null : state.getTopicTracker().getCurrentTopic();
        return props.getPracticeTopic() != null && props.getPracticeTopic().equals(topic)
                ? prompts.load("hint_practice") : "";
    }
```

- [x] **Step 7: 允许 practice 为 null**

`EvalResult.java` 加常量：

```java
    /** 允许留空的维度。纯概念题没有项目背景，硬打分只会得到一堆 5-6 分的假数据 */
    public static final List<String> NULLABLE_DIMENSIONS = List.of("practice");
```

`degraded()` 里把可空维度也置空（现在全 0，看起来像候选人挂了）：

```java
        Map<String, Double> dims = new LinkedHashMap<>();
        for (String key : DIMENSIONS) {
            dims.put(key, NULLABLE_DIMENSIONS.contains(key) ? null : 0.0);
        }
```

`EvalResultParser.normalizeDimensions` 换成：

```java
    /** 永远返回完整的五个维度，顺序固定——前端雷达图直接按顺序画。可空维度允许保持 null */
    private static Map<String, Double> normalizeDimensions(Map<String, Double> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String key : EvalResult.DIMENSIONS) {
            Double value = raw == null ? null : raw.get(key);
            // LLM 按 evaluate.md 的约定，遇到纯概念题会给 practice: null，
            // 这里必须原样保留；clamp 会把 null 变成 0，那就是在编分数
            out.put(key, value == null && EvalResult.NULLABLE_DIMENSIONS.contains(key)
                    ? null : clamp(value));
        }
        return out;
    }
```

- [x] **Step 8: 编译 + 看真实提示词**

编译同 Task 1 Step 8。然后写一个临时程序把渲染结果打出来（`%TEMP%\PromptCheck.java`，
用既有的跑法）：

```bash
CP="target/classes;$(cat /c/Users/huangjinqing001/AppData/Local/Temp/cp.txt)"
cd /c/Users/huangjinqing001/AppData/Local/Temp
"$JAVA_HOME/bin/java" -Dstdout.encoding=UTF-8 -cp "D:/ideaProjects/ai-mianshi/$CP" PromptCheck
```

`PromptCheck` 要做的事：`new PromptLoader().render("question_first", Map.of("practiceHint", "【项目经历要求】…", ...))`，
断言输出里 **不含 `{practiceHint}` 字面量**、且含 `【项目经历要求】`；再把 `practiceHint` 传空串渲染一次，
确认空串时渲染结果正常、不留多余占位符。

- [ ] **Step 9: 起服务跑一次 start** ← 没跑：本机没有可用的 api-key（yml 里是 `${DEEPSEEK_API_KEY:}`，环境变量未设），真实 start 会直接 500。同一条链路改由 ApiCheck 覆盖（假 LLM + 临时 SQLite + 真 HTTP + 真鉴权），44 条断言全过

用真实 key（环境变量）起服务，在首页选 Java / 中等 / **不传简历** 起一场，看日志第一行出题的
`题目：` 是不是场景题（应含「线上」「排查」「为什么选」这类词），而不是「请介绍 JVM 内存模型」。
确认后再看评分日志里 `practice=` 是否有值（有简历就应该是真分数，没有简历问的是场景题，也应该有分）。

- [x] **Step 10: Commit**

```bash
git add src/main/resources/prompts/hint_practice.md \
        src/main/resources/prompts/question_first.md \
        src/main/resources/prompts/question_followup.md \
        src/main/resources/prompts/evaluate.md \
        src/main/resources/application.yml \
        src/main/java/com/ke/nhservice/aimianshi/common/config/InterviewProperties.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResult.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/EvalResultParser.java
git commit -m "feat(interview): 话题池加入项目经历，实践维度允许为空"
```

提交前用 `git diff --cached src/main/resources/application.yml` 再确认一眼没有 key 字样。

---

## Task 3: 话题轮次上限 + 出题去重（问题 ③ 之「一直换个角度」）

**Files:**
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/TopicTracker.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java`
- Modify: `src/main/resources/prompts/question_followup.md`

Task 1 Step 5 已经把 `followUpCount` 的计数语义改对了（换话题归零、否则累加）。
这一步把注释、提示词侧的重复防护补齐。

**刻意不做的事：** 不在 `InterviewRouting.decide()` 里加分数阈值。用户那场分数是 5.5–7.5，
全在「4-8 → CONTINUE」区间里，`NextAction.infer()` 的兜底算出来同样是 CONTINUE，
加阈值改不动结果。真正让问题消失的是轮次上限。保持决策点唯一、不引入第二套判断。

- [x] **Step 1: TopicTracker 注释改语义**

```java
    /**
     * 当前话题已经答过几轮。达到上限就强制换话题（见 EvaluateNode）。
     *
     * 注意是「本话题答过几轮」而不是「连续追问了几次」：旧实现只在 LLM 给 DEEPEN 时 +1，
     * 而答得中规中矩时 LLM 给的是 CONTINUE，计数器永远是 0，上限一次都没生效过。
     */
    private int followUpCount;
```

- [x] **Step 2: QuestionNode 把「问过的题」喂给出题提示词**

`renderHistory` 旁边新增：

```java
    /**
     * 已经问过的所有题目，只给题目不给答案。
     *
     * 原来只靠「最近 2 题」的滑动窗口去重，视野太窄：实测第 5 题和第 8 题都在问
     * final 域与 this 逸出。这里把全部题面列出来（10 题也就 600 字左右），
     * 让 LLM 自己看清哪些问过了。
     */
    private String renderAsked(InterviewState state) {
        if (state.getDialogues().isEmpty()) {
            return "（还没有问过任何问题）";
        }
        StringBuilder sb = new StringBuilder();
        for (Dialogue item : state.getDialogues()) {
            sb.append(item.getSeq()).append(". ").append(item.getQuestion()).append('\n');
        }
        return sb.toString().trim();
    }
```

`vars` 里加：

```java
        vars.put("askedQuestions", renderAsked(state));
```

import 加 `com.ke.nhservice.aimianshi.biz.interview.Dialogue`。

- [x] **Step 3: question_followup.md 用上 askedQuestions**

「最近的问答记录：{history}」之后插入：

```
已经问过的问题（不要重复，也不要换个说法再问一遍）：
{askedQuestions}
```

并把第 5 条要求改成：

```
5. 上面「已经问过的问题」里出现过的题目，不要换个说法再问一遍
```

- [x] **Step 4: 编译 + 验证去重视野**

编译同 Task 1 Step 8。临时程序验证（`%TEMP%\RoutingCheck.java`，不放进仓库）：

`EvaluateNode` 会往库里写对话，而 `InterviewDao` 是依赖 `JdbcTemplate` 的具体类（不是接口），
没法用 stub。所以程序里挂一个**真的临时 SQLite**：`DriverManager` 建
`jdbc:sqlite:<tmp>/routing-check.db`，用 Spring 的 `ResourceDatabasePopulator` 跑一遍
`classpath:schema.sql`（`target/classes/schema.sql`，用 `FileSystemResource` 指过去），
建一条 record，再 `new InterviewDao(new JdbcTemplate(dataSource))`。

`NodeContext` 里放：上面的真实 dao、`new PromptLoader()`、一份手工 `InterviewProperties`
（`maxFollowUp = 3`）、以及一个假 `LlmClient`（`chat()` 直接返回固定评分 JSON，
`nextAction` 写 `"CONTINUE"`）。然后：

1. `topicTracker.currentTopic = "JVM 内存模型"`、`allTopics = [项目经历, JVM 内存模型, 并发编程]`、
   `coveredTopics = [项目经历, JVM 内存模型]`、`followUpCount = 3`、`questionIndex = 4`、
   `maxQuestions = 10`、`questionText/answer` 随便填。
   跑 `EvaluateNode.execute()`，断言 `state.getLastRouting()` 是 `switch`、
   `state.getNextTopic()` 是「并发编程」。
2. 同样的状态但 `followUpCount = 0`，断言路由仍是 `continue`、`nextTopic` 为 null（守卫不能误伤第一轮）。
3. 断言 `followUpCount` 在 switch 后归零、在 continue 后变成 1。

- [ ] **Step 5: 用真实 LLM 跑满 10 题** ← 没跑：同上，没有 key。改用 RoutingCheck 的用例 5 离线复现这个场景：假 LLM 全程只回 CONTINUE（实测那场就是 9 次 CONTINUE），连跑 10 题，断言话题每 3 题一换、switch 出现 3 次、10 题落在 4 个话题上。旧实现这里会 10 题全在同一话题

起服务，在首页起一场 Java / 中等（传不传简历都行），连续答 10 题（回答随便写点）。
跑完看日志：

```bash
grep -E "出题完成|评分 \||分支决策" /c/Users/huangjinqing001/AppData/Local/Temp/run.log
```

预期：`话题=` 每 3 题换一次（项目经历 → JVM 内存模型 → 并发编程 → 集合框架…），
`实际分支=` 里 `switch` 至少出现 3 次，题目两两不重复。

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/TopicTracker.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/node/QuestionNode.java \
        src/main/resources/prompts/question_followup.md
git commit -m "fix(interview): 话题问满轮数强制切换 + 出题去重视野扩到全部历史"
```

---

## Task 4: 上一轮回顾（问题 ①）

**Files:**
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/dto/InterviewTurnVO.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/controller/InterviewController.java`
- Modify: `src/main/resources/static/interview.html`
- Modify: `src/main/resources/static/css/app.css`

为什么字段放后端而不是前端记：前端记的 `lastAnswer` 一刷新就没了，而 `/resume` 是刷新页面走的路径。
`InterviewController.toTurnVO` 里**已经**取了 `Dialogue last = lastDialogue(state.getRecordId())`
（`InterviewController.java:149`），补几个字段几乎零成本。

- [x] **Step 1: VO 补上一轮原文**

```java
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
        String error,
        // 上一轮的原文。只有分数没有题目和回答，复盘时想不起「我当时是怎么答的」；
        // 而且从后端带出来，刷新页面也不会丢
        Integer lastSeq,
        String lastTopic,
        String lastDifficulty,
        String lastQuestion,
        String lastAnswer) {
}
```

- [x] **Step 2: Controller 填字段**

`toTurnVO` 的 `new InterviewTurnVO(...)` 末尾（`error` 之后）追加：

```java
                last == null ? null : last.getSeq(),
                last == null ? null : last.getTopic(),
                last == null ? null : last.getDifficulty(),
                last == null ? null : last.getQuestion(),
                last == null ? null : last.getAnswer());
```

- [x] **Step 3: interview.html 换成「上一轮回顾」折叠块**

把 `interview.html:37-62` 整段（`<div class="feedback" v-if="turn.lastScore != null">` 到
`<div class="bubble candidate" v-if="lastAnswer">`）替换成：

```html
          <!-- 上一轮回顾：默认折叠。
               原来这里是一块评分 + 一个孤零零的「你的回答」气泡，位置在当前题目之下，
               新题一出来就读成「新题已经被答过了」。现在整轮（题目+回答+评分）收进一个折叠块，
               默认收起，要看点一下 -->
          <details class="feedback prior" v-if="showPrior">
            <summary>
              上一轮回顾 · 第 {{ turn.lastSeq }} 题 · {{ turn.lastTopic || '综合' }}
              <span class="score" :class="scoreClass(turn.lastScore)">
                {{ fmtScore(turn.lastScore) }}
              </span>
              <span class="tag">{{ NEXT_ACTION_LABELS[turn.nextAction] || turn.nextAction }}</span>
            </summary>
            <div class="prior-body">
              <p class="mt8"><span class="muted">问：</span>{{ turn.lastQuestion }}</p>
              <p class="mt8" style="white-space:pre-wrap">
                <span class="muted">答：</span>{{ turn.lastAnswer || '（未作答）' }}
              </p>
              <p class="mt8" v-if="turn.lastComment">
                <span class="muted">评语：</span>{{ turn.lastComment }}
              </p>
              <div class="dim-grid mt8" v-if="turn.lastDimensions">
                <div class="dim-item" v-for="(v, k) in turn.lastDimensions" :key="k">
                  <span class="k">{{ DIMENSION_LABELS[k] || k }}</span>
                  <span class="v">{{ fmtScore(v) }}</span>
                </div>
              </div>
            </div>
          </details>

          <div class="bubble interviewer" v-if="turn.question">
            <span class="who">
              第 {{ turn.questionIndex }} 题 · {{ turn.topic || '综合' }} · {{ turn.difficulty || '-' }}
            </span>
            {{ turn.question }}
          </div>
```

- [x] **Step 4: 删掉前端的 lastAnswer**

`data()` 里删掉 `lastAnswer: '',`（**必须删**，否则它和模板里的 `turn.lastAnswer` 是两个东西，
留着会让人以为还能用）。`submit()` 里删掉 `this.lastAnswer = submitted;` 这一行。

`computed` 里加：

```js
      // 上一轮和当前题是同一道题时不显示（刚开局、或答完最后一题还没出下一题），
      // 否则会和下面的题面重复
      showPrior() {
        const t = this.turn;
        return !!(t && t.lastSeq && t.lastQuestion && t.lastQuestion !== t.question);
      }
```

- [x] **Step 5: app.css 加样式**

追加到「面试对话」段末尾：

```css
/* ── 上一轮回顾（默认折叠） ── */

details.prior { cursor: pointer; }

details.prior > summary {
  display: flex;
  align-items: center;
  gap: 10px;
  list-style: none;
}

/* 去掉 Chrome/Safari 默认的三角，用 summary 自己那行文字当提示 */
details.prior > summary::-webkit-details-marker { display: none; }

details.prior > summary::before { content: '▸'; color: var(--muted); }
details.prior[open] > summary::before { content: '▾'; }

details.prior .prior-body p { margin: 0; }
```

注意：`.feedback` 上已有 `max-width: 78%`，`details.prior` 复用它，别再写一遍宽度——
上次复盘页就踩过「`muted small` 里的 `.small` 根本没定义」这种照抄不存在的类的坑。

- [x] **Step 6: 用 node 沙箱验页面不白屏**

复用 `%TEMP%\PageCheck.js`。改动：把 `TURN_ANSWERED` 夹具补上
`lastSeq: 1, lastTopic: 'JVM 内存模型', lastQuestion: '……', lastAnswer: '……'`，
并在 `SUITES['interview.html']` 里断言：

- 渲染结果含「上一轮回顾」和 `lastAnswer` 的内容
- 断言**不含** `lastAnswer` 这个 Vue 变量名残留导致的报错（沙箱里 Vue 渲染失败会直接抛）

```bash
"/c/Program Files/nodejs/node" /c/Users/huangjinqing001/AppData/Local/Temp/PageCheck.js
```

预期：`interview.html` 那一组全 PASS，其余页面不回归。

- [ ] **Step 7: 人工看一眼** ← 没跑：没有浏览器可用。改成 ApiCheck 断言「上一轮原文确实从后端带出来」（lastSeq/lastQuestion/lastAnswer 三个字段的实际值都打出来了）+ PageCheck 断言 showPrior 的三种取值和模板形态

起服务，答一道题，确认：新题下面不再挂着上一题的回答；「上一轮回顾 ▸」收起时只占一行；
点开能看到题目、回答、评语、五维；刷新页面（F5）后这一块还在。

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/ke/nhservice/aimianshi/common/dto/InterviewTurnVO.java \
        src/main/java/com/ke/nhservice/aimianshi/controller/InterviewController.java \
        src/main/resources/static/interview.html \
        src/main/resources/static/css/app.css
git commit -m "fix(web): 上一轮问答收进折叠块，字段由后端带回（刷新不丢）"
```

---

## Task 5: 复盘页导出 Markdown（问题 ④）

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/report.html`

纯前端：`/api/interview/{id}/detail` 返回的 `detail` 里已经有题目、回答、评语、五维、分支、
next_topic、报告，**不需要新接口**。顺带避开「导出链接是 `<a href>` 带不上 Authorization 头」这个坑。

- [x] **Step 1: app.js 加导出函数**

追加到文件末尾（`DIFFICULTY_OPTIONS` 之前的位置也行，放末尾更清楚）：

```js
/* ────────────────────────── 复盘导出 ────────────────────────── */

/** Windows 文件名里 / \ : * ? " < > | 都是非法的，统一换掉 */
function safeFileName(name) {
  const cleaned = String(name).replace(/[\\/:*?"<>|]/g, '-').trim();
  return cleaned || 'export';
}

/** 表格单元格：| 会把表格撑破，换行会把一行拆成两行 */
function mdCell(text) {
  return String(text || '').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

/**
 * 复盘详情 → Markdown 全文。
 * 数据全部来自 /api/interview/{id}/detail，所以导出不需要新接口，
 * 也就不存在「下载链接带不上 token」的问题。
 */
function buildReportMarkdown(detail) {
  const action = a => NEXT_ACTION_LABELS[a] || a || '-';
  const topics = [...new Set(detail.dialogues.map(d => d.topic).filter(Boolean))];
  const L = [];

  L.push(`# ${detail.position || '技术面试'} · 面试复盘`, '');
  L.push(`- 公司：${detail.company || '-'}`);
  L.push(`- 方向：${detail.domain || '-'}`);
  L.push(`- 难度：${detail.difficulty || '-'}`);
  L.push(`- 时间：${fmtTime(detail.createdAt)}`);
  L.push(`- 状态：${detail.status === 'finished' ? '已结束' : '进行中'}`);
  L.push(`- 总分：${fmtScore(detail.totalScore)} / 10（共 ${detail.dialogues.length} 题）`);
  L.push(`- 覆盖话题：${topics.join('、') || '-'}`);
  if (detail.error) L.push(`- 备注：${detail.error}`);
  L.push('');

  L.push('## 决策链', '');
  L.push('| 题号 | 话题 | 难度 | 得分 | 图的分支 | 说明 |');
  L.push('|---|---|---|---|---|---|');
  for (const d of detail.dialogues) {
    const note = (d.comment || '') + (d.nextTopic ? `（换到：${d.nextTopic}）` : '');
    L.push(`| ${d.seq} | ${mdCell(d.topic || '-')} | ${mdCell(d.difficulty || '-')} `
         + `| ${fmtScore(d.score)} | ${action(d.nextAction)} | ${mdCell(note)} |`);
  }
  L.push('');

  L.push('## 逐题详情', '');
  for (const d of detail.dialogues) {
    L.push(`### 第 ${d.seq} 题 · ${d.topic || '综合'} · ${d.difficulty || '-'} · ${fmtScore(d.score)} 分`, '');
    L.push(`**问：** ${d.question || '-'}`, '');
    L.push('**答：**', '', (d.answer || '（未作答）').trim(), '');
    if (d.comment) L.push(`**评语：** ${d.comment}`, '');
    const dims = d.dimensions || {};
    const keys = Object.keys(dims);
    if (keys.length) {
      // 没有项目背景的题 practice 是 null，这里会显示成 '-'，不是 0 分
      L.push('**五维：** ' + keys
        .map(k => `${DIMENSION_LABELS[k] || k} ${fmtScore(dims[k])}`).join(' · '), '');
    }
    L.push(`**下一步：** → ${action(d.nextAction)}${d.nextTopic ? `（换到：${d.nextTopic}）` : ''}`, '');
  }

  L.push('## AI 综合报告', '');
  L.push(detail.report || '（这场面试还没结束，没有综合报告）', '');
  L.push('---', `导出时间：${fmtTime(Date.now())}`);
  return L.join('\n');
}

/** Blob + <a download>。不用 window.open：那会带上 token 问题，也没法指定文件名 */
function downloadTextFile(filename, text, mime = 'text/markdown;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type: mime }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  // 立刻 revoke 在部分浏览器上会打断下载，等一拍再释放
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
```

- [x] **Step 2: report.html 加按钮**

概览卡片里，`<p class="muted mt8">共 … 题 · …</p>` 之后插入：

```html
        <div class="mt8" style="display:flex;justify-content:flex-end">
          <button class="ghost small" @click="exportMarkdown">导出 Markdown（问题 + 回答 + 评语 + 决策）</button>
        </div>
```

`methods` 里加：

```js
      exportMarkdown() {
        if (!this.detail) return;
        const d = this.detail;
        const stamp = fmtTime(d.createdAt).replace(/[-: ]/g, '');
        const name = safeFileName(`面试复盘-${d.position || '技术面试'}-${stamp}`);
        downloadTextFile(name + '.md', buildReportMarkdown(d));
        toast('已导出 ' + name + '.md');
      }
```

`buildReportMarkdown` / `downloadTextFile` / `safeFileName` 是 app.js 里的全局函数，
在 methods 里直接调用即可，不用再往 data/methods 上挂（**不要**像 `renderMarkdown` 那样
在 data 和 methods 里重复写两遍）。

- [x] **Step 3: node 验导出内容**

临时程序 `%TEMP%\ExportCheck.js`（不放进仓库）：读 `list.json` / `detail` 夹具，
`eval` app.js 里的三个函数，然后断言：

- `buildReportMarkdown(detail)` 含 `# `、`## 决策链`、`## 逐题详情`、`## AI 综合报告`
- 每一题都出现 `### 第 N 题`
- 评语、nextAction 的中文标签（`换个角度` 等）出现
- 含 `|` 的评语被转义成 `\|`
- `safeFileName('面试复盘-Java/后端: 2026-09-28.md')` 里的非法字符全变成 `-`

```bash
"/c/Program Files/nodejs/node" /c/Users/huangjinqing001/AppData/Local/Temp/ExportCheck.js
```

- [ ] **Step 4: 人工下一次** ← 没跑：没有浏览器。改成 PageCheck 里真调一次 exportMarkdown，把产出的 Blob 读回来断言含岗位名、决策链、逐题详情

起服务 → 打开一场跑完的面试的复盘页 → 点「导出 Markdown」→ 用编辑器打开下载的文件，
确认题号、回答、评语、五维、分支决策、综合报告都在，中文不乱码。

- [x] **Step 5: Commit**

```bash
git add src/main/resources/static/js/app.js src/main/resources/static/report.html
git commit -m "feat(web): 复盘页支持导出 Markdown（问题+回答+评语+分支决策）"
```

---

## Task 6: 文档与收尾

**Files:**
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-09-29-interview-fixes.md`

- [x] **Step 1: 把本计划落到仓库**

把这份计划文档原样写到 `docs/superpowers/plans/2026-09-29-interview-fixes.md`
（和一期计划同一目录，方便以后翻）。

- [x] **Step 2: README 同步四处**

- 「快速开始」里补一句：日志现在能看到 `HTTP … → 200 | 123 ms | user=1` 和每题的出题/评分/分支行
- 「数据库」表后面补：话题池第一个是「项目经历」，出题走 `hint_practice`，没传简历也能问出场景题
- 「接口」补一句：复盘页右上角可导出 Markdown，纯前端 Blob，无新增接口
- 「已知取舍」加两行：

```
| 实践维度可能是空的 | 纯概念题没有项目背景就不打分（显示 `-`），不凑中间分 |
| 同一话题最多问 3 题 | `app.interview.max-follow-up`。问满强制换话题，不再「一直换个角度」 |
```

- [ ] **Step 3: 收尾验收（真跑一场）** ← 没跑：没有 key。见本文件末尾「执行结果」一节

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
export DEEPSEEK_API_KEY=你的key
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o spring-boot:run
```

依次确认：
1. 首页起一场（可以传简历，也可以不传）
2. 面试页：新题下面没有上一题的回答；「上一轮回顾」可折叠，刷新后还在
3. 连答 10 题：日志里话题每 3 题一换；题目不重复
4. 复盘页：实践维度可能显示 `-`；点导出 Markdown 能拿到完整文件
5. 控制台里能看到每个请求一行 `HTTP …`

- [x] **Step 4: 打包确认静态资源进去了**

```bash
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -o -q clean package
```

`prompts/hint_practice.md` 是新文件，必须确认它被打进 jar：

```bash
"$JAVA_HOME/bin/jar" tf target/ai-mianshi-0.0.1-SNAPSHOT.jar | grep -E "hint_practice|application.yml|interview.html"
```

预期：三行都有。打漏了的话启动后第一次出项目题就会 500（`PromptLoader` 找不到文件）。

- [x] **Step 5: Commit**

```bash
git add README.md docs/superpowers/plans/2026-09-29-interview-fixes.md
git commit -m "docs: 四问题修复的落地记录与 README 同步"
```

---

## 验证清单（全部做完后过一次）

| 检查 | 怎么验 | 期望 |
|---|---|---|
| 请求日志 | `grep "HTTP " run.log` | 每个 `/api` 请求一行，含状态码、耗时、user |
| 出题日志 | `grep "出题完成" run.log` | 有题号、话题、难度、耗时、题目全文 |
| 评分日志 | `grep "题评分" run.log` | 有总分、LLM 建议 vs 实际分支、耗时 |
| 分支日志 | `grep "分支决策" run.log` | 每轮一行 `evaluate → switch/continue/…` |
| 话题轮转 | `grep "分支决策" run.log` | `switch` 至少出现 3 次（10 题 / 每话题 3 题） |
| 去重 | 复盘页逐题详情 | 没有两道题在问同一件事 |
| 实践分 | 复盘页五维 / 导出文件 | 项目题有分，纯概念题显示 `-`，不再是清一色 6.0 |
| 上一轮回顾 | 面试页提交一题 + F5 | 折叠块在，位置在新题之上 |
| 导出 | 复盘页点按钮 | 下载 `.md`，题号/回答/评语/五维/分支/报告齐全，无乱码 |
| 不回归 | `node %TEMP%\PageCheck.js` | 六个页面全 PASS |

## 明确不做的事

- **不在 `InterviewRouting.decide()` 加分数阈值**：用户那场分数 5.5–7.5，`NextAction.infer()`
  兜底算出来还是 CONTINUE，加阈值改不动结果；真正的病根是同话题问了 10 轮，已由轮次上限解决
- **不做 LLM 抽取简历项目**：多一次调用多 10 秒等待，收益不抵成本。「项目经历」话题 + `hint_practice`
  已经能同时覆盖「有简历」和「没简历」两种情形
- **不动 `graph/` 包**：这次改动全在业务侧和提示词，图引擎不该知道「项目经历」是什么
- **不加导出接口**：`detail` 已经返回全部所需字段，加接口只会多一份要维护的序列化逻辑
---

## 执行结果

6 个 commit（分支 `feat/interview-phase1`，按计划只 commit 不 push）：

| commit | 内容 |
|---|---|
| `feat(log)` | 请求日志 Filter + 出题/评分/分支/图执行日志 |
| `feat(interview)` | 话题池加「项目经历」，practice 允许为 null |
| `fix(interview)` | 话题问满强制切换 + 出题去重扩到全部历史 |
| `fix(web)` | 上一轮回顾折叠块，字段由后端带回 |
| `feat(web)` | 复盘页导出 Markdown |
| `docs` | 本文件与 README 同步 |

### 与计划的偏差（都是执行中发现的真问题，不是绕路）

1. **`EvaluateNode` 的守卫差一轮。** 计划里的代码是 `followUpCount >= maxFollowUp`，
   但同一份计划给的验收预期是「话题每 3 题换一次」、`evaluate.md` 里给 LLM 的
   `topicRounds` 也是 `followUpCount + 1`（本题算在内）。按计划原文写会变成每话题 4 题。
   已改成 `followUpCount + 1 >= maxFollowUp`，Java 侧和提示词侧同一套口径。
   **是 `RoutingCheck` 的边界用例逼出来的**——这正是写它的理由。

2. **`TopicTracker.setCoveredTopics` 不做防御性拷贝。** 传 `List.of()` 进去，
   后面的 `markCovered` 会抛 `UnsupportedOperationException`。生产路径走 Jackson
   反序列化（可变 ArrayList）碰不到，但和 `setAllTopics` 行为不一致，顺手修了。

3. **`hint_practice.md` 搭了 Task 1 的 commit。** `QuestionNode.practiceHint()` 在同一批
   改动里，不带上它的话 Task 1 那个 commit 单独 checkout 会缺文件。
   反过来 `question_followup.md` 的 `{askedQuestions}` 段落落在了 Task 2 的 commit 里，
   那一瞬间 Java 侧还没 `put` 这个变量——中间态没被跑过，但确实是个瑕疵。

4. **`ApiCheck` 三条陈旧预期。** 话题池第一个变成「项目经历」后，
   `switch` 之后挑到的下一个话题从「并发编程」变成「JVM 内存模型」，
   三条断言挂掉。核对过是实现对了、断言旧了，改断言并把原因写在旁边。
   同时把 turn JSON 的契约断言从 16 个键扩到 20 个（新增的 5 个原文字段）。

### 验证结果（全部为 `%TEMP%` 下的临时程序，仓库里不写测试）

| 程序 | 结果 | 验的是什么 |
|---|---|---|
| `PromptCheck` | 22 / 22 | 9 个提示词都能加载、渲染后无残留占位符、JSON 花括号没被吃掉 |
| `GraphE2ECheck` | 57 / 57 | 假 LLM + 真 SQLite 跑穿整张图；含新增的出题去重断言 |
| `RoutingCheck` | 20 / 20 | 轮次守卫的边界、落库一致性、**10 题全程 CONTINUE 时每 3 题换话题** |
| `ApiCheck` | 44 / 44 | 真 HTTP + 真鉴权 + 假 LLM，四个接口的字段契约 |
| `PageCheck` | 128 / 128 | 六个页面的内联脚本、`showPrior` 三种取值、折叠块模板形态 |
| `ExportCheck` | 28 / 28 | 导出内容完整性、`\|` 与换行转义、文件名合法性、Blob 下载 |

### 还没做的验收

五步没跑，原因都写在对应步骤旁边，汇总一下：**本机没有可用的 api-key**
（`application.yml` 里是 `${DEEPSEEK_API_KEY:}`，环境变量未设），
所以「真跑一场」这类验收做不了；**也没有浏览器**，所以「人工看一眼」那几步改成了
程序化断言。剩下的 4 步（Task 2 Step 9、Task 3 Step 5、Task 4 Step 7、Task 5 Step 4、
Task 6 Step 3）需要你自己跑一遍，重点看：

1. 面试页新题下面不再挂着上一题的回答，「上一轮回顾 ▸」收起时只占一行，F5 后还在
2. 连答 10 题：日志里 `话题=` 每 3 题一换，题目不重复
3. 复盘页实践维度可能显示 `-`；点导出能拿到完整 `.md`
4. 控制台每个请求一行 `HTTP … → 200 | xx ms | user=1`

### 部署前必做

- `app.auth.secret` 的默认值 `change-me-before-deploy-please` 必须换掉
- 要接内网网关的话在本地改 `app.llm.base-url` / `model`，**别把 key 写回
  `application.yml`**（用 `export DEEPSEEK_API_KEY=...`）
