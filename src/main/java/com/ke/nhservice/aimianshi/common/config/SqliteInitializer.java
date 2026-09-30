package com.ke.nhservice.aimianshi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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