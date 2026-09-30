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

/**
 * 简历改稿的读写。
 *
 * 和 t_resume 一样**物理删**：改稿是派生产物，没有任何聚合依赖它
 * （不像面试记录——/stats 的历史均分按场次聚合，真删会顺手改掉「历史水平」）。
 *
 * ★ 删一份简历要连带删它的改稿（见 {@link #deleteByResume}）：t_resume 没有外键级联。
 */
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
     *
     * ★ 不取 markdown（NULL AS markdown）：一行的全文可能上万字，而列表只要元信息。
     *   要全文走 findById。所以列表里那些对象的 markdown() 是 null，别拿它去渲染。
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
     * 删一份简历时连带删它的全部改稿，返回删了几条。
     *
     * ★ t_resume 是物理删、**没有外键级联**，不这么删就会留下孤儿行：
     *   按 resumeId 查永远查不到它们，但行还在库里占着地方。
     */
    public int deleteByResume(Long resumeId) {
        return jdbc.update("DELETE FROM t_resume_review WHERE resume_id = ?", resumeId);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM t_resume_review WHERE id = ?", id);
    }
}