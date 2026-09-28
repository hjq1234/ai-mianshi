package com.ke.nhservice.aimianshi.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQLite 的 journal_mode 是写进数据库文件头的，设置一次永久生效，
 * 所以放在启动时执行一次就够（busy_timeout 则是每连接生效的，在 Hikari 的
 * connection-init-sql 里配）。
 *
 * 不开 WAL 的话，写操作会锁整库，读也被挡住。
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
        String mode = jdbcTemplate.queryForObject("PRAGMA journal_mode=WAL", String.class);
        log.info("SQLite journal_mode = {}", mode);
        if (!"wal".equalsIgnoreCase(mode)) {
            log.warn("SQLite 未进入 WAL 模式，并发写入可能报 database is locked");
        }
    }
}