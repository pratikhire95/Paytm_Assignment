package com.example.paytm.seatManagement.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable show configuration. Never changes after creation, which is why it can be cached safely. */
public final class ShowInfo {

    private final UUID id;
    private final String name;
    private final long pricePaise;
    private final int perUserLimit;
    private final int totalSeats;
    private final Instant createdAt;

    public ShowInfo(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.pricePaise = pricePaise;
        this.perUserLimit = perUserLimit;
        this.totalSeats = totalSeats;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Integer minor units (paise). Never a float. */
    public long pricePaise() {
        return pricePaise;
    }

    public int perUserLimit() {
        return perUserLimit;
    }

    public int totalSeats() {
        return totalSeats;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
