package com.ke.nhservice.aimianshi.biz.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserDao {

    private static final RowMapper<User> MAPPER = (rs, rowNum) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        List<User> list = jdbc.query(
                "SELECT id, username, password_hash, nickname, created_at FROM t_user WHERE username = ?",
                MAPPER, username);
        return list.stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        List<User> list = jdbc.query(
                "SELECT id, username, password_hash, nickname, created_at FROM t_user WHERE id = ?",
                MAPPER, id);
        return list.stream().findFirst();
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM t_user", Long.class);
        return n == null ? 0 : n;
    }

    public long insert(String username, String passwordHash, String nickname) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO t_user(username, password_hash, nickname, created_at) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            ps.setLong(4, System.currentTimeMillis());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入用户后没有拿到自增主键");
        }
        return key.longValue();
    }
}