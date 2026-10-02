package com.example.paytm.seatManagement.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds the HikariCP pools. One place, so the main pool and the readiness probe pool cannot drift apart. */
public final class Pools {

    private Pools() {
    }

    /**
     * The main application pool.
     *
     * <p>Fixed size (min idle == max): a stampede must not wait for the pool to grow. Requests beyond the pool
     * size queue inside Hikari (they are not rejected), bounded by {@code dbConnectionTimeoutMs}.
     * Session guards are applied to every physical connection: a runaway statement, a lock wait or an abandoned
     * transaction can never pin a connection forever.
     */
    public static HikariDataSource mainPool(AppSettings s) {
        HikariConfig cfg = base(s, "seats-main");
        cfg.setMaximumPoolSize(s.dbPoolSize());
        cfg.setMinimumIdle(s.dbPoolSize());
        cfg.setConnectionTimeout(s.dbConnectionTimeoutMs());
        cfg.setConnectionInitSql(
                "SELECT set_config('statement_timeout', '30000', false), "
                        + "set_config('lock_timeout', '20000', false), "
                        + "set_config('idle_in_transaction_session_timeout', '60000', false)");
        return new HikariDataSource(cfg);
    }

    /**
     * A tiny dedicated pool for /readyz. It is separate from the main pool on purpose: during a stampede the
     * main pool is saturated by design, and a readiness probe that queued behind it would report "not ready"
     * for a service that is in fact working flat out.
     */
    public static HikariDataSource probePool(AppSettings s) {
        HikariConfig cfg = base(s, "seats-probe");
        int timeout = s.readinessTimeoutMs();
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(timeout);
        cfg.setValidationTimeout(Math.max(250, timeout - 250));
        return new HikariDataSource(cfg);
    }

    private static HikariConfig base(AppSettings s, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(s.jdbcUrl());
        cfg.setUsername(s.dbUser());
        if (s.dbPassword() != null) {
            cfg.setPassword(s.dbPassword());
        }
        // Do not fail startup just because the database is momentarily unreachable (cold start ordering on
        // free tiers); Flyway retries the first connection and /readyz reports the truth meanwhile.
        cfg.setInitializationFailTimeout(-1);
        cfg.setMaxLifetime(900_000L);
        cfg.setKeepaliveTime(120_000L);
        cfg.addDataSourceProperty("ApplicationName", "seat-management");
        cfg.addDataSourceProperty("tcpKeepAlive", "true");
        cfg.addDataSourceProperty("connectTimeout", "10");
        cfg.addDataSourceProperty("socketTimeout", "60");
        return cfg;
    }
}
