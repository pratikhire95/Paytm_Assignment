package com.example.paytm.seatManagement.domain;

import com.example.paytm.seatManagement.common.ApiException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Classification of database failures: retry it, report "try again shortly" (503), or treat it as a bug (500). */
public final class SqlErrors {

    private static final Logger log = LoggerFactory.getLogger(SqlErrors.class);

    private SqlErrors() {
    }

    /**
     * Failures that mean "you lost a lock-ordering or serialisation race, running the transaction again is
     * correct": 40001 serialization_failure, 40P01 deadlock_detected. With the deterministic lock order
     * deadlocks should not occur at all; this is the safety net, not the mechanism.
     */
    public static boolean isRetryable(SQLException e) {
        String state = e.getSQLState();
        return "40001".equals(state) || "40P01".equals(state);
    }

    /** Dependency trouble (connection, pool wait, timeout, lock wait, shutdown): the client should retry later. */
    public static boolean isTransient(SQLException e) {
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException
                || e instanceof SQLTimeoutException || e instanceof SQLNonTransientConnectionException) {
            return true;
        }
        String state = e.getSQLState();
        if (state == null) {
            return false;
        }
        return state.startsWith("08")          // connection exception
                || state.startsWith("53")      // insufficient resources (e.g. too many connections)
                || state.startsWith("57")      // operator intervention (statement timeout, admin shutdown)
                || state.startsWith("40")      // serialization failure / deadlock that exhausted retries
                || "55P03".equals(state);      // lock_not_available (lock_timeout)
    }

    /**
     * A short, stable label for WHY a database call ended in "try again shortly", so a 503 in the logs says what to look at:
     * {@code pool_timeout} = no pooled connection became free in time (saturation), {@code lock_timeout} / {@code statement_timeout}
     * = the database was too slow or a lock was held too long, {@code connection_lost} = the database is unreachable.
     */
    public static String causeOf(SQLException e) {
        if (e instanceof SQLTransientConnectionException) {
            return "pool_timeout"; // HikariCP: no connection became available within DB_CONNECTION_TIMEOUT_MS (or none could be opened)
        }
        String state = e.getSQLState();
        if ("55P03".equals(state)) {
            return "lock_timeout";
        }
        if ("57014".equals(state)) {
            return "statement_timeout";
        }
        if (state != null && state.startsWith("40")) {
            return "deadlock_or_serialization";
        }
        if (e instanceof SQLNonTransientConnectionException || (state != null && state.startsWith("08"))) {
            return "connection_lost";
        }
        if (state != null && state.startsWith("53")) {
            return "database_out_of_resources";
        }
        if (state != null && state.startsWith("57")) {
            return "database_shutdown";
        }
        if (e instanceof SQLTimeoutException) {
            return "timeout";
        }
        return "transient_error";
    }

    /**
     * Never returns normally. The message sent to clients is fixed: no SQL, hosts or driver text leak out. The cause goes to the
     * server log only (it is what tells an operator, and the next person reading /logs, which kind of 503 this was).
     */
    public static RuntimeException map(SQLException e) {
        if (isTransient(e)) {
            // For a pool timeout the driver's own text is just the pool name and its counters ("... request timed out after
            // 30000ms (total=20, active=20, idle=0, waiting=250)"): no host, user or SQL, and exactly the numbers that show saturation.
            log.warn("database call failed, answering 503: cause={} sqlstate={}{}", causeOf(e), e.getSQLState(),
                    e instanceof SQLTransientConnectionException ? " detail=" + e.getMessage() : "");
            return new ApiException(503, "service_unavailable",
                    "The service is temporarily unable to process this request; please retry shortly");
        }
        return new IllegalStateException("unexpected database failure, sqlstate=" + e.getSQLState(), e);
    }
}
