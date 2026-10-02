package com.example.paytm.seatManagement.domain;

import java.util.List;

/**
 * A consistent snapshot of one show: per-status counts and (optionally) every seat's status.
 *
 * <p>The counts are derived from the very same result set (or the same single GROUP BY statement) that supplies
 * the seats, so within one snapshot {@code available + held + confirmed} equals the number of seat rows
 * by construction, and that number equals {@code total_seats} because seats are never deleted.
 */
public final class ShowState {

    public static final String[] STATUS_NAMES = {"available", "held", "confirmed"};
    public static final byte AVAILABLE = 0;
    public static final byte HELD = 1;
    public static final byte CONFIRMED = 2;

    private final ShowInfo show;
    private final int available;
    private final int held;
    private final int confirmed;
    private final List<String> labels;
    private final byte[] statusCodes;

    /** @param labels / statusCodes null when per-seat detail was not requested */
    public ShowState(ShowInfo show, int available, int held, int confirmed, List<String> labels, byte[] statusCodes) {
        this.show = show;
        this.available = available;
        this.held = held;
        this.confirmed = confirmed;
        this.labels = labels;
        this.statusCodes = statusCodes;
    }

    public ShowInfo show() {
        return show;
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

    public boolean hasSeats() {
        return labels != null;
    }

    public List<String> labels() {
        return labels;
    }

    public byte[] statusCodes() {
        return statusCodes;
    }

    public static byte codeOf(String status) {
        switch (status) {
            case "available":
                return AVAILABLE;
            case "held":
                return HELD;
            case "confirmed":
                return CONFIRMED;
            default:
                throw new IllegalStateException("unknown seat status in database: " + status);
        }
    }
}
