package com.example.paytm.seatManagement.domain;

import com.example.paytm.seatManagement.common.ApiException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * Shows and read-side views of seat state.
 *
 * <p>Show configuration is immutable after creation, so it is cached in memory (a read per reserve would be
 * pure overhead on the hot path). Seat state is NEVER cached: it is always read from the database.
 */
@Repository
public class ShowRepository {

    private static final int CACHE_LIMIT = 10_000;

    private static final String SQL_INSERT_SHOW =
            "INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?, ?, ?, ?, ?) "
                    + "RETURNING created_at";

    /** ord = position in the request, so the show lists seats in the order the admin supplied. */
    private static final String SQL_INSERT_SEATS =
            "INSERT INTO seats (show_id, label, ord) "
                    + "SELECT ?::uuid, t.label, t.ord FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)";

    private static final String SQL_FIND_SHOW =
            "SELECT id, name, price_paise, per_user_limit, total_seats, created_at FROM shows WHERE id = ?";

    private static final String SQL_STATE_SEATS = "SELECT label, status FROM seats WHERE show_id = ? ORDER BY ord";

    private static final String SQL_STATE_COUNTS = "SELECT status, count(*) FROM seats WHERE show_id = ? GROUP BY status";

    /** One statement => one snapshot for all shows, so per-show numbers are mutually consistent. */
    private static final String SQL_RECENT_COUNTS =
            "SELECT sh.id, sh.total_seats, "
                    + "count(*) FILTER (WHERE s.status = 'available') AS available, "
                    + "count(*) FILTER (WHERE s.status = 'held') AS held, "
                    + "count(*) FILTER (WHERE s.status = 'confirmed') AS confirmed "
                    + "FROM (SELECT id, total_seats FROM shows ORDER BY created_at DESC LIMIT ?) sh "
                    + "JOIN seats s ON s.show_id = sh.id GROUP BY sh.id, sh.total_seats";

    private final DataSource ds;
    private final ConcurrentHashMap<UUID, ShowInfo> cache = new ConcurrentHashMap<>();

    public ShowRepository(DataSource ds) {
        this.ds = ds;
    }

    /** Creates the show and all its seats (all "available") in one transaction. */
    public ShowInfo create(String name, long pricePaise, int perUserLimit, List<String> seats) {
        UUID id = UUID.randomUUID();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            boolean committed = false;
            try {
                Instant createdAt;
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_SHOW)) {
                    ps.setObject(1, id);
                    ps.setString(2, name);
                    ps.setLong(3, pricePaise);
                    ps.setInt(4, perUserLimit);
                    ps.setInt(5, seats.size());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        createdAt = rs.getTimestamp(1).toInstant();
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_SEATS)) {
                    ps.setObject(1, id);
                    ps.setArray(2, c.createArrayOf("text", seats.toArray()));
                    int inserted = ps.executeUpdate();
                    if (inserted != seats.size()) {
                        throw new IllegalStateException("seat insert count mismatch: " + inserted + " != " + seats.size());
                    }
                }
                c.commit();
                committed = true;
                ShowInfo info = new ShowInfo(id, name, pricePaise, perUserLimit, seats.size(), createdAt);
                remember(info);
                return info;
            } finally {
                if (!committed) {
                    Tx.rollbackQuietly(c);
                }
                Tx.autoCommitQuietly(c);
            }
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }
    }

    /** @return the show, or null when it does not exist */
    public ShowInfo find(UUID id) {
        ShowInfo cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_SHOW)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                ShowInfo info = new ShowInfo(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getInt(4),
                        rs.getInt(5), rs.getTimestamp(6).toInstant());
                remember(info);
                return info;
            }
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }
    }

    public ShowInfo require(UUID id) {
        ShowInfo show = find(id);
        if (show == null) {
            throw new ApiException(404, "show_not_found", "No such show");
        }
        return show;
    }

    /**
     * Current state of a show from a single statement (one MVCC snapshot).
     *
     * @param includeSeats true for the per-seat list, false for counts only (cheap; used for polling)
     */
    public ShowState state(UUID id, boolean includeSeats) {
        ShowInfo show = require(id);
        try (Connection c = ds.getConnection()) {
            int[] counts = new int[3];
            if (includeSeats) {
                List<String> labels = new ArrayList<>(show.totalSeats());
                byte[] codes = new byte[show.totalSeats()];
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement(SQL_STATE_SEATS)) {
                    ps.setObject(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (n >= codes.length) {
                                // More seat rows than total_seats: the invariant is broken. Fail loudly, never
                                // paper over it in a response that is supposed to prove the invariant.
                                throw new IllegalStateException("show " + id + " has more seats than total_seats");
                            }
                            labels.add(rs.getString(1));
                            byte code = ShowState.codeOf(rs.getString(2));
                            codes[n++] = code;
                            counts[code]++;
                        }
                    }
                }
                return new ShowState(show, counts[ShowState.AVAILABLE], counts[ShowState.HELD],
                        counts[ShowState.CONFIRMED], labels, n == codes.length ? codes : java.util.Arrays.copyOf(codes, n));
            }
            try (PreparedStatement ps = c.prepareStatement(SQL_STATE_COUNTS)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        counts[ShowState.codeOf(rs.getString(1))] = rs.getInt(2);
                    }
                }
            }
            return new ShowState(show, counts[ShowState.AVAILABLE], counts[ShowState.HELD],
                    counts[ShowState.CONFIRMED], null, null);
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }
    }

    /** Seat counts for the most recently created shows (bounded, to keep metric cardinality in check). */
    public List<ShowCounts> recentCounts(int maxShows) {
        List<ShowCounts> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_RECENT_COUNTS)) {
            ps.setInt(1, maxShows);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ShowCounts(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            throw SqlErrors.map(e);
        }
        return out;
    }

    private void remember(ShowInfo info) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(info.id(), info);
    }
}
