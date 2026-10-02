package com.example.paytm.seatManagement.domain;

import java.sql.Connection;
import java.sql.SQLException;

/** Cleanup helpers that never throw, so they are safe inside {@code finally} without masking the real error. */
final class Tx {

    private Tx() {
    }

    static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is probably dead; the pool will evict it
        }
    }

    /** Restores autocommit for the pool. Call AFTER rollback/commit: switching on mid-transaction would commit. */
    static void autoCommitQuietly(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ignored) {
            // same as above
        }
    }
}
