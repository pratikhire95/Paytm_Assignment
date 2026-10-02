package com.example.paytm.seatManagement.domain;

/** Result of a cancel attempt. */
public final class CancelOutcome {

    public enum Kind {
        /** This call released the seats. */
        CANCELLED,
        /** Already cancelled (by an earlier or concurrent call): nothing changed, same body returned. */
        ALREADY_CANCELLED,
        NOT_FOUND,
        /** The reservation exists but belongs to someone else. Nothing changed. */
        FORBIDDEN
    }

    private static final CancelOutcome NOT_FOUND_OUTCOME = new CancelOutcome(Kind.NOT_FOUND, null);
    private static final CancelOutcome FORBIDDEN_OUTCOME = new CancelOutcome(Kind.FORBIDDEN, null);

    private final Kind kind;
    private final ReservationRecord reservation;

    private CancelOutcome(Kind kind, ReservationRecord reservation) {
        this.kind = kind;
        this.reservation = reservation;
    }

    public static CancelOutcome cancelled(ReservationRecord r) {
        return new CancelOutcome(Kind.CANCELLED, r);
    }

    public static CancelOutcome alreadyCancelled(ReservationRecord r) {
        return new CancelOutcome(Kind.ALREADY_CANCELLED, r);
    }

    public static CancelOutcome notFound() {
        return NOT_FOUND_OUTCOME;
    }

    public static CancelOutcome forbidden() {
        return FORBIDDEN_OUTCOME;
    }

    public Kind kind() {
        return kind;
    }

    public ReservationRecord reservation() {
        return reservation;
    }
}
