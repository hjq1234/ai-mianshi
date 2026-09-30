# 面试记录软删除 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给「面试记录」列表页加删除按钮，删掉的记录从所有界面消失但数据保留（不影响 `/stats` 的历史均分）。

**Architecture:** `t_interview_record` 加一个 `deleted INTEGER NOT NULL DEFAULT 0` 列；老库由 `SqliteInitializer`
在启动时检测缺列并 `ALTER` 补上。四条读这张表的 SQL 各加一个 `AND deleted = 0`——这是软删的**全部**成本，
因为所有子表查询的 `record_id` 都出自这四处，record 这层挡住就够了。删除走
`InterviewEngine.delete` → `InterviewDao.softDelete`，一条 UPDATE，不需要事务。

**Tech Stack:** Spring Boot 4.1.1 / Java 21 / SQLite + JdbcTemplate / Vue 3 CDN 版

**设计稿:** `docs/superpowers/specs/2026-09-30-interview-soft-delete-design.md`（已提交，`6906de0`）

**用户既有约束（必须遵守）:**
- **只 commit，不 push**
- **不许 `git add -A` / `git commit -a`**，每处都列显式路径
- **`src/main/resources/application.yml` 绝对不能提交**（里面有本地 `base-url` / `model` / `max-questions` 改动）
- 全程中文
- **不写验证程序**。这个功能四处过滤点**全都能从界面上看出来**（记录还在 = 漏了；历史均分变了 = 漏了），
  不属于「写错了不报错」那一类，所以只做编译 + 人工验收，不要新建 check 程序、不要跑 A/B

---

## Context

「面试记录」页现在只能看不能删。库里 6 条记录，一半是调模型/调提示词时跑出来的测试场次。

三个已定下来的决定（详见设计稿）：

| 决定 | 选的 |
|---|---|
| 谁能删 | 进行中的也能删（半途放弃的正是最该清的那类） |
| 删的语义 | **软删**（标记隐藏，数据留着）—— 真删会把 `/stats` 的历史均分一起改掉 |
| 老库怎么办 | `SqliteInitializer` 启动时 `PRAGMA table_info` 探测 + `ALTER` |

**软删的唯一风险是「漏一处过滤」**：漏了不报错，只是那一处还看得见已删的数据。
所以「要过滤的地方」是穷举过的，全库 `grep t_interview_record src/main` 只有 4 处读：

| `InterviewDao` | 行（WHERE 所在行，已逐行核对） | 现在 | 加 |
|---|---|---|---|
| `findRecord` | 114 | `WHERE r.id = ?` | `AND r.deleted = 0` |
| `listByUser` | 136 | `WHERE r.user_id = ?` | `AND r.deleted = 0` |
| `countByUser` | 144 | `WHERE user_id = ?` | `AND deleted = 0` |
| `listFinishedIdsWithAnswers` | 217 | `WHERE r.user_id = ? AND r.status = 'finished'` | `AND r.deleted = 0` |

`findRecord` 是咽喉：`InterviewEngine.loadOwned`（私有）唯一从它取记录，而**所有**写路径
（`answer` / `resume` / `state` / `finish` / `detail`）都先过 `loadOwned`。过滤了它，
已删记录在所有接口上自动变成 404，不用逐个方法再拦一次。

不用改的地方：`RecordRow`（`RECORD_MAPPER` 按列名取值，`r.*` 多出来的 `deleted` 会被忽略）、
`t_interview_dialogue` / `t_graph_trace`（子表查询收的都是 record id）。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/resources/schema.sql` | 改 | 新库的 `t_interview_record` 建表语句加 `deleted` 列 |
| `src/main/java/.../common/config/SqliteInitializer.java` | 改 | 老库探测缺列 + `ALTER` 补上（幂等） |
| `src/main/java/.../biz/interview/InterviewDao.java` | 改 | 四处读加过滤 + 新增 `softDelete` + 类注释写明过滤规则 |
| `src/main/java/.../biz/interview/InterviewEngine.java` | 改 | 新增 `delete(userId, recordId)` |
| `src/main/java/.../controller/InterviewController.java` | 改 | 新增 `DELETE /api/interview/{id}` |
| `src/main/resources/static/history.html` | 改 | 删除按钮 + `remove()` 方法 |
| `README.md` | 改 | 接口清单 + 取舍表同步 |

---

## Task 1: 数据层（加列 + 迁移 + 四处过滤 + softDelete）

**Files:**
- Modify: `src/main/resources/schema.sql:21-39`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/common/config/SqliteInitializer.java`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewDao.java`

- [ ] **Step 1: schema.sql 加列**

`t_interview_record` 的建表语句（`schema.sql:21-39`），在 `report` 和 `state_json` 之间插入 `deleted`。
改完这一段是：

```sql
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

    -- ★ 软删标记。1 = 已删。查询一律要带 AND deleted = 0（见 InterviewDao 类注释里那 4 处）
    -- ★ 这一行只对新库生效——CREATE TABLE IF NOT EXISTS 对已存在的表是整条跳过的，
    --   老库靠 SqliteInitializer 的 ALTER 补（见下一个 Step）
    deleted     INTEGER NOT NULL DEFAULT 0,

    -- ★ 图引擎的两个关键字段
    state_json  TEXT,                        -- InterviewState 序列化快照
    cursor      TEXT,                        -- 引擎游标：下次从哪个节点继续

    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);
```

- [ ] **Step 2: SqliteInitializer 加迁移**

整个 `run()` 换成下面这样（类注释也补一句，因为「启动时执行一次的事」这个定位现在装了两件事）：

```java
/**
 * 启动时执行一次的事情都放这儿。
 *
 * 现在有两件：
 *   1. journal_mode 是写进数据库文件头的，设一次永久生效（busy_timeout 是每连接生效的，
 *      在 Hikari 的 connection-init-sql 里配）。不开 WAL 的话，写操作会锁整库，读也被挡住。
 *   2. 给老库补 t_interview_record.deleted 列。schema.sql 里那行只对新库生效，
 *      因为 CREATE TABLE IF NOT EXISTS 对已经存在的表是整条跳过的。
 *
 * 执行顺序：spring.sql.init 跑 schema.sql 是在 DataSource 初始化阶段，
 * 早于 ApplicationRunner，所以这里跑的时候表一定已经存在了。
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
        ensureWalMode();
        ensureSoftDeleteColumn();
    }

    private void ensureWalMode() {
        String mode = jdbcTemplate.queryForObject("PRAGMA journal_mode=WAL", String.class);
        log.info("SQLite journal_mode = {}", mode);
        if (!"wal".equalsIgnoreCase(mode)) {
            log.warn("SQLite 未进入 WAL 模式，并发写入可能报 database is locked");
        }
    }

    /**
     * 老库补 deleted 列。
     *
     * 为什么自动做而不是让人手工跑一次 ALTER：忘了的表现是每个面试接口都 500 报
     * 「no such column: deleted」，而且没有任何地方会提醒。6 行幂等代码换掉这一整类问题。
     *
     * 用 PRAGMA table_info 判断，而不是 try { ALTER } catch {}：后者的控制流是异常，
     * 会把「真的执行失败」也一起吞掉。
     */
    private void ensureSoftDeleteColumn() {
        List<Map<String, Object>> columns =
                jdbcTemplate.queryForList("PRAGMA table_info(t_interview_record)");
        boolean exists = columns.stream()
                .anyMatch(c -> "deleted".equalsIgnoreCase(String.valueOf(c.get("name"))));
        if (exists) {
            return;
        }
        jdbcTemplate.execute(
                "ALTER TABLE t_interview_record ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
        log.info("t_interview_record 补上 deleted 列（软删标记），已有记录默认 0=未删");
    }
}
```

import 补两个（其余已有）：

```java
import java.util.List;
import java.util.Map;
```

- [ ] **Step 3: InterviewDao 类注释写明过滤规则**

`InterviewDao` 现在只有 `@Repository`，没有类注释。加上（这是「漏一处就是删了还在」的唯一书面提醒，
将来加查询的人只会看到这里）：

```java
/**
 * 面试记录 / 逐题对话 / 图轨迹的读写。
 *
 * ★ 软删：t_interview_record.deleted = 1 表示已删。读这张表的查询**必须**带 AND deleted = 0，
 *   漏一处不报错，只是那一处还看得见已删的记录。目前需要过滤的恰好四处：
 *     findRecord / listByUser / countByUser / listFinishedIdsWithAnswers
 *   加新的查询时，回来把这一行也补上。
 *
 * ★ 子表（t_interview_dialogue / t_graph_trace）不用过滤：它们的查询收的都是 record id，
 *   而那些 id 全出自上面那四处，record 这层挡住了子表就查不出来。
 *
 * ★ findRecord 是咽喉：InterviewEngine.loadOwned 只从它取记录，而所有写路径都先过 loadOwned。
 *   所以过滤了它，已删记录在所有接口上自动 404。
 */
@Repository
public class InterviewDao {
```

- [ ] **Step 4: 四处读加过滤**

这四处各改一行。`findRecord`（`InterviewDao:109-116`）：

```java
    /** 连表带出简历摘要，供 StartNode 塞进 state */
    public Optional<RecordRow> findRecord(Long id) {
        return jdbc.query("""
                SELECT r.*, substr(coalesce(s.content, ''), 1, ?) AS resume_summary
                FROM t_interview_record r
                LEFT JOIN t_resume s ON s.id = r.resume_id
                WHERE r.id = ? AND r.deleted = 0
                """, RECORD_MAPPER, RESUME_SUMMARY_CHARS, id).stream().findFirst();
    }
```

`listByUser`（`InterviewDao:132-140`）：

```java
    public List<RecordRow> listByUser(Long userId, int limit, int offset) {
        return jdbc.query("""
                SELECT r.*, '' AS resume_summary
                FROM t_interview_record r
                WHERE r.user_id = ? AND r.deleted = 0
                ORDER BY r.created_at DESC
                LIMIT ? OFFSET ?
                """, RECORD_MAPPER, userId, limit, offset);
    }
```

`countByUser`（`InterviewDao:142-146`）：

```java
    public int countByUser(Long userId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_interview_record WHERE user_id = ? AND deleted = 0",
                Integer.class, userId);
        return n == null ? 0 : n;
    }
```

`listFinishedIdsWithAnswers`（`InterviewDao:214-221`）：

```java
    public List<Long> listFinishedIdsWithAnswers(Long userId) {
        return jdbc.queryForList("""
                SELECT r.id FROM t_interview_record r
                WHERE r.user_id = ? AND r.status = 'finished' AND r.deleted = 0
                  AND EXISTS (SELECT 1 FROM t_interview_dialogue d WHERE d.record_id = r.id)
                ORDER BY r.created_at DESC
                """, Long.class, userId);
    }
```

- [ ] **Step 5: softDelete**

放在写方法那一组里，`finishRecord` 之后、`listByUser` 之前（`InterviewDao:130` 和 `132` 之间）：

```java
    /**
     * 软删：数据留着，只是所有查询都看不见了（见类注释里那四处过滤）。
     *
     * 走软删而不是 DELETE FROM，是因为 /stats 的历史均分是按场次聚合的——
     * 真删会让「删掉一场答砸的面试」顺手把「历史水平（N 场）」也改了。
     *
     * 单条 UPDATE，不需要事务（ResumeDao 那个 @Transactional 是因为它一次改两行）。
     */
    public void softDelete(Long id) {
        jdbc.update("UPDATE t_interview_record SET deleted = 1, updated_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }
```

- [ ] **Step 6: 编译**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
cd /d/ideaProjects/ai-mianshi
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q clean compile
```

预期：无输出（`-q` 下只有错误才打印），`target/classes` 重新生成。

- [ ] **Step 7: 确认老库迁移真的跑了**

起一次服务看日志（**不要**用 curl，curl 在这个环境被禁）：

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
cd /d/ideaProjects/ai-mianshi
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o spring-boot:run \
  > /c/Users/huangjinqing001/AppData/Local/Temp/run.log 2>&1 &
```

起来之后：

```bash
grep -E "journal_mode|deleted 列" /c/Users/huangjinqing001/AppData/Local/Temp/run.log
```

预期**两条都在**：

```
SQLite journal_mode = wal
t_interview_record 补上 deleted 列（软删标记），已有记录默认 0=未删
```

再起第二次，`grep "deleted 列"` 应当**没有输出** —— 迁移是幂等的，第二次探测到列已存在就跳过。
这一条是「幂等」的唯一证据，一定要跑第二次。

库里的老记录没丢、而且 `deleted` 都是 0，可以用 Python 看一眼（本机没有 `sqlite3` 命令行）：

```bash
"/d/Program Files (x86)/python/Python312/python" -c "
import sqlite3
c = sqlite3.connect(r'D:/ideaProjects/ai-mianshi/data/interview.db')
for row in c.execute('SELECT id, status, deleted FROM t_interview_record ORDER BY id'):
    print(row)
"
```

预期：老记录条数不变，每条第三列都是 `0`。（中文在 GBK 控制台可能乱码，数字是准的。）
这条命令**不动结构、不写数据**，只是查，可以放心跑。

- [ ] **Step 8: Commit**

```bash
cd /d/ideaProjects/ai-mianshi
git add src/main/resources/schema.sql \
        src/main/java/com/ke/nhservice/aimianshi/common/config/SqliteInitializer.java \
        src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewDao.java
git diff --cached --name-only   # ★ 确认这三行，且没有 application.yml
git commit -m "feat(interview): 面试记录软删——加 deleted 列、老库自动迁移、四处查询加过滤"
```

---

## Task 2: 业务层 + 接口

**Files:**
- Modify: `src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java:163-166`
- Modify: `src/main/java/com/ke/nhservice/aimianshi/controller/InterviewController.java`

- [ ] **Step 1: InterviewEngine.delete**

放在 `requireRecord` 之后（`InterviewEngine:166` 之后，`dialogues` 之前）：

```java
    /**
     * 软删一场面试记录。
     *
     * 复用 requireRecord 而不是自己写归属校验：它里面就是 loadOwned，
     * 「不存在」和「不是你的」都统一抛 notFound（面试记录不存在），
     * 所以已删的记录在这里自动也是 404——对同一条再删一次会 404，是正确行为不是 bug。
     *
     * 不判断状态：进行中的也能删（半途放弃的正是最该清理的那类）。
     */
    public void delete(Long userId, Long recordId) {
        requireRecord(userId, recordId);
        dao.softDelete(recordId);
        log.info("删除面试记录 | 面试={} 用户={}", recordId, userId);
    }
```

> `requireRecord` 顺带会把 `state_json` 反序列化出来（删一条记录用不到），但为省这点开销另写一份
> 归属判断，正是 `requireRecord` 那句注释（「归属校验统一走 loadOwned，不在这里重复写一遍」）在防的事。

- [ ] **Step 2: InterviewController 加 DELETE**

import 补一个（其余已有）：

```java
import org.springframework.web.bind.annotation.DeleteMapping;
```

接口方法放在 `resume`（`:66-69`）之后、`state`（`:71`）之前——和其余写接口挨着：

```java
    /**
     * 删除一条面试记录（软删）。
     * 和 /api/resume/{id} 那条一一对应：都是走 service 的归属校验 + 一个 DAO 写操作。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        engine.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }
```

`ApiResponse.ok()`（无参那个）存在，见 `ApiResponse:14`，返回 `{code:0, message:"ok", data:null}`。

- [ ] **Step 3: 编译**

同 Task 1 Step 6。

- [ ] **Step 4: 冒烟一次（就一条，别多做）**

起服务（同 Task 1 Step 7 的启动命令），用浏览器或本机已有的 `%TEMP%` 下的单文件 Java HttpClient
程序带 `Authorization: Bearer <token>` 删一条**测试用的**记录（**不要用 curl**），确认返回
`{"code":0,"message":"ok","data":null}`。

只要这一条能确认「接口通了、签名和序列化都没问题」就够了。归属校验、重复删除 404、
`/stats` 不受影响这三条**留到 Task 4 的收口清单一次验**（那里本来就要跑一遍），不要在这里再验一遍。

- [ ] **Step 5: Commit**

```bash
cd /d/ideaProjects/ai-mianshi
git add src/main/java/com/ke/nhservice/aimianshi/biz/interview/InterviewEngine.java \
        src/main/java/com/ke/nhservice/aimianshi/controller/InterviewController.java
git diff --cached --name-only
git commit -m "feat(interview): 删除面试记录的接口与服务方法"
```

---

## Task 3: 前端删除按钮

**Files:**
- Modify: `src/main/resources/static/history.html`

`api(path, {method})` 支持 `DELETE`（`app.js:39` 读 `options.method`），
`toast(message, isError)` 有第二个参数（`app.js:118`），`button.danger` 样式已有（`app.css:125`）——
都不用改 `app.js` / `app.css`。

- [ ] **Step 1: 加按钮**

`history.html:47-51` 那个 `<td class="right">` 换成（**两个状态都显示删除**，和「进行中的也能删」对应）：

```html
            <td class="right">
              <button v-if="r.status === 'finished'" class="small"
                      @click="go('report.html', r.id)">查看复盘</button>
              <button v-else class="small" @click="go('interview.html', r.id)">继续面试</button>
              <button class="small danger" @click="remove(r.id)">删除</button>
            </td>
```

- [ ] **Step 2: 加 remove()**

`methods` 里，`go(...)` 之后加（注意 `go` 那行结尾要补逗号）：

```js
    methods: {
      fmtTime,
      fmtScore,
      scoreClass,
      go(page, id) { location.href = page + '?id=' + id; },

      async remove(id) {
        if (!confirm('确定删除这条面试记录？')) return;
        try {
          await api('/api/interview/' + id, { method: 'DELETE' });
          // 从本地列表摘掉那一行，不整页刷新：重载会把滚动位置也弄丢
          this.list = this.list.filter(r => r.id !== id);
          toast('已删除');
        } catch (e) {
          toast(e.message, true);
        }
      }
    }
```

`this.list` 是 `/api/interview/list` 直接返回的数组（`mounted` 里 `this.list = await api(...)`，
模板用 `list.length` / `v-for`），所以 `filter` 直接可用。

- [ ] **Step 3: 人工验收**

起服务 → 打开 `history.html`：

1. 每行都有「删除」，进行中那行是「继续面试 + 删除」，已结束那行是「查看复盘 + 删除」
2. 点删除 → 弹确认框 → 确定 → 那一行**立刻消失**（页面没跳转、滚动位置没变）
3. 点删除 → 弹确认框 → **取消** → 什么都没发生（不发请求）
4. 刷新页面 → 删掉的还在不在（不在 = 对的）
5. 点「删除」时按钮没变成禁用、也不该连点两次——第二次会收到 404 并 toast 报错，
   这是预期的（可以接受，不必额外处理）

- [ ] **Step 4: Commit**

```bash
cd /d/ideaProjects/ai-mianshi
git add src/main/resources/static/history.html
git diff --cached --name-only
git commit -m "feat(web): 面试记录列表页加删除按钮"
```

---

## Task 4: README 同步 + 收口验收

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 接口清单加一行**

`README.md` 的「接口」代码块里，`GET /api/interview/list` 那一行的前面插入（挨着其余按 id 的写接口）：

```
DELETE /api/interview/{id}          软删一条面试记录
```

- [ ] **Step 2: 数据库那节补一句**

`README.md`「数据库」节的表格下面、「数据文件默认在 `./data/interview.db`」那句之后，加一段：

```markdown
`t_interview_record` 上有个 `deleted` 列（软删标记）。删除面试记录是**标记隐藏，不是真删**：
数据留着，只是所有查询都带 `AND deleted = 0`。这么做是因为 `/stats` 的历史均分按场次聚合，
真删会让「删掉一场答砸的面试」顺手把「历史水平（N 场）」也改了。

需要过滤的读查询恰好四处（`findRecord` / `listByUser` / `countByUser` / `listFinishedIdsWithAnswers`），
都在 `InterviewDao`，类注释里列着——**加新查询时要回去补**，漏一处不报错，只是那一处还看得见已删的记录。
子表不用过滤：它们的查询收的都是 record id，而那些 id 全出自这四处之一。

老库（已经建过表的）靠 `SqliteInitializer` 在启动时探测缺列 + `ALTER` 自动补上，
因为 `CREATE TABLE IF NOT EXISTS` 对已存在的表是整条跳过的。要真删就手工 `DELETE FROM` 三张表。
```

- [ ] **Step 3: 取舍表加一行**

`README.md`「几个已知的取舍」表格里加（放在「知识库只留接口不实现」那行附近）：

```
| 删除面试记录是软删（标记隐藏） | 数据留着，`t_interview_record.deleted = 1`。真删要手工 `DELETE FROM` 三张表——单用户自用，不做回收站界面 |
```

- [ ] **Step 4: 收口验收（一次跑完，别拆成多轮）**

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
cd /d/ideaProjects/ai-mianshi
./mvnw -s /d/apache-jmeter-5.4.3/settings.xml -Dmaven.repo.local=D:/repository -o -q clean package -DskipTests
```

确认静态页被打进 jar：

```bash
"$JAVA_HOME/bin/jar" tf target/ai-mianshi-0.0.1-SNAPSHOT.jar | grep -E "history.html|SqliteInitializer"
```

预期两行都有（改的是已有文件，理论上不会漏，但 `clean` 之后确认一次很便宜）。

然后起服务走一遍完整的人工验收清单（下表），一次跑完：

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 启动日志 | 有 `journal_mode = wal`；**第一次**有「补上 deleted 列」，重启后没有 |
| 2 | 打开「面试记录」 | 6 条老记录都在（迁移没弄丢数据），每条都有「删除」 |
| 3 | 删一条**进行中**的 | 行立刻消失 |
| 4 | 同一个 `report.html?id=<刚删的>` 直接敲地址 | 提示「面试记录不存在」，不是幽灵页 |
| 5 | 删一条**已结束**的，看 `/stats` | 「历史均分（N 场）」的 N **不变** |
| 6 | 刷新列表页 | 删掉的两条都不在了 |
| 7 | 重启服务，再看列表 | 还是不在了（说明是落库的，不是只改内存） |
| 8 | （可选）跑设计稿里那条 Python 查询 | 两条记录还在库里，`deleted = 1` |

第 4 条验的是 `findRecord` 的过滤（咽喉那个）；第 5 条验的是 `listFinishedIdsWithAnswers`。
这两条是最容易漏的，别省。

- [ ] **Step 5: Commit**

```bash
cd /d/ideaProjects/ai-mianshi
git add README.md docs/superpowers/plans/2026-09-30-interview-soft-delete.md
git diff --cached --name-only   # ★ 确认没有 application.yml
git commit -m "docs: 面试记录软删的落地记录与 README 同步"
```

---

## 验证清单

| 检查 | 怎么验 | 期望 |
|---|---|---|
| 新库有列 | 删掉 `data/interview.db` 重启 | 不报 `no such column: deleted`，建表语句里带 `deleted` |
| 老库能升 | 用现成的 6 条记录的库重启 | 日志有「补上 deleted 列」，记录数不变 |
| 迁移幂等 | 再重启一次 | 日志**没有**「补上 deleted 列」 |
| 列表过滤 | 列表页 | 删掉的不显示 |
| 详情过滤 | 直接敲 `report.html?id=<已删>` | 「面试记录不存在」 |
| 统计不过滤掉分母 | `/stats` | 删记录前后 N 不变 |
| 重复删除 | 同一条删两次 | 第二次 404，不是 500 |
| 删除不误伤 | 删一条后看其余记录 | 其余记录照常能点开复盘 / 继续 |

## 明确不做的事

- **不做恢复 / 回收站界面**：要找回就 `UPDATE t_interview_record SET deleted = 0 WHERE id = ?`，单用户自用够了
- **不做批量删除**：列表本来就不长，多选框 + 全选是一整套 UI
- **不真删子表**：软删的意义就是数据留着；而且子表靠 record 那层挡住就够
- **不动 `/stats` 的语义**：软删之后它天然不受影响——这正是选软删的原因
- **不写验证程序**：四处过滤点全都从界面上看得出来（见本计划开头的约束说明），
  按用户要求只做编译 + 人工验收