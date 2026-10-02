package com.example.paytm.seatManagement.domain;

import com.example.paytm.seatManagement.observability.Metrics;
import java.util.Collections;
import java.util.List;

/**
 * Result of a reserve attempt. Declines are ordinary values, not exceptions: a lost race is a normal domain
 * outcome (HTTP 409), and throwing/catching an exception per loser would be wasted work under a stampede.
 */
public final class ReserveOutcome {

    public enum Kind {
        /** A new reservation was created (201). */
        CONFIRMED,
        /** Same key and same request seen before: the original reservation is returned (200). */
        REPLAY,
        /** No reservation created: seat taken or per-user limit (409). */
        DECLINED,
        /** Same key reused for a different request (409). */
        KEY_CONFLICT,
        /** Internal: the idempotency key row already exists; the caller resolves it to REPLAY / KEY_CONFLICT. */
        KEY_TAKEN
    }

    private static final ReserveOutcome KEY_CONFLICT_OUTCOME =
            new ReserveOutcome(Kind.KEY_CONFLICT, null, Metrics.IDEMPOTENCY_KEY_CONFLICT, Collections.<String>emptyList(), 0);
    private static final ReserveOutcome KEY_TAKEN_OUTCOME =
            new ReserveOutcome(Kind.KEY_TAKEN, null, null, Collections.<String>emptyList(), 0);

    private final Kind kind;
    private final ReservationRecord reservation;
    private final String reason;
    private final List<String> seats;
    private final int limit;

    private ReserveOutcome(Kind kind, ReservationRecord reservation, String reason, List<String> seats, int limit) {
        this.kind = kind;
        this.reservation = reservation;
        this.reason = reason;
        this.seats = seats;
        this.limit = limit;
    }

    public static ReserveOutcome confirmed(ReservationRecord r) {
        return new ReserveOutcome(Kind.CONFIRMED, r, null, Collections.<String>emptyList(), 0);
    }

    public static ReserveOutcome replay(ReservationRecord r) {
        return new ReserveOutcome(Kind.REPLAY, r, Metrics.IDEMPOTENT_REPLAY, Collections.<String>emptyList(), 0);
    }

    public static ReserveOutcome seatTaken(List<String> unavailable) {
        return new ReserveOutcome(Kind.DECLINED, null, Metrics.SEAT_TAKEN, unavailable, 0);
    }

    public static ReserveOutcome perUserLimit(int limit) {
        return new ReserveOutcome(Kind.DECLINED, null, Metrics.PER_USER_LIMIT, Collections.<String>emptyList(), limit);
    }

    public static ReserveOutcome keyConflict() {
        return KEY_CONFLICT_OUTCOME;
    }

    static ReserveOutcome keyTaken() {
        return KEY_TAKEN_OUTCOME;
    }

    public Kind kind() {
        return kind;
    }

    /** Present for CONFIRMED and REPLAY. */
    public ReservationRecord reservation() {
        return reservation;
    }

    /** Machine-readable reason for DECLINED / REPLAY / KEY_CONFLICT (also the metrics label). */
    public String reason() {
        return reason;
    }

    /** For seat_taken: the seats that could not be had. */
    public List<String> seats() {
        return seats;
    }

    /** For per_user_limit: the limit that applies. */
    public int limit() {
        return limit;
    }
}
