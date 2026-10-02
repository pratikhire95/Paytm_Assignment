package com.example.paytm.seatManagement.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A reservation as stored. {@code seats} keeps the order in which the client asked for them. */
public final class ReservationRecord {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";

    private final UUID id;
    private final UUID showId;
    private final String userId;
    private final String requestHash;
    private final List<String> seats;
    private final long amountPaise;
    private final String status;
    private final Instant createdAt;
    private final Instant cancelledAt;

    public ReservationRecord(UUID id, UUID showId, String userId, String requestHash, List<String> seats,
            long amountPaise, String status, Instant createdAt, Instant cancelledAt) {
        this.id = id;
        this.showId = showId;
        this.userId = userId;
        this.requestHash = requestHash;
        this.seats = seats;
        this.amountPaise = amountPaise;
        this.status = status;
        this.createdAt = createdAt;
        this.cancelledAt = cancelledAt;
    }

    public UUID id() {
        return id;
    }

    public UUID showId() {
        return showId;
    }

    public String userId() {
        return userId;
    }

    public String requestHash() {
        return requestHash;
    }

    public List<String> seats() {
        return seats;
    }

    public long amountPaise() {
        return amountPaise;
    }

    public String status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Null unless cancelled. */
    public Instant cancelledAt() {
        return cancelledAt;
    }

    public boolean isCancelled() {
        return CANCELLED.equals(status);
    }
}
