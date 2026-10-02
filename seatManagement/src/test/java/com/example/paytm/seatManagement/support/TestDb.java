package com.example.paytm.seatManagement.support;

import com.example.paytm.seatManagement.config.AppSettings;
import com.example.paytm.seatManagement.config.Pools;
import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * Connection details for the integration tests, taken from the environment so that nothing credential-like lives
 * in the repository:
 *
 * <pre>
 *   TEST_DATABASE_URL   jdbc:postgresql://localhost:5432/seats_test     (tests are skipped when unset)
 *   TEST_DB_USER        defaults to "postgres"
 *   TEST_DB_PASSWORD    optional
 * </pre>
 *
 * The target database is migrated by Flyway and then written to freely: point it at a scratch database.
 * Every test creates its own show(s), so tests never interfere with each other or with leftovers of earlier runs.
 */
public final class TestDb {

    public static final String ENV_URL = "TEST_DATABASE_URL";

    private TestDb() {
    }

    /** A complete configuration with fresh random secrets (generated at run time, never committed). */
    public static Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("ADMIN_TOKEN", "adm-" + UUID.randomUUID());
        env.put("TOKEN_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID());
        env.put("DATABASE_URL", System.getenv(ENV_URL));
        String user = System.getenv("TEST_DB_USER");
        env.put("DB_USER", user == null || user.trim().isEmpty() ? "postgres" : user.trim());
        String password = System.getenv("TEST_DB_PASSWORD");
        if (password != null && !password.isEmpty()) {
            env.put("DB_PASSWORD", password);
        }
        env.put("DB_POOL_SIZE", "30");
        env.put("DB_CONNECTION_TIMEOUT_MS", "60000");
        return env;
    }

    public static AppSettings settings(Map<String, String> env) {
        return new AppSettings(env::get);
    }

    /** The production pool configuration, with the schema migrated exactly as the application does at start-up. */
    public static HikariDataSource migratedPool(AppSettings settings) {
        HikariDataSource ds = Pools.mainPool(settings);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return ds;
    }
}
