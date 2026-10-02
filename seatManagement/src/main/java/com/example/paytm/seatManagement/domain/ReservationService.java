package com.example.paytm.seatManagement.domain;

import com.example.paytm.seatManagement.common.ApiException;
import com.example.paytm.seatManagement.observability.Metrics;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The reservation engine: where "who gets the seat" is decided.
 *
 * <h2>The atomic decision</h2>
 * One database transaction (READ COMMITTED) per reserve request, whose statements run in a fixed order:
 * <ol>
 *   <li><b>Idempotency row</b> - {@code INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING}. Zero rows
 *       means the key already exists (or a twin request holds it right now - then this statement blocks until the
 *       twin finishes). Either way the caller resolves it to "replay the original" or "key reused for a
 *       different request".</li>
 *   <li><b>Per-user limit</b> - guarded upsert on {@code user_show_holds}; also serialises one user's concurrent
 *       requests on a row lock.</li>
 *   <li><b>Seats</b> - {@code SELECT ... WHERE status='available' ORDER BY ord FOR UPDATE} then a guarded
 *       {@code UPDATE}. The row lock plus the status guard is what makes "exactly one winner per seat" true:
 *       a loser blocks on the lock, then re-evaluates the guard against the winner's committed row, finds the
 *       seat no longer available and gets fewer rows than it asked for.</li>
 * </ol>
 * If any step says no, the transaction rolls back and nothing at all has changed (all-or-nothing for multi-seat
 * requests). Locks are always taken in the same order (see {@link ReservationRepository}), so there are no
 * deadlocks; if the database reports one anyway (40P01) or a serialisation failure (40001) the transaction is
 * simply re-run, never surfaced as a 500.
 *
 * <p>Before opening a transaction, a lock-free peek declines requests for seats that are visibly taken. That is an
 * optimisation only (it turns the ~95% of a stampede that loses into cheap reads); correctness never depends on it.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_ATTEMPTS = 4;

    private final DataSource ds;
    private final ShowRepository shows;
    private final ReservationRepository repo;
    private final Metrics metrics;

    public ReservationService(DataSource ds, ShowRepository shows, ReservationRepository repo, Metrics metrics) {
        this.ds = ds;
        this.shows = shows;
        this.repo = repo;
        this.metrics = metrics;
    }

    // ================================================================================================ reserve

    /**
     * @param seats the labels in the order the client sent them (already validated: non-empty, unique, well-formed)
     */
    public ReserveOutcome reserve(String userId, UUID showId, List<String> seats, String idempotencyKey) {
        ShowInfo show = shows.require(showId);
        List<String> sorted = new ArrayList<>(seats);
        Collections.sort(sorted);
        String hash = RequestFingerprint.of(showId, sorted);

        ReserveOutcome outcome;
        try {
            outcome = attemptReserve(show, userId, seats, sorted, idempotencyKey, hash);
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }

        switch (outcome.kind()) {
            case CONFIRMED:
                metrics.reservationConfirmed();
                break;
            case REPLAY:
            case DECLINED:
            case KEY_CONFLICT:
                metrics.reservationDeclined(outcome.reason());
                break;
            default:
                break;
        }
        return outcome;
    }

    private ReserveOutcome attemptReserve(ShowInfo show, String userId, List<String> seats, List<String> sorted,
            String key, String hash) throws SQLException {
        try (Connection c = ds.getConnection()) {
            // 1. A key that already produced a reservation is consumed for good: replay it (same request) or
            //    reject it (different request). Checked first so a retry of a successful booking is never
            //    mistaken for "seat taken" just because the seat is now (rightly) ours.
            ReservationRecord existing = repo.findByKey(c, userId, key);
            if (existing != null) {
                return resolveExistingKey(existing, hash);
            }

            // 2. More seats than the limit allows can never succeed.
            if (seats.size() > show.perUserLimit()) {
                return ReserveOutcome.perUserLimit(show.perUserLimit());
            }

            // 3. Cheap lock-free decline for visibly-taken seats (optimisation only).
            SeatCheck check = repo.precheck(c, show.id(), sorted);
            if (!check.unknown().isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("seats", check.unknown());
                throw new ApiException(422, "unknown_seat", "One or more seats do not exist in this show", details);
            }
            if (!check.taken().isEmpty()) {
                // The seats may be "taken" by a twin of THIS request (a retry racing the original): the twin can
                // commit after the key lookup in step 1 and before this peek. Look the key up once more so such a
                // retry gets the original reservation back (200) instead of a bogus "seat taken" (409).
                ReservationRecord twin = repo.findByKey(c, userId, key);
                if (twin != null) {
                    return resolveExistingKey(twin, hash);
                }
                return ReserveOutcome.seatTaken(check.taken());
            }

            // 4. The authoritative decision.
            for (int attempt = 1;; attempt++) {
                try {
                    ReserveOutcome o = reserveInTransaction(c, show, userId, seats, sorted, key, hash);
                    if (o.kind() != ReserveOutcome.Kind.KEY_TAKEN) {
                        return o;
                    }
                    // A concurrent request with the same key committed first (we waited for it): resolve it.
                    ReservationRecord now = repo.findByKey(c, userId, key);
                    if (now != null) {
                        return resolveExistingKey(now, hash);
                    }
                    // The twin rolled back between our two statements: simply go round again.
                } catch (SQLException e) {
                    if (!SqlErrors.isRetryable(e)) {
                        throw e;
                    }
                    log.warn("reserve transaction retried after sqlstate={} (attempt {})", e.getSQLState(), attempt);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw new ApiException(503, "service_unavailable",
                            "The service is busy; please retry shortly");
                }
                backoff(attempt);
            }
        }
    }

    private ReserveOutcome reserveInTransaction(Connection c, ShowInfo show, String userId, List<String> seats,
            List<String> sorted, String key, String hash) throws SQLException {
        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());

        c.setAutoCommit(false);
        boolean committed = false;
        try {
            // (1) idempotency row - first, so twins serialise here before they can touch anything else
            Instant createdAt = repo.insertReservation(c, reservationId, show.id(), userId, key, hash, seats, amount);
            if (createdAt == null) {
                return ReserveOutcome.keyTaken();
            }

            // (2) per-user limit
            if (!repo.tryAcquireQuota(c, show.id(), userId, seats.size(), show.perUserLimit())) {
                return ReserveOutcome.perUserLimit(show.perUserLimit());
            }

            // (3) seats: lock in ascending order, all-or-nothing
            List<String> locked = repo.lockAvailableSeats(c, show.id(), sorted);
            if (locked.size() != sorted.size()) {
                return ReserveOutcome.seatTaken(missing(sorted, locked));
            }
            int claimed = repo.claimSeats(c, show.id(), sorted, reservationId, userId);
            if (claimed != sorted.size()) {
                // Cannot happen while we hold the row locks; if it ever does, abort rather than half-book.
                throw new IllegalStateException("claimed " + claimed + " of " + sorted.size() + " locked seats");
            }

            c.commit();
            committed = true;
            return ReserveOutcome.confirmed(new ReservationRecord(reservationId, show.id(), userId, hash, seats,
                    amount, ReservationRecord.CONFIRMED, createdAt, null));
        } finally {
            if (!committed) {
                Tx.rollbackQuietly(c); // a decline leaves no trace: idempotency row and counter bump vanish too
            }
            Tx.autoCommitQuietly(c);
        }
    }

    private static ReserveOutcome resolveExistingKey(ReservationRecord existing, String hash) {
        if (existing.requestHash().equals(hash)) {
            return ReserveOutcome.replay(existing);
        }
        return ReserveOutcome.keyConflict();
    }

    private static List<String> missing(List<String> wanted, List<String> got) {
        Set<String> have = new HashSet<>(got);
        List<String> out = new ArrayList<>();
        for (String s : wanted) {
            if (!have.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    // ================================================================================================ cancel

    /** Owner-only, idempotent release of a reservation's seats. */
    public CancelOutcome cancel(String userId, UUID reservationId) {
        CancelOutcome outcome;
        try {
            outcome = attemptCancel(userId, reservationId);
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }
        if (outcome.kind() == CancelOutcome.Kind.CANCELLED) {
            metrics.reservationCancelled();
        }
        return outcome;
    }

    private CancelOutcome attemptCancel(String userId, UUID reservationId) throws SQLException {
        try (Connection c = ds.getConnection()) {
            ReservationRecord rec = repo.findById(c, reservationId);
            if (rec == null) {
                return CancelOutcome.notFound();
            }
            if (!rec.userId().equals(userId)) {
                return CancelOutcome.forbidden(); // identity comes from the token; nothing is touched
            }
            if (rec.isCancelled()) {
                return CancelOutcome.alreadyCancelled(rec);
            }
            for (int attempt = 1;; attempt++) {
                try {
                    return cancelInTransaction(c, rec, userId);
                } catch (SQLException e) {
                    if (!SqlErrors.isRetryable(e) || attempt >= MAX_ATTEMPTS) {
                        throw e;
                    }
                    log.warn("cancel transaction retried after sqlstate={} (attempt {})", e.getSQLState(), attempt);
                }
                backoff(attempt);
            }
        }
    }

    private CancelOutcome cancelInTransaction(Connection c, ReservationRecord rec, String userId) throws SQLException {
        c.setAutoCommit(false);
        boolean committed = false;
        try {
            // Same lock order as reserve: the user's counter row first, then the reservation, then the seats.
            repo.lockQuota(c, rec.showId(), userId);

            ReservationRecord cancelled = repo.markCancelled(c, rec.id(), userId);
            if (cancelled == null) {
                // Lost a race with another cancel of the same reservation: it already did the work.
                ReservationRecord now = repo.findById(c, rec.id());
                return CancelOutcome.alreadyCancelled(now != null ? now : rec);
            }

            List<String> sorted = new ArrayList<>(cancelled.seats());
            Collections.sort(sorted);
            // Seat locks in ascending ord, the same global order reserve uses (a bare UPDATE would lock in index order).
            repo.lockOwnedSeats(c, rec.showId(), sorted, rec.id());
            // Guarded by reservation_id: only seats still owned by THIS reservation are released, so a stale or
            // duplicate cancel can never free a seat that has meanwhile been sold to someone else.
            int released = repo.releaseSeats(c, rec.showId(), sorted, rec.id());
            if (released != sorted.size()) {
                throw new IllegalStateException("released " + released + " of " + sorted.size() + " seats");
            }
            repo.releaseQuota(c, rec.showId(), userId, sorted.size());

            c.commit();
            committed = true;
            return CancelOutcome.cancelled(cancelled);
        } finally {
            if (!committed) {
                Tx.rollbackQuietly(c);
            }
            Tx.autoCommitQuietly(c);
        }
    }

    // ================================================================================================ util

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 25L * attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "service_unavailable", "The service is shutting down; please retry");
        }
    }
}
