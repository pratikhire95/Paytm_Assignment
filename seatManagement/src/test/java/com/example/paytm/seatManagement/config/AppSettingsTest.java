package com.example.paytm.seatManagement.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppSettingsTest {

    /** A complete, valid environment with run-time random secrets (nothing credential-like is committed). */
    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("ADMIN_TOKEN", "adm-" + UUID.randomUUID());
        env.put("TOKEN_SECRET", UUID.randomUUID() + "-" + UUID.randomUUID());
        env.put("DATABASE_URL", "jdbc:postgresql://localhost:5432/seats");
        env.put("DB_USER", "app");
        return env;
    }

    @Test
    void refusesToStartWithoutSecrets() {
        Map<String, String> noAdmin = validEnv();
        noAdmin.remove("ADMIN_TOKEN");
        assertThrows(IllegalStateException.class, () -> new AppSettings(noAdmin::get));

        Map<String, String> noSecret = validEnv();
        noSecret.remove("TOKEN_SECRET");
        assertThrows(IllegalStateException.class, () -> new AppSettings(noSecret::get));
    }

    @Test
    void refusesWeakSecrets() {
        Map<String, String> shortAdmin = validEnv();
        shortAdmin.put("ADMIN_TOKEN", "short");
        assertThrows(IllegalStateException.class, () -> new AppSettings(shortAdmin::get));

        Map<String, String> shortSecret = validEnv();
        shortSecret.put("TOKEN_SECRET", "too-short-for-hmac");
        assertThrows(IllegalStateException.class, () -> new AppSettings(shortSecret::get));
    }

    @Test
    void errorMessagesNeverEchoTheSecretValue() {
        Map<String, String> env = validEnv();
        String weak = "weak-" + UUID.randomUUID().toString().substring(0, 4);
        env.put("ADMIN_TOKEN", weak);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
        assertFalse(e.getMessage().contains(weak));
    }

    @Test
    void defaultsAreSensible() {
        AppSettings s = new AppSettings(validEnv()::get);
        assertEquals(4, s.defaultPerUserLimit());
        assertEquals(20, s.dbPoolSize());
        assertTrue(s.tokenMintEnabled());
        assertTrue(s.publicLogsEnabled());
        assertEquals(86_400L, s.tokenTtlSeconds());
    }

    @Test
    void rangeChecksRejectNonsense() {
        Map<String, String> env = validEnv();
        env.put("DB_POOL_SIZE", "0");
        assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
        env.put("DB_POOL_SIZE", "abc");
        assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
        env.put("DB_POOL_SIZE", "30");
        env.put("ALLOW_TOKEN_MINT", "maybe");
        assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
    }

    @Test
    void parsesPlatformStylePostgresUrl() {
        Map<String, String> env = validEnv();
        env.put("DATABASE_URL", "postgres://seatuser:p%40ss%2Bw%2Fd@dpg-abc123-a:5432/seats_db?sslmode=require");
        env.remove("DB_USER");
        AppSettings s = new AppSettings(env::get);
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/seats_db?sslmode=require", s.jdbcUrl());
        assertEquals("seatuser", s.dbUser());
        assertEquals("p@ss+w/d", s.dbPassword());
    }

    @Test
    void literalPlusInPasswordSurvives() {
        Map<String, String> env = validEnv();
        env.put("DATABASE_URL", "postgresql://u:a+b@db/seats");
        AppSettings s = new AppSettings(env::get);
        assertEquals("a+b", s.dbPassword());
        assertEquals("jdbc:postgresql://db:5432/seats", s.jdbcUrl());
    }

    @Test
    void jdbcUrlNeedsExplicitUser() {
        Map<String, String> env = validEnv();
        env.remove("DB_USER");
        assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
    }

    @Test
    void fallsBackToDiscreteVariables() {
        Map<String, String> env = validEnv();
        env.remove("DATABASE_URL");
        env.put("DB_HOST", "db.internal");
        env.put("DB_NAME", "seats");
        env.put("DB_PASSWORD", "x");
        AppSettings s = new AppSettings(env::get);
        assertEquals("jdbc:postgresql://db.internal:5432/seats", s.jdbcUrl());
        assertEquals("app", s.dbUser());
    }

    @Test
    void missingDatabaseConfigurationFailsClosed() {
        Map<String, String> env = validEnv();
        env.remove("DATABASE_URL");
        assertThrows(IllegalStateException.class, () -> new AppSettings(env::get));
    }

    @Test
    void describeDatabaseNeverContainsCredentials() {
        Map<String, String> env = validEnv();
        env.put("DATABASE_URL", "postgres://seatuser:topsecret@db.example.com:5432/seats?sslmode=require");
        AppSettings s = new AppSettings(env::get);
        assertEquals("db.example.com:5432/seats", s.describeDatabase());
        assertFalse(s.describeDatabase().contains("topsecret"));
    }
}
