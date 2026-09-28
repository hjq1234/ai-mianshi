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
├── graph/          图引擎（纯通用，不 import biz/controller/wrapper）
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
GET    /api/interview/{id}/state    刷新页面用，不推进图
GET    /api/interview/list
GET    /api/interview/{id}/detail   复盘详情
GET    /api/interview/{id}/trace    节点轨迹
```

除 `/api/auth/login` 外全部要 `Authorization: Bearer <token>`。

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
| 部署前必须改 `app.auth.secret` | 默认值是 `change-me-before-deploy-please`，不改等于谁都能签 token |

## 二期计划

复盘页的雷达图、分数趋势图、图路径可视化、话题覆盖度。
数据（五维评分、`t_graph_trace`）一期就已经采全了，二期只负责画。