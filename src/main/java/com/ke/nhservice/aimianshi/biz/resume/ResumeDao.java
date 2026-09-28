package com.ke.nhservice.aimianshi.biz.resume;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class ResumeDao {

    private static final RowMapper<Resume> MAPPER = (rs, rowNum) -> new Resume(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("filename"),
            rs.getString("content"),
            rs.getInt("is_default") == 1,
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ResumeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Long userId, String filename, String content) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_resume(user_id, filename, content, is_default, created_at) VALUES (?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, filename);
            ps.setString(3, content);
            ps.setLong(4, System.currentTimeMillis());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入简历后没有拿到自增主键");
        }
        return key.longValue();
    }

    public List<Resume> listByUser(Long userId) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE user_id = ? ORDER BY is_default DESC, created_at DESC
                """, MAPPER, userId);
    }

    public Optional<Resume> findById(Long id) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE id = ?
                """, MAPPER, id).stream().findFirst();
    }

    public Optional<Resume> findDefault(Long userId) {
        return jdbc.query("""
                SELECT id, user_id, filename, content, is_default, created_at
                FROM t_resume WHERE user_id = ? AND is_default = 1
                ORDER BY created_at DESC LIMIT 1
                """, MAPPER, userId).stream().findFirst();
    }

    public int countByUser(Long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM t_resume WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** 先把该用户所有简历的标记清掉再设新的，保证「默认简历」全局唯一 */
    @Transactional
    public void setDefault(Long userId, Long resumeId) {
        jdbc.update("UPDATE t_resume SET is_default = 0 WHERE user_id = ?", userId);
        jdbc.update("UPDATE t_resume SET is_default = 1 WHERE id = ? AND user_id = ?", resumeId, userId);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM t_resume WHERE id = ?", id);
    }
}