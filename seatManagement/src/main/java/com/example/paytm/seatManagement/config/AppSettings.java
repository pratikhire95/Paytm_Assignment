package com.example.paytm.seatManagement.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * All runtime configuration, read once from environment variables and validated up front.
 *
 * <p>Security posture: there are deliberately NO default secrets. If ADMIN_TOKEN or TOKEN_SECRET is
 * missing or too short the application refuses to start (fail closed) instead of silently running with
 * a guessable credential. Nothing in this class is ever logged except non-sensitive tuning values.
 */
public final class AppSettings {

    private final String adminToken;
    private final byte[] tokenSecret;
    private final long tokenTtlSeconds;
    private final boolean tokenMintEnabled;
    private final boolean publicLogsEnabled;
    private final int defaultPerUserLimit;
    private final int maxSeatsPerShow;
    private final int maxSeatsPerRequest;
    private final int metricsMaxShows;
    private final int dbPoolSize;
    private final int dbConnectionTimeoutMs;
    private final int readinessTimeoutMs;
    private final int logBufferSize;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;

    /** @param env lookup of environment variables / Spring properties (returns null when unset) */
    public AppSettings(Function<String, String> env) {
        this.adminToken = required(env, "ADMIN_TOKEN", 16);
        this.tokenSecret = required(env, "TOKEN_SECRET", 32).getBytes(StandardCharsets.UTF_8);
        this.tokenTtlSeconds = longVal(env, "TOKEN_TTL_SECONDS", 86_400L, 60L, 30L * 86_400L);
        this.tokenMintEnabled = boolVal(env, "ALLOW_TOKEN_MINT", true);
        this.publicLogsEnabled = boolVal(env, "PUBLIC_LOGS_ENABLED", true);
        this.defaultPerUserLimit = (int) longVal(env, "DEFAULT_PER_USER_LIMIT", 4, 1, 100);
        this.maxSeatsPerShow = (int) longVal(env, "MAX_SEATS_PER_SHOW", 100_000, 1, 500_000);
        this.maxSeatsPerRequest = (int) longVal(env, "MAX_SEATS_PER_REQUEST", 100, 1, 1_000);
        this.metricsMaxShows = (int) longVal(env, "METRICS_MAX_SHOWS", 10, 1, 200);
        this.dbPoolSize = (int) longVal(env, "DB_POOL_SIZE", 20, 1, 200);
        this.dbConnectionTimeoutMs = (int) longVal(env, "DB_CONNECTION_TIMEOUT_MS", 30_000, 1_000, 120_000);
        this.readinessTimeoutMs = (int) longVal(env, "READINESS_TIMEOUT_MS", 2_000, 500, 30_000);
        this.logBufferSize = (int) longVal(env, "LOG_BUFFER_SIZE", 5_000, 100, 100_000);

        String[] jdbc = resolveJdbc(env);
        this.jdbcUrl = jdbc[0];
        this.dbUser = jdbc[1];
        this.dbPassword = jdbc[2];
    }

    // ---------------------------------------------------------------- accessors

    public String adminToken() {
        return adminToken;
    }

    public byte[] tokenSecret() {
        return tokenSecret.clone();
    }

    public long tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public boolean tokenMintEnabled() {
        return tokenMintEnabled;
    }

    public boolean publicLogsEnabled() {
        return publicLogsEnabled;
    }

    public int defaultPerUserLimit() {
        return defaultPerUserLimit;
    }

    public int maxSeatsPerShow() {
        return maxSeatsPerShow;
    }

    public int maxSeatsPerRequest() {
        return maxSeatsPerRequest;
    }

    public int metricsMaxShows() {
        return metricsMaxShows;
    }

    public int dbPoolSize() {
        return dbPoolSize;
    }

    public int dbConnectionTimeoutMs() {
        return dbConnectionTimeoutMs;
    }

    public int readinessTimeoutMs() {
        return readinessTimeoutMs;
    }

    public int logBufferSize() {
        return logBufferSize;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String dbUser() {
        return dbUser;
    }

    public String dbPassword() {
        return dbPassword;
    }

    /** host:port/database only - safe to log (no credentials, no query string). */
    public String describeDatabase() {
        String s = jdbcUrl;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slashes = s.indexOf("//");
        return slashes >= 0 ? s.substring(slashes + 2) : s;
    }

    // ---------------------------------------------------------------- parsing helpers

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String required(Function<String, String> env, String key, int minLength) {
        String v = trimToNull(env.apply(key));
        if (v == null) {
            throw new IllegalStateException(key + " must be set (at least " + minLength + " characters). "
                    + "There is intentionally no default.");
        }
        if (v.length() < minLength) {
            throw new IllegalStateException(key + " is too short (need at least " + minLength + " characters).");
        }
        return v;
    }

    private static long longVal(Function<String, String> env, String key, long def, long min, long max) {
        String v = trimToNull(env.apply(key));
        if (v == null) {
            return def;
        }
        long parsed;
        try {
            parsed = Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(key + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalStateException(key + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private static boolean boolVal(Function<String, String> env, String key, boolean def) {
        String v = trimToNull(env.apply(key));
        if (v == null) {
            return def;
        }
        if ("true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v) || "0".equals(v) || "no".equalsIgnoreCase(v)) {
            return false;
        }
        throw new IllegalStateException(key + " must be true or false");
    }

    /**
     * Accepts DATABASE_URL as jdbc:postgresql://..., postgres://user:pass@host:port/db or
     * postgresql://...; otherwise falls back to DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD.
     * Returns {jdbcUrl, user, password}.
     */
    static String[] resolveJdbc(Function<String, String> env) {
        String raw = trimToNull(env.apply("DATABASE_URL"));
        String envUser = trimToNull(env.apply("DB_USER"));
        String envPassword = trimToNull(env.apply("DB_PASSWORD"));

        if (raw != null) {
            if (raw.startsWith("jdbc:")) {
                if (envUser == null) {
                    throw new IllegalStateException("DB_USER must be set when DATABASE_URL is a jdbc: URL");
                }
                return new String[] {raw, envUser, envPassword};
            }
            String rest;
            if (raw.startsWith("postgres://")) {
                rest = raw.substring("postgres://".length());
            } else if (raw.startsWith("postgresql://")) {
                rest = raw.substring("postgresql://".length());
            } else {
                throw new IllegalStateException("DATABASE_URL must start with jdbc:, postgres:// or postgresql://");
            }
            return parsePostgresUrl(rest, envUser, envPassword);
        }

        String host = trimToNull(env.apply("DB_HOST"));
        String name = trimToNull(env.apply("DB_NAME"));
        if (host == null || name == null || envUser == null) {
            throw new IllegalStateException(
                    "Database not configured: set DATABASE_URL, or DB_HOST + DB_NAME + DB_USER (+ DB_PORT, DB_PASSWORD)");
        }
        String port = trimToNull(env.apply("DB_PORT"));
        if (port == null) {
            port = "5432";
        }
        return new String[] {"jdbc:postgresql://" + host + ":" + port + "/" + name, envUser, envPassword};
    }

    private static String[] parsePostgresUrl(String rest, String envUser, String envPassword) {
        String authorityAndPath = rest;
        String query = null;
        int q = authorityAndPath.indexOf('?');
        if (q >= 0) {
            query = authorityAndPath.substring(q + 1);
            authorityAndPath = authorityAndPath.substring(0, q);
        }
        int slash = authorityAndPath.indexOf('/');
        if (slash < 0 || slash == authorityAndPath.length() - 1) {
            throw new IllegalStateException("DATABASE_URL has no database name");
        }
        String authority = authorityAndPath.substring(0, slash);
        String database = authorityAndPath.substring(slash + 1);

        String hostPort = authority;
        String user = envUser;
        String password = envPassword;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            hostPort = authority.substring(at + 1);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                user = decode(userInfo.substring(0, colon));
                password = decode(userInfo.substring(colon + 1));
            } else {
                user = decode(userInfo);
            }
        }
        if (user == null || user.isEmpty()) {
            throw new IllegalStateException("DATABASE_URL has no user (and DB_USER is not set)");
        }
        if (hostPort.isEmpty()) {
            throw new IllegalStateException("DATABASE_URL has no host");
        }
        if (hostPort.indexOf(':') < 0) {
            hostPort = hostPort + ":5432";
        }
        String url = "jdbc:postgresql://" + hostPort + "/" + database + (query == null ? "" : "?" + query);
        return new String[] {url, user, password};
    }

    private static String decode(String s) {
        // URLDecoder treats '+' as a space (form encoding); in a URL userinfo a literal '+' must stay '+'.
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
