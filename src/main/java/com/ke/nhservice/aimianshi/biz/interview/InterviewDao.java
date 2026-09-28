package com.ke.nhservice.aimianshi.biz.interview;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ke.nhservice.aimianshi.common.util.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                JsonUtil.toJson(new EvalJson(d.getDimensions(), d.getComment())),
                d.getNextAction(), d.getNextTopic(), System.currentTimeMillis());
    }

    /**
     * 批量统计每场面试答了几题。列表页一次查完，避免逐条 count 的 N+1。
     * 拼进 SQL 的只有若干个 "?"，参数仍走占位符绑定，没有注入面。
     */
    public Map<Long, Integer> countDialoguesByRecord(List<Long> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(recordIds.size(), "?"));
        String sql = "SELECT record_id, COUNT(*) AS c FROM t_interview_dialogue "
                + "WHERE record_id IN (" + placeholders + ") GROUP BY record_id";

        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query(sql, rs -> {
            counts.put(rs.getLong("record_id"), rs.getInt("c"));
        }, recordIds.toArray());
        return counts;
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
                EvalJson ej = JsonUtil.fromJson(evalJson, EvalJson.class);
                if (ej.dimensions() != null) {
                    d.setDimensions(ej.dimensions());
                }
                d.setComment(ej.comment());
            }
            return d;
        }, recordId);
    }

    /**
     * eval_json 的载荷：五维明细 + 评语。
     *
     * 评语和五维明细都是同一题的 LLM 评分产物，放一个 JSON 里一起存取，
     * 省一个列。★ 早先这里只存了 dimensions，评语被静默丢掉——
     * 于是复盘页永远看不到评语，而 EndNode 生成报告用的是内存对象、报告里却有，
     * 两边不一致。schema 对 eval_json 的注释本来就写着「五维明细 + 评语」，
     * 是代码没做到。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EvalJson(Map<String, Double> dimensions, String comment) {
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