# 二期 · 复盘页可视化 设计文档

> 日期：2026-09-29　分支：`feat/interview-phase1`（二期仍在此分支上做）
> 前置：一期（核心主流程）已完成，四问题修复已落地

## 一、背景与目标

一期把复盘页做成了「文字版」：逐题详情 + 决策链表格 + AI 综合报告。一期的设计文档把二期定为
「复盘增强」——把已经采全的数据画成图。

**目标**：复盘页从「读文字」变成「看趋势」——一眼看出能力短板在哪、分数怎么走的、
图的每一步花了多久、话题聊得均不均匀。

**关键前提**：后端数据一期就采全了，二期基本是纯前端画图。唯一的新后端代码是一个
历史均分接口（雷达图要拿历史做对比）。

## 二、范围

**做**：

1. 复盘页四张图：能力雷达图、分数趋势图、图执行路径、话题覆盖度
2. 新接口 `GET /api/interview/stats`（五维历史均分）
3. 删掉复盘页的「图执行轨迹」原始表格

**不做**：知识库 embedding、流式输出、图表的图片导出、`graph/` 包的任何改动、node/npm 构建链。

## 三、真实数据摸底（设计依据）

摸的是本地库 `data/interview.db` 里最新一场跑完的面试（记录 4，Java/中等，10 题）：

| 指标 | 实测值 | 对设计的影响 |
|---|---|---|
| trace 条数 | 62 条 / 10 轮 | 不是一两百条，规模很小 |
| 每轮形状 | `分支节点 → question → wait_answer×2 → evaluate`，第 10 轮多 `end_loop` | 高度规整的循环，不是任意拓扑 |
| 分支分布 | `continue ×12、switch ×4、deepen ×2、end_loop ×2` | 分支种类少，适合当标签而不是节点 |
| 节点耗时 | `question` 5.1 秒、**`evaluate` 24 秒** | 耗时差异巨大，且页面上完全看不到 |
| 整场跨度 | 1,421,217 ms ≈ 23.7 分钟 | 正好对上文档里「用时 23 分钟」 |

**由此推翻文档里的一个原方案**：一期文档写的是用 ECharts `graph`（力导向图）画路径。
但这份数据是**一个 10 次的循环**——力导向图会把 5 个节点甩成一个圈，每条边重叠 10 次，
既看不出顺序也看不出耗时。改用时间线泳道。

**另一个结论**：整场「用时」不能用 `Σ cost_ms` 算——用户作答的那几分钟没有 trace 记录。
必须用记录的时间跨度（`createdAt` → `updatedAt`）。

## 四、页面新结构

```
① 概览（含导出按钮）              ← 不变
② 能力雷达图  │  分数趋势图        ← 新增，并排；窄屏自动堆叠
③ 图执行路径（泳道时间线）         ← 新增
④ 话题覆盖度                      ← 新增
⑤ 图的分支决策链（表格）           ← 保留，原样
⑥ 逐题详情 · AI 综合报告           ← 不变
⑦ 图执行轨迹（62 行裸数据表格）    ← 删除
```

删轨迹表的理由：泳道图完全覆盖它（节点、耗时、状态、分支都有），而且泳道图能一眼看出耗时差异；
留着它只是把开发向的裸数据摆在用户面前。决策链表格保留——它是可读版，而且导出 Markdown 靠它。

## 五、四张图的详细设计

统一走 CDN 引入 **ECharts 5.6.0**（`https://cdn.jsdelivr.net/npm/echarts@5.6.0/dist/echarts.min.js`）。
已验证该 URL 可访问（206）。选 5.6.0 而不是 npm 上最新的 6.1.0：雷达/折线/柱状这三个图表类型的
API 在 5.x 上是稳定的，实现时能照确定性写法走；6.x 是新大版本，跑通再升不迟。

**通用约束**：ECharts 容器必须有明确高度（CSS 里给死，如 `height: 260px`）。
容器高度为 0 时 ECharts 什么都不画——表现和「白屏」一样，是这个库最常见的一个坑。

### ① 能力雷达图

- ECharts `radar`，五个轴（准确性/深度/表达/实践/解题思路），`max: 10`
- 两条多边形：本场（实线 + 半透明填充）+ 历史均分（虚线、不填充）
- 图例写明场次：「历史均分（12 场）」
- **`practice` 为 null 的处理**：本场多边形该维取 0，卡片下方一行小字说明
  「本场无实践分（纯概念题不打分），图中按 0 显示」。不假装有分。
- 历史均分按「有分的场次」平均，忽略 null——不能被 null 当成 0 拉低
- 本场数据来源：`detail.dialogues[].dimensions` 的平均（忽略 null 维度）
- 历史数据来源：`/api/interview/stats`

### ② 分数趋势图

- ECharts `line`，x 轴题号 1..N，y 轴 0-10（`min:0, max:10`）
- 折线 + 数据点，点上标分数值
- 一条均分虚线（`markLine`）
- **换话题的位置用浅色竖带标出**（`markArea`，按 `nextAction === 'switch'` 的题号）——
  一眼看出「第 3、5、7 题换了话题」，也就是话题边界
- tooltip 显示：话题 / 难度 / 分支 / 评语

### ③ 图执行路径（泳道时间线）

**这一张不用 ECharts，用 HTML/CSS 画。** 理由：它本质是「表格 + 色块」而不是坐标图。
ECharts 的 `custom series` 要写 `renderItem` 那套坐标数学（约 60 行且难读），
而 flex + 百分比宽度只要 20 行，还能直接复用现成的 `.tag` / `.muted` 样式。

- 每轮一行（共 N 行），行首是「第 N 轮」
- 行内每个节点一个色块，**宽度 = 该节点耗时占本轮总耗时的比例**
- 颜色区分规则：`nodeType === 'branch'` 的行走绿色（分支决策），其余按 `status` 走——
  `suspend` 黄（等你作答）、`error` 红、`ok` 灰蓝。判断顺序是先 nodeType 后 status，
  因为 `evaluate` 的 branch 行 status 也是 `ok`，混在一起会看不出哪条是决策
- 行末标分支去向的中文（深入追问 / 换个角度 / 更换话题 / 结束）
- 色块 `title` 属性显示 `节点名 · 5119 ms · ok`
- 卡片顶部一行图例 + 一句说明「色块宽度 = 该节点耗时」
- 数据来源：`detail.traces`（已有），前端按 `round` 分组；
  `evaluate` 在 trace 里有两条（一条 normal、一条 branch），渲染时合并成一条并取 branch 行的去向
- 卡片副标题里的「整场用时」用 `updatedAt - createdAt`，**不是** Σcost_ms（见第三节）

### ④ 话题覆盖度

- ECharts 横向柱状图（`bar` + `yAxis type:'category'`），每个话题一条
- 条长 = 该话题的题数；条右侧标「6 题 · 均分 7.1」
- 按题数降序；话题为空的题归到「综合」

### 空态与降级

| 情况 | 表现 |
|---|---|
| 这场还没答完（`dialogues` 为空） | 四张图都不画，各卡片显示「这场还没答完，还没有数据」 |
| `traces` 为空 | 泳道卡片显示空态，其余三张照画 |
| `/stats` 请求失败 | **只降级雷达图**，改成只画本场一条多边形 + 图例注明「历史均分暂不可用」；其余三张图和整页不受影响 |
| 只有 1 题 | 三张 ECharts 图正常画（雷达是两个点重合的多边形，可接受） |
| 某题 `dimensions` 全 null | 该题不计入本场均分；若全为空则雷达只画历史那条 |

**贯穿原则**：图表是增强，任何一张图挂了都不能让复盘页挂。每张图单独 try/catch，
失败时把容器换成一行「这张图渲染失败」的文字。

## 六、后端：`GET /api/interview/stats`

```java
public record InterviewStatsVO(
        int finishedCount,                  // 已完成的场次
        Map<String, Double> dimensions,     // 五维历史均分（忽略 null）
        Double totalScore) {                // 总分历史均分
}
```

实现：

1. `SELECT id FROM t_interview_record WHERE user_id = ? AND status = 'finished'`
2. 按这批 id 批量取 `t_interview_dialogue` 的 `eval_json` 和 `score`
3. Java 侧解析 `eval_json`（复用 `InterviewDao` 里已有的那个回复报文解析，五维就存在这个字段里）
4. 每个维度累加**非 null** 的值并单独计数 → 均分；`score` 同理
5. `finishedCount = 0` 时返回空 map 和 null 总分

**不按岗位方向过滤**：现在只用 Java，加 `domain` 参数是 YAGNI，将来要再加。

`eval_json` 是 `TEXT`，`score` 是 `REAL`；一场 10 题、几十场也就几百行，Java 侧遍历足够，
不引入 SQL 的 `json_extract`。

## 七、文件分工

| 文件 | 动作 | 职责 |
|---|---|---|
| `static/js/charts.js` | 新建 | 四个渲染函数（见下），零构建，`report.html` 用 `<script>` 引 |
| `static/report.html` | 改 | 加 4 张卡片 + 调用；删轨迹表格卡片；引入 ECharts 与 charts.js |
| `static/css/app.css` | 改 | 泳道样式、图表容器高度、卡片副标题 |
| `static/js/app.js` | 改 | 导出 Markdown 补一段「数据概览」（用时 / 话题覆盖 / 五维均分），让导出和图表对齐 |
| `controller/InterviewController.java` | 改 | `GET /stats` |
| `biz/interview/InterviewDao.java` | 改 | 批量查已完成场次的对话行（照 `countDialoguesByRecord` 的批量写法） |
| `common/dto/InterviewStatsVO.java` | 新建 | 上面的 record |
| `README.md` | 改 | 同步二期行为 |

`charts.js` 的接口（`report.html` 只调这四个，不碰 ECharts 细节）：

```js
renderRadar(el, { current, history, historyCount })  // current/history 都是 {准确性维度key: 值}
renderScoreTrend(el, dialogues)
renderTopicCoverage(el, dialogues)
traceSwimlaneHtml(traces)                            // 返回 HTML 字符串，不是 ECharts
```

`charts.js` 独立成文件而不是写进 `report.html` 的内联脚本：四张图约 250 行，
塞进去那个文件要破 500 行；独立文件仍然是零构建（多一个 script 标签而已）。

## 八、验证策略

沿用一期的方式：**仓库里不写测试类**，验证放 `%TEMP%` 的临时程序。

| 验什么 | 用什么 | 要点 |
|---|---|---|
| 六个页面不白屏 | 扩展 `%TEMP%\PageCheck.js` | report.html 加图后回归风险最大。沙箱里没有 CDN，需要注入一个假的 `echarts` 全局（`init()` 返回 `{setOption(){}, resize(){}}`） |
| 聚合算法 | 新建 `%TEMP%\StatsCheck.java` | 边界：practice 为 null、`finishedCount = 0`、全 null 的题 |
| 新接口 | 扩展 `%TEMP%\ApiCheck.java` | 真 HTTP + 真鉴权 |
| 导出仍然对 | 扩展 `%TEMP%\ExportCheck.js` | 加了「数据概览」段后原有断言不回归 |
| 四张图长什么样 | 人工打开复盘页 | 唯一能验「好不好看」的方式 |

## 九、沿用一期的约束

- 仓库里**不写测试类**，验证程序一律放 `%TEMP%`
- **只 commit 不 push**
- 全程中文（代码注释、提交信息、文档）
- `src/main/resources/application.yml` 里**不得出现真实 api-key**；
  本地那处指向内部网关的 `base-url` / `model` 改动**不要提交**
- commit 用显式文件路径，不用 `git add -A`

## 十、决策记录

| 决策 | 选了什么 | 放弃了什么 | 为什么 |
|---|---|---|---|
| 路径图形态 | 时间线泳道 | 力导向图（一期文档原方案） | 实测是 10 次循环，力导向图会把边重叠 10 次，看不出顺序和耗时 |
| 泳道怎么渲染 | HTML/CSS | ECharts custom series | 本质是表格+色块，不是坐标图；20 行 vs 60 行难读的坐标数学 |
| 雷达图对比 | 本场 + 历史均分 | 只画本场 / 对比上一场 | 只画一场看不出强弱；对比上一场对第一场和隔太久的场次没意义 |
| ECharts 版本 | 5.6.0 | 6.1.0（npm 最新） | 三个图表类型的 API 在 5.x 稳定，6.x 是新大版本，跑通再升 |
| 轨迹表格 | 删掉 | 保留 / 折叠 | 泳道图完全覆盖它，且更能看出耗时差异 |
| 统计口径 | 不按岗位方向过滤 | 加 `domain` 参数 | 现在只用 Java，YAGNI |
| 整场用时 | `updatedAt - createdAt` | `Σ cost_ms` | 作答等待时间没有 trace 记录，Σ 会少算好几分钟 |