package com.example.paytm.seatManagement.domain;

import java.util.List;

/**
 * Result of the lock-free availability pre-check. Purely advisory (an optimisation that lets losers of a
 * stampede be declined without opening a transaction): the authoritative decision is always the guarded
 * update inside the reservation transaction.
 */
public final class SeatCheck {

    private final List<String> unknown;
    private final List<String> taken;

    public SeatCheck(List<String> unknown, List<String> taken) {
        this.unknown = unknown;
        this.taken = taken;
    }

    /** Requested labels that are not seats of this show. */
    public List<String> unknown() {
        return unknown;
    }

    /** Requested seats that exist but are not available (held or confirmed). */
    public List<String> taken() {
        return taken;
    }
}
