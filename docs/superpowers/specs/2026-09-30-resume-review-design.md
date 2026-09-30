# 简历修改建议设计

## 要解决的事

现在系统只能**读**简历（PDF 抽文本 → 出题时当背景资料），不能帮他**改**。
而简历是面试的第一道关：出题时能看到「项目经历写得虚」，但没有任何地方告诉他怎么改。

加一个功能：对着目标岗位，给出逐条修改建议 + 一份改写后的参考稿，存下来可回看、可导出。

## 已定的决定

| 问题 | 定的 |
|---|---|
| 交给用户什么 | **修改建议**（逐条）+ **一份改写后的参考稿**（可导出） |
| 参考材料 | 简历原文 + 目标岗位（**可加多条**：岗位名 + JD 可空）+ 面试记录（**多选**） |
| 给谁用 | **自己用**，不做权限 / 多用户那套 |
| 存不存库 | **存**，按简历留历史（能回看、能对比改前改后） |
| 产物格式 | **纯 Markdown，一次 LLM 调用**（不是 JSON） |
| 编造怎么处理 | 参考稿里凡是模型自己补的数字/项目细节，**一律写成【方括号】占位**让他自己填 |
| 多岗位的输出形态 | 一份主稿 + 每个岗位一节差异建议 |

## 三个已验证的技术事实（决定了方案）

### 1. 为什么产物是纯 Markdown，不是 JSON

`EvalResultParser.extractJson` 取的是**第一个 `{` 到最后一个 `}`**。响应被截掉尾巴时找不到
最后一个 `}`，**整次生成全废**。而「一整份改写稿」是很长的输出，恰恰是最容易撞长度上限的那种。

而且 `LlmProperties` 里**没有 `maxTokens` 字段**，`OpenAiCompatibleClient:48-52` 发的 body 只有
`model` / `messages` / `temperature` / `stream` —— **`max_tokens` 根本没发**，输出上限是网关的默认值，
是一个未知数。（200k 那个上下文窗口管的是**输入**能塞多少，输出多长是另一个参数。）

所以：产物是纯 Markdown。截断的表现从「整次全废」降级成「参考稿写到一半，但前面的建议都在」。
批注也就不用往 JSON 字符串里塞（那一大堆 `\n` 转义是模型出错的常事）。

### 2. 为什么派生在后端（抽建议 / 去批注）

产物只有一份（Markdown），页面上的「修改建议面板」和「干净参考稿」都是**从它派生出来的视图**。
派生逻辑必须只有一处实现 —— 否则「怎么抽建议」会在 Java 和 JS 里各写一份
（这个项目一直在防这件事，`InterviewStats` 的类注释就是「这条规则只该有一处实现」）。

顺带解决了一个渲染问题：`renderMarkdown` 是刻意做小的（注释写着「报告是 LLM 出的，只用到这些」），
**不支持引用块**，而且它第一步就把 `>` 转义成 `&gt;`。派生之后页面拿到的 `document` 里
**已经没有 `>` 行了**，直接用现有的 `renderMarkdown` 就行，不用动 `app.js`。

批注在原位的形态留给**导出的 .md** —— 那才是他拿去对照着改的东西。

### 3. 为什么多岗位是「一份主稿 + 每岗位一节」

给 2 个岗位，一份参考稿不够用（两边要改的地方不一样）；但每个岗位各出一份完整稿，
输出长度 ×N，2 个岗位就很容易截断。

「一份主稿 + 每个岗位补丁」既把长度压住（N 个岗位只多出 N 个小节），又正好是
「投不同岗位要微调」的真实形态：**一份主简历 + 每个岗位的补丁**，维护起来也是这个样子。

## 提示词契约（`prompts/resume_review.md`）

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
   让候选人自己填真实的。**绝对不要编造看起来合理的数字**——
   这份简历是要拿去投的，编出来的东西面试一问就穿帮。

4. 主稿之后加一节 `## 这份简历最该先改的三件事`，不超过三条，每条一行。

5. 然后**每个目标岗位各加一节** `## 投「<岗位名>」要额外改什么`，每节 2-4 条，
   同样是 `> 建议：` 开头的行。只写这个岗位特有的调整，不要重复主稿里说过的。

6. 不要开场白（「以下是改写后的简历」这类），直接从主稿正文开始。

各节顺序：主稿 → `## 这份简历最该先改的三件事` → 各岗位的额外调整节。
```

**`{targets}` 的拼法**（后端拼，模型直接读到岗位名）：

```
① Java 后端
   JD：负责交易链路高并发…
② Go 后端
   JD：（未提供，按该岗位通用标准）
```

**`{interviewMaterial}` 的拼法**（只给**已勾选**那几场）：

```
第 1 场 · Java 后端 / Java · 中等 · 总分 7.2 · 已答 10 题
  第1题 JVM 内存模型 6.5
  第2题 并发编程 5.0
  …
  五维均分：准确性 7.1 / 深度 6.0 / 表达 7.5 / 实践 2.5 / 问题解决 6.8
```

没勾 → `（未提供）`；勾了但一场都没完成 → `（还没有已完成的面试）`（提示词里让它别硬编）。

## 数据层

新表（`schema.sql`）。**不需要迁移**：这张表本来就不存在，`CREATE TABLE IF NOT EXISTS` 直接建
（和上次给 `t_interview_record` 加 `deleted` 列是两回事）。

```sql
CREATE TABLE IF NOT EXISTS t_resume_review (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER NOT NULL,
    resume_id        INTEGER NOT NULL,   -- 改的是哪份简历
    markdown         TEXT    NOT NULL,   -- LLM 全文，批注在原位
    suggestion_count INTEGER NOT NULL DEFAULT 0,  -- 写入时算一次，列表页不用拖全文
    targets_json     TEXT,               -- [{"title":"Java 后端","jd":"…"}]，至少一条
    interview_ids    TEXT,               -- 参考了哪几场（逗号分隔），没用为 NULL
    model            TEXT,               -- 换模型后能看出这条是哪个生成的
    truncated        INTEGER NOT NULL DEFAULT 0,  -- 见「错误处理」
    created_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_resume ON t_resume_review(resume_id, created_at DESC);
```

**`suggestion_count` 存下来而不是每次算**：列表页要显示「建议 N 条」，
不该为了一个数字把每行的全文都拖出来。它由同一个抽取函数在写入时算一次，不会漂移。

**删除用物理删**（不是软删）：review 是派生出来的产物，没有任何聚合依赖它；
而且和简历本身（`ResumeDao.delete` 就是物理删）保持一致。

**连带删除**（容易漏）：`t_resume` 是物理删、**没有外键级联**，所以删简历会留下孤儿 review。
改法：`ResumeService.delete` 加 `@Transactional`，先删 review 再删简历。
事务边界放 service 而不是 DAO —— `ResumeDao.setDefault` 那个 `@Transactional` 是「一张表的两条语句」，
这里是两张表。

## 后端

| 文件 | 动作 | 职责 |
|---|---|---|
| `biz/resume/ResumeReview.java` | 新建 | record，一行 review |
| `biz/resume/ResumeReviewDao.java` | 新建 | insert / listByResume / findById / delete / deleteByResume |
| `biz/resume/ResumeReviewParser.java` | 新建 | **纯函数**：Markdown → suggestions / document / count。唯一实现处 |
| `biz/resume/ResumeReviewService.java` | 新建 | 拼素材 → 调 LLM → 落库 |
| `biz/resume/ResumeService.java` | 改 | `delete` 加事务 + 连带删 review |
| `controller/ResumeReviewController.java` | 新建 | 四个接口 |
| `biz/interview/InterviewEngine.java` | 改 | 加只读方法 `dialoguesOfRecentFinished` / 指定场次的方法 |
| `wrapper/llm/LlmProperties.java` | 改 | 加 `maxTokens`，**默认 0 = 不发这个字段** |
| `wrapper/llm/OpenAiCompatibleClient.java` | 改 | `maxTokens > 0` 时才放进 payload |
| `wrapper/llm/ChatCompletionResponse.java` | 改 | `Choice` 加 `finishReason` |
| `common/dto/` | 新建 | `ResumeReviewRequest` / `ResumeReviewVO` / `ResumeReviewItemVO` |

### 面试记录素材从哪来

`InterviewEngine` 加只读方法，内部**复用现成的 DAO 方法**（`listFinishedIdsWithAnswers` +
`listDialoguesByRecords`），不写新 SQL。格式化（拼成提示词素材）留在 resume 侧 ——
interview 只提供数据，不认识「简历改稿」这回事。

### `max_tokens` 为什么默认不发

不知道这个网关的默认上限是多少。硬设 8192 而网关默认本来就是 16384 的话，是把它改坏了。
所以做成「默认为空 → 不发这个字段 → 行为一点不变」，需要时在 yml 加一行
`app.llm.max-tokens: 8192`。

（顺带：`max_tokens` 没发对**面试那条链**也有隐患 —— 评分 JSON 被截断的话
`extractJson` 找不到最后的 `}`，整题白跑。加了这个字段至少有个旋钮。）

## 接口

新前缀，**不挂在 `/api/resume/{id}` 下面** —— 避免和 `DELETE /api/resume/{id}` 抢路径。

```
POST   /api/resume-review              {resumeId, targets:[{title,jd}], interviewIds:[…]} → 详情
GET    /api/resume-review?resumeId=X   历史列表（不含 markdown）
GET    /api/resume-review/{id}         详情（含全部派生字段）
DELETE /api/resume-review/{id}         删掉一条
```

**详情返回三项派生字段**（都在后端算好）：

| 字段 | 内容 |
|---|---|
| `markdown` | 原始全文，批注在原位。**导出用这个** |
| `document` | 去掉批注行之后的干净正文。页面渲染参考稿用这个 |
| `suggestions` | `[{section, text}]`。`section` = 它属于哪个小节（主稿 / 投「Java 后端」的额外调整），前端按它分组 |

**抽建议的规则**（`ResumeReviewParser`，三步）：

1. 逐行取 `>` 开头的行 → `suggestions`；扫的时候记住「最近一个 `##` 标题」当 `section`。
   第一个 `##` 之前的批注（改开头个人信息 / 求职意向那些）归到「简历开头」
2. 去掉这些行 → 候选正文
3. **把因此空掉的小节标题也去掉** —— 岗位节按提示词约定只含 `> 建议：` 行，不去标题的话
   参考稿里会出现「## 投「Go 后端」要额外改什么」下面空无一物。最后压缩连续空行

第 3 步是那种「不写出来就一定会踩」的细节。

`section` 取「最近一个 `##` 标题」，而不是笼统地标成「主稿」：主稿里的标题就是简历自己的章节
（项目经历 / 工作经历 …），按它分组，面板上就是「项目经历那 3 条建议」，比一个「主稿 12 条」
的大堆有用得多。这也正是提示词第 1 条要求模型给每个大块起 `##` 标题的原因 ——
不给标题的话，主稿部分的所有批注都会掉进「简历开头」那一组。

## 前端

**入口**：`resume.html` 每行加一个「改简历」按钮 → 跳 `resume-review.html?resumeId=X`。
`topbarHtml` 那 3 个导航条目**不动** —— 这页需要 `resumeId`，塞不进导航栏。

**`resume-review.html`**（新建）：

- 顶部：简历文件名 + 「生成建议」按钮，点开表单
  - **目标岗位**：可加多条，每条 = 岗位名（文本，必填）+ JD（textarea，可空）+ ✕ 删除；
    底部 `[+ 再加一条]`。至少留一条
  - **面试记录**：多选的勾选列表，复用 `GET /api/interview/list`。
    每行显示 岗位 / 方向 / 已答 N 题 / 总分 / 时间，让他自己判断哪场值得参考
    （进行中的也能勾，但「已答 N 题」会显示出来，参考价值低是看得见的）
- 生成中：转圈 + 「生成中…一般 15-40 秒」（`read-timeout` 是 120 秒）
- 历史列表：时间 / 建议 N 条 / 几个目标岗位 / 模型 / 查看 / 删除
- 详情区：
  - **修改建议面板**：按 `section` 分组（简历各章节 N 条 / 投「X」的额外调整 N 条），逐条卡片
  - **参考稿**：`document` 走现有的 `renderMarkdown`
  - 右上角「导出 Markdown」：**导出 `markdown`**（带原位批注），
    复用复盘页那套 `downloadTextFile` + `safeFileName`

## 错误处理

- **LLM 失败** → 这次不落库，toast（和现有调用一致）。不存失败记录
- **被长度上限截断** → 现在**看不到这个信息**（`ChatCompletionResponse` 只声明了
  `choices[].message`），所以加 `finishReason`：`== "length"` 时存 `truncated = 1`，
  页面顶部给一条黄条「这次输出被长度上限截断了，去 `app.llm.max-tokens` 调大」。
  不做这个的话现象是「参考稿戛然而止」，会以为是模型笨而不是撞了上限。
  按第 1 节的降级设计，截断时前面的建议都还在库里

  ⚠️ **必须写 `@JsonProperty("finish_reason")`**：全项目**一个 `@JsonProperty` 都没有**，
  也没有配 `spring.jackson.property-naming-strategy`（grep 过），所以 Jackson 走默认的
  camelCase —— 声明成 `String finishReason()` 是**绑不上** `finish_reason` 的，
  结果 `truncated` **永远是 0**，黄条永远不出现，而这**不报任何错**。
  （这个类现有的 `role` / `content` / `message` 都是单个词，看不出这个问题——
  `finish_reason` 是这里第一个多词字段。）
- **一场面试都没完成** → 勾了也照跑，素材写「（还没有已完成的面试）」
- **岗位名全空** → 前端拦（至少一条且 title 非空），后端也校验一次

## 明确不做的

- **不做「每个岗位一份完整稿」**：输出 ×N 太容易截断。主稿 + 岗位补丁已经够用
- **不做软删 / 回收站**：review 是派生产物，物理删（和简历本身一致）
- **不做版本对比界面**：「改前 vs 改后」靠历史列表自己翻，不专门做 diff
- **不动 `renderMarkdown`**：派生出的 `document` 不需要引用块支持
- **不引 `OfflinePunctuation` 之类的新依赖**：这条路和语音无关

## 怎么验

1. 简历页点「改简历」→ 生成 → 建议面板 + 参考稿都出来
2. **参考稿里出现【】占位符** ← 「它没瞎编数字」的证据
3. 建议面板的条数和历史列表里的「建议 N 条」对得上
4. 加**两个**目标岗位再生成 → 参考稿主稿之后出现两节 `## 投「X」要额外改什么`，
   且建议面板按小节分了组
5. 导出 .md，用编辑器打开 → 批注在**原位**（紧贴它要改的那段）
6. 勾上几场面试记录再生成 → 建议里出现针对他弱项话题的话（如「你并发编程均分 5.2」）
7. 删一份简历 → 它的 review 一起没了：
   `SELECT COUNT(*) FROM t_resume_review WHERE resume_id = ?` 为 0
8. 参考稿里**不该**出现空的岗位小节标题（解析第 3 步生效）