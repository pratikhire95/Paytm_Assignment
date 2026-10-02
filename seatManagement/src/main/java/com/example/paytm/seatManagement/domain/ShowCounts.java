package com.example.paytm.seatManagement.domain;

import java.util.UUID;

/** Per-show seat counts for the metrics gauges. */
public final class ShowCounts {

    private final UUID showId;
    private final int total;
    private final int available;
    private final int held;
    private final int confirmed;

    public ShowCounts(UUID showId, int total, int available, int held, int confirmed) {
        this.showId = showId;
        this.total = total;
        this.available = available;
        this.held = held;
        this.confirmed = confirmed;
    }

    public UUID showId() {
        return showId;
    }

    public int total() {
        return total;
    }

    public int available() {
        return available;
    }

    public int held() {
        return held;
    }

    public int confirmed() {
        return confirmed;
    }
}
