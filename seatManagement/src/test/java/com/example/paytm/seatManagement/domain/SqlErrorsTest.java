package com.example.paytm.seatManagement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paytm.seatManagement.common.ApiException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

    private static final String POOL_TIMEOUT =
            "seats-main - Connection is not available, request timed out after 30000ms (total=20, active=20, idle=0, waiting=250)";

    @Test
    void onlyDeadlocksAndSerializationFailuresAreRetriedInProcess() {
        assertTrue(SqlErrors.isRetryable(new SQLException("deadlock detected", "40P01")));
        assertTrue(SqlErrors.isRetryable(new SQLException("could not serialize access", "40001")));
        assertFalse(SqlErrors.isRetryable(new SQLException("canceling statement due to lock timeout", "55P03")));
        assertFalse(SqlErrors.isRetryable(new SQLException("duplicate key", "23505")));
        assertFalse(SqlErrors.isRetryable(new SQLTransientConnectionException(POOL_TIMEOUT)));
    }

    @Test
    void overloadAndOutagesAreAnswered503WithAFixedMessage() {
        SQLException[] transientFailures = {
            new SQLTransientConnectionException(POOL_TIMEOUT),
            new SQLException("canceling statement due to lock timeout", "55P03"),
            new SQLException("canceling statement due to statement timeout", "57014"),
            new SQLException("Connection to db.internal:5432 refused", "08001"),
            new SQLException("deadlock detected", "40P01"),
            new SQLException("sorry, too many clients already", "53300"),
            new SQLTimeoutException("timed out"),
            new SQLNonTransientConnectionException("connection closed"),
        };
        for (SQLException e : transientFailures) {
            RuntimeException mapped = SqlErrors.map(e);
            assertTrue(mapped instanceof ApiException, "not a 503 for: " + e.getMessage());
            ApiException api = (ApiException) mapped;
            assertEquals(503, api.getStatus());
            assertEquals("service_unavailable", api.getCode());
            // the client-facing text is fixed: nothing from the driver (host names, SQL, pool internals) may leak into it
            assertFalse(api.getMessage().contains("db.internal"));
            assertFalse(api.getMessage().contains("seats-main"));
            assertFalse(api.getMessage().contains("sqlstate"));
        }
    }

    @Test
    void everythingElseIsABugNotAnOutage() {
        RuntimeException mapped = SqlErrors.map(new SQLException("duplicate key value violates unique constraint", "23505"));
        assertTrue(mapped instanceof IllegalStateException);
        assertTrue(mapped.getMessage().contains("23505"));
        assertFalse(mapped.getMessage().contains("duplicate key")); // the driver text stays in the cause, never in the message
    }

    @Test
    void causesHaveStableLabelsForTheLogs() {
        assertEquals("pool_timeout", SqlErrors.causeOf(new SQLTransientConnectionException(POOL_TIMEOUT)));
        assertEquals("pool_timeout", SqlErrors.causeOf(new SQLTransientConnectionException("x", "08001")));
        assertEquals("lock_timeout", SqlErrors.causeOf(new SQLException("x", "55P03")));
        assertEquals("statement_timeout", SqlErrors.causeOf(new SQLException("x", "57014")));
        assertEquals("deadlock_or_serialization", SqlErrors.causeOf(new SQLException("x", "40P01")));
        assertEquals("connection_lost", SqlErrors.causeOf(new SQLException("x", "08006")));
        assertEquals("connection_lost", SqlErrors.causeOf(new SQLNonTransientConnectionException("x")));
        assertEquals("database_out_of_resources", SqlErrors.causeOf(new SQLException("x", "53300")));
        assertEquals("database_shutdown", SqlErrors.causeOf(new SQLException("x", "57P01")));
        assertEquals("timeout", SqlErrors.causeOf(new SQLTimeoutException("x")));
    }
}
