package com.example.paytm.seatManagement.domain;

import com.example.paytm.seatManagement.common.ApiException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;

/** Classification of database failures: retry it, report "try again shortly" (503), or treat it as a bug (500). */
public final class SqlErrors {

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

    /** Never returns normally. The message sent to clients is fixed: no SQL, hosts or driver text leak out. */
    public static RuntimeException map(SQLException e) {
        if (isTransient(e)) {
            return new ApiException(503, "service_unavailable",
                    "The service is temporarily unable to process this request; please retry shortly");
        }
        return new IllegalStateException("unexpected database failure, sqlstate=" + e.getSQLState(), e);
    }
}
