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

    -- ★ 软删标记。1 = 已删。查询一律要带 AND deleted = 0（见 InterviewDao 类注释里那 4 处）
    -- ★ 这一行只对新库生效——CREATE TABLE IF NOT EXISTS 对已存在的表是整条跳过的，
    --   老库靠 SqliteInitializer 的 ALTER 补
    deleted     INTEGER NOT NULL DEFAULT 0,

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

-- 简历改稿。一次生成一行，按简历留历史。
-- ★ 这是一张**新表**：CREATE TABLE IF NOT EXISTS 直接建就行，
--   不需要像上面 deleted 列那样靠 SqliteInitializer 补 ALTER（那是对已存在的表加列）
CREATE TABLE IF NOT EXISTS t_resume_review (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER NOT NULL,
    resume_id        INTEGER NOT NULL,            -- 改的是哪份简历
    markdown         TEXT    NOT NULL,            -- LLM 全文，批注（> 建议：）留在原位
    suggestion_count INTEGER NOT NULL DEFAULT 0,  -- 写入时算一次，列表页不用拖全文
    targets_json     TEXT,                        -- [{"title":"Java 后端","jd":"…"}]，至少一条
    interview_ids    TEXT,                        -- 参考了哪几场（逗号分隔），没选为 NULL
    model            TEXT,                        -- 换模型后能看出这条是哪个生成的
    truncated        INTEGER NOT NULL DEFAULT 0,  -- 1 = 输出被长度上限截断
    created_at       INTEGER NOT NULL
);

-- (record_id, seq) 唯一：evaluate 节点失败重跑时用 INSERT OR REPLACE 覆盖，
-- 避免同题写出两条对话记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_dialogue_record_seq ON t_interview_dialogue(record_id, seq);
CREATE INDEX IF NOT EXISTS idx_trace_record  ON t_graph_trace(record_id, seq);
CREATE INDEX IF NOT EXISTS idx_record_user   ON t_interview_record(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_resume_user   ON t_resume(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_resume ON t_resume_review(resume_id, created_at DESC);