package com.example.paytm.seatManagement.domain;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Every SQL statement that decides who owns a seat lives in this one class, so the whole concurrency story can
 * be reviewed in one place. All statements are parameterised (no string concatenation of input anywhere).
 *
 * <p>The methods take the caller's {@link Connection}: transaction boundaries belong to
 * {@link ReservationService}, which decides the order in which these statements run. That order is the
 * lock order, and it is the same everywhere:
 *
 * <pre>
 *   reserve:  1. reservations row (idempotency key)   2. user_show_holds row   3. seat rows, ascending ord
 *   cancel:   1. user_show_holds row                  2. reservations row      3. seat rows, ascending ord
 * </pre>
 *
 * Every transaction touching the same user's counter row takes it before any seat row, and seat rows are always
 * taken in ascending {@code ord}; there is therefore no cycle in the wait-for graph, i.e. no deadlock.
 *
 * <p>Both paths lock seats with an explicit {@code SELECT ... ORDER BY ord FOR UPDATE}. A bare {@code UPDATE}
 * would lock rows in index or heap order instead, which is not the global order.
 */
@Repository
public class ReservationRepository {

    // ---------------------------------------------------------------------------------------------- reserve

    /**
     * Step 1 - the idempotency row. The UNIQUE (user_id, idempotency_key) index makes "one key, one reservation" a
     * database invariant. ON CONFLICT DO NOTHING (instead of catching a unique violation) keeps the transaction
     * usable; if a concurrent twin has the key in flight, this statement WAITS for that transaction to finish,
     * which is exactly the serialisation we want.
     */
    private static final String SQL_INSERT_RESERVATION =
            "INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats, amount_paise, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'confirmed') "
                    + "ON CONFLICT (user_id, idempotency_key) DO NOTHING "
                    + "RETURNING created_at";

    /**
     * Step 2 - the per-user limit as a guarded upsert. First request: inserts the counter row. Later requests:
     * the DO UPDATE only fires while {@code count + n <= limit}; otherwise zero rows are affected => decline.
     * The row lock taken here is held to the end of the transaction, so two concurrent requests of the same user
     * cannot both read "3 of 4 used" and both succeed: the second waits, then sees 4.
     */
    private static final String SQL_QUOTA_ACQUIRE =
            "INSERT INTO user_show_holds AS h (show_id, user_id, seat_count) VALUES (?, ?, ?) "
                    + "ON CONFLICT (show_id, user_id) DO UPDATE SET seat_count = h.seat_count + EXCLUDED.seat_count "
                    + "WHERE h.seat_count + EXCLUDED.seat_count <= ?";

    /**
     * Step 3a - lock the wanted seats that are still available, in ascending ord (deterministic order =
     * no deadlock). Under READ COMMITTED, a row that another transaction changed while we waited for its lock
     * is re-checked against {@code status = 'available'}; if it is no longer available it silently drops out
     * of the result, so fewer rows come back than were asked for => decline.
     */
    private static final String SQL_LOCK_SEATS =
            "SELECT label FROM seats WHERE show_id = ? AND label = ANY (?) AND status = 'available' "
                    + "ORDER BY ord FOR UPDATE";

    /** Step 3b - flip the seats we hold locks on. The status guard is repeated as defence in depth. */
    private static final String SQL_CLAIM_SEATS =
            "UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ? "
                    + "WHERE show_id = ? AND label = ANY (?) AND status = 'available'";

    // ---------------------------------------------------------------------------------------------- reads

    private static final String RESERVATION_COLUMNS =
            "id, show_id, user_id, request_hash, seats, amount_paise, status, created_at, cancelled_at";

    private static final String SQL_FIND_BY_KEY =
            "SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE user_id = ? AND idempotency_key = ?";

    private static final String SQL_FIND_BY_ID = "SELECT " + RESERVATION_COLUMNS + " FROM reservations WHERE id = ?";

    /** Lock-free peek used only to decline losers cheaply. NOT the decision (see SeatCheck). */
    private static final String SQL_PRECHECK = "SELECT label, status FROM seats WHERE show_id = ? AND label = ANY (?)";

    // ---------------------------------------------------------------------------------------------- cancel

    private static final String SQL_LOCK_QUOTA =
            "SELECT seat_count FROM user_show_holds WHERE show_id = ? AND user_id = ? FOR UPDATE";

    /** Only a CONFIRMED reservation owned by the caller transitions; the loser of a duplicate cancel gets 0 rows. */
    private static final String SQL_MARK_CANCELLED =
            "UPDATE reservations SET status = 'cancelled', cancelled_at = now() "
                    + "WHERE id = ? AND user_id = ? AND status = 'confirmed' "
                    + "RETURNING " + RESERVATION_COLUMNS;

    /**
     * Cancel, step 3a - lock the seats this reservation owns in ascending ord, exactly like reserve does. Needed
     * because PostgreSQL locks the newest version of a row before it re-checks the WHERE clause, so a reserve whose
     * snapshot is a moment old can briefly hold a lock on a seat that has just become 'confirmed'. As long as both
     * sides take their seat locks in the same order, they still cannot form a cycle.
     */
    private static final String SQL_LOCK_OWNED_SEATS =
            "SELECT label FROM seats WHERE show_id = ? AND label = ANY (?) AND reservation_id = ? "
                    + "ORDER BY ord FOR UPDATE";

    /** Guarded by reservation_id: a seat that now belongs to someone else can never be released by a stale cancel. */
    private static final String SQL_RELEASE_SEATS =
            "UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL "
                    + "WHERE show_id = ? AND label = ANY (?) AND reservation_id = ?";

    private static final String SQL_RELEASE_QUOTA =
            "UPDATE user_show_holds SET seat_count = GREATEST(seat_count - ?, 0) WHERE show_id = ? AND user_id = ?";

    // ================================================================================================ reserve

    /** @return the DB timestamp of the new row, or null when the (user, key) pair already exists. */
    public Instant insertReservation(Connection c, UUID id, UUID showId, String userId, String key, String hash,
            List<String> seats, long amountPaise) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_RESERVATION)) {
            ps.setObject(1, id);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            ps.setString(4, key);
            ps.setString(5, hash);
            ps.setArray(6, c.createArrayOf("text", seats.toArray()));
            ps.setLong(7, amountPaise);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1).toInstant() : null;
            }
        }
    }

    /** @return true when the counter was incremented (limit not exceeded) */
    public boolean tryAcquireQuota(Connection c, UUID showId, String userId, int n, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_QUOTA_ACQUIRE)) {
            ps.setObject(1, showId);
            ps.setString(2, userId);
            ps.setInt(3, n);
            ps.setInt(4, limit);
            return ps.executeUpdate() == 1;
        }
    }

    /** @return labels that are now locked by this transaction and are available (subset of {@code sortedLabels}) */
    public List<String> lockAvailableSeats(Connection c, UUID showId, List<String> sortedLabels) throws SQLException {
        List<String> locked = new ArrayList<>(sortedLabels.size());
        try (PreparedStatement ps = c.prepareStatement(SQL_LOCK_SEATS)) {
            ps.setObject(1, showId);
            ps.setArray(2, c.createArrayOf("text", sortedLabels.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    locked.add(rs.getString(1));
                }
            }
        }
        return locked;
    }

    /** @return number of seats flipped to confirmed (must equal the number requested) */
    public int claimSeats(Connection c, UUID showId, List<String> sortedLabels, UUID reservationId, String userId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_CLAIM_SEATS)) {
            ps.setObject(1, reservationId);
            ps.setString(2, userId);
            ps.setObject(3, showId);
            ps.setArray(4, c.createArrayOf("text", sortedLabels.toArray()));
            return ps.executeUpdate();
        }
    }

    // ================================================================================================ reads

    public ReservationRecord findByKey(Connection c, String userId, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_KEY)) {
            ps.setString(1, userId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    public ReservationRecord findById(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_ID)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** Classifies the requested labels as unknown (not a seat of this show) or taken (exists, not available). */
    public SeatCheck precheck(Connection c, UUID showId, List<String> labels) throws SQLException {
        Map<String, String> found = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_PRECHECK)) {
            ps.setObject(1, showId);
            ps.setArray(2, c.createArrayOf("text", labels.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        List<String> unknown = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        for (String label : labels) {
            String status = found.get(label);
            if (status == null) {
                unknown.add(label);
            } else if (!"available".equals(status)) {
                taken.add(label);
            }
        }
        return new SeatCheck(unknown, taken);
    }

    // ================================================================================================ cancel

    public void lockQuota(Connection c, UUID showId, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_LOCK_QUOTA)) {
            ps.setObject(1, showId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next(); // row normally exists; if not, the later guarded statements simply affect 0 rows
            }
        }
    }

    /** @return the cancelled reservation, or null when it was not (or no longer) a confirmed reservation of this user */
    public ReservationRecord markCancelled(Connection c, UUID id, String userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_MARK_CANCELLED)) {
            ps.setObject(1, id);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** @return how many of the given seats are now row-locked by this transaction as owned by the reservation */
    public int lockOwnedSeats(Connection c, UUID showId, List<String> labels, UUID reservationId)
            throws SQLException {
        int locked = 0;
        try (PreparedStatement ps = c.prepareStatement(SQL_LOCK_OWNED_SEATS)) {
            ps.setObject(1, showId);
            ps.setArray(2, c.createArrayOf("text", labels.toArray()));
            ps.setObject(3, reservationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    locked++;
                }
            }
        }
        return locked;
    }

    /** @return number of seats released (must equal the reservation's seat count) */
    public int releaseSeats(Connection c, UUID showId, List<String> sortedLabels, UUID reservationId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_RELEASE_SEATS)) {
            ps.setObject(1, showId);
            ps.setArray(2, c.createArrayOf("text", sortedLabels.toArray()));
            ps.setObject(3, reservationId);
            return ps.executeUpdate();
        }
    }

    public int releaseQuota(Connection c, UUID showId, String userId, int n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SQL_RELEASE_QUOTA)) {
            ps.setInt(1, n);
            ps.setObject(2, showId);
            ps.setString(3, userId);
            return ps.executeUpdate();
        }
    }

    // ================================================================================================ mapping

    private static ReservationRecord read(ResultSet rs) throws SQLException {
        Array arr = rs.getArray(5);
        List<String> seats = Arrays.asList((String[]) arr.getArray());
        Timestamp cancelled = rs.getTimestamp(9);
        return new ReservationRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), seats, rs.getLong(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }
}
