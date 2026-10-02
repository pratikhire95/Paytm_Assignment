package com.example.paytm.seatManagement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.paytm.seatManagement.observability.Metrics;
import com.example.paytm.seatManagement.support.TestDb;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The correctness bar, executed against a real PostgreSQL (skipped unless TEST_DATABASE_URL is set).
 *
 * <p>These tests call the {@link ReservationService} directly from many threads, which is the same code path the
 * HTTP layer uses; the burst script exercises the full HTTP stack afterwards. Every test builds its own show, then
 * checks, besides its own expectations, the same set of database-level invariants ({@link #assertConsistent}).
 */
@EnabledIfEnvironmentVariable(named = TestDb.ENV_URL, matches = ".+")
class ReservationConcurrencyTest {

    private static HikariDataSource ds;
    private static ShowRepository shows;
    private static ReservationService service;
    private static Metrics metrics;

    @BeforeAll
    static void start() {
        ds = TestDb.migratedPool(TestDb.settings(TestDb.env()));
        metrics = new Metrics();
        shows = new ShowRepository(ds);
        service = new ReservationService(ds, shows, new ReservationRepository(), metrics);
    }

    @AfterAll
    static void stop() {
        if (ds != null) {
            ds.close();
        }
    }

    // ================================================================================== the headline guarantees

    @Test
    void hotSeatHasExactlyOneWinnerAndEveryoneElseIsDeclinedCleanly() throws Exception {
        ShowInfo show = newShow(5, 4);
        int contenders = 300;
        long confirmedBefore = metrics.confirmedCount();
        long takenBefore = metrics.declinedCount(Metrics.SEAT_TAKEN);

        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            final String user = "hot-" + i;
            tasks.add(() -> service.reserve(user, show.id(), Arrays.asList("S001"), uniqueKey()));
        }
        Tally t = Tally.of(runConcurrently(120, tasks)); // any exception (a would-be 5xx) fails the test here

        assertEquals(1, t.confirmed, t.toString());
        assertEquals(contenders - 1, t.seatTaken, t.toString());
        assertEquals(0, t.other, t.toString());
        assertEquals(1, metrics.confirmedCount() - confirmedBefore);
        assertEquals(contenders - 1, metrics.declinedCount(Metrics.SEAT_TAKEN) - takenBefore);

        ShowState st = shows.state(show.id(), false);
        assertEquals(1, st.confirmed());
        assertEquals(4, st.available());
        assertConsistent(show);
    }

    @Test
    void manyHotSeatsEachGoToExactlyOneBuyerAndTheInvariantHoldsThroughout() throws Exception {
        int seats = 20;
        int contendersPerSeat = 20;
        ShowInfo show = newShow(seats, 4);
        List<String> labels = labels(seats);

        // A watcher polls the show for the whole run: available + held + confirmed must equal total in EVERY snapshot.
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger snapshots = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        AtomicReference<Throwable> watcherFailure = new AtomicReference<>();
        Thread watcher = new Thread(() -> {
            try {
                while (!done.get()) {
                    ShowState st = shows.state(show.id(), true);
                    snapshots.incrementAndGet();
                    if (st.available() + st.held() + st.confirmed() != show.totalSeats()
                            || st.labels().size() != show.totalSeats()) {
                        violations.incrementAndGet();
                    }
                }
            } catch (Throwable e) {
                watcherFailure.set(e);
            }
        }, "invariant-watcher");
        watcher.start();

        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < seats * contendersPerSeat; i++) {
            final String user = "many-" + i;
            final String seat = labels.get(i % seats);
            tasks.add(() -> service.reserve(user, show.id(), Arrays.asList(seat), uniqueKey()));
        }
        Tally t;
        try {
            t = Tally.of(runConcurrently(150, tasks));
        } finally {
            done.set(true);
            watcher.join(30_000);
        }

        assertNull(watcherFailure.get(), "the invariant watcher failed");
        assertTrue(snapshots.get() > 0, "the watcher never took a snapshot");
        assertEquals(0, violations.get(), "reconciliation invariant violated in a live snapshot");
        assertEquals(seats, t.confirmed, t.toString());
        assertEquals(seats * contendersPerSeat - seats, t.seatTaken, t.toString());
        assertEquals(0, t.other, t.toString());
        ShowState st = shows.state(show.id(), false);
        assertEquals(seats, st.confirmed());
        assertEquals(0, st.available());
        assertConsistent(show);
    }

    @Test
    void overlappingMultiSeatRequestsInOppositeOrdersNeverDeadlockAndAreAllOrNothing() throws Exception {
        ShowInfo show = newShow(6, 4);
        List<List<String>> variants = Arrays.asList(
                Arrays.asList("S001", "S002", "S003"),
                Arrays.asList("S003", "S002", "S001"),
                Arrays.asList("S002", "S004"),
                Arrays.asList("S004", "S002"),
                Arrays.asList("S005", "S006", "S001"),
                Arrays.asList("S006", "S005"));

        int users = 240;
        List<String> userIds = new ArrayList<>();
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            final String user = "multi-" + i;
            final List<String> wanted = variants.get(i % variants.size());
            userIds.add(user);
            tasks.add(() -> service.reserve(user, show.id(), wanted, uniqueKey()));
        }
        List<ReserveOutcome> outcomes = runConcurrently(120, tasks); // a deadlock victim would surface as an exception

        Map<String, Integer> expectedOwned = new HashMap<>();
        int confirmedSeats = 0;
        for (int i = 0; i < users; i++) {
            ReserveOutcome o = outcomes.get(i);
            if (o.kind() == ReserveOutcome.Kind.CONFIRMED) {
                expectedOwned.put(userIds.get(i), o.reservation().seats().size());
                confirmedSeats += o.reservation().seats().size();
            } else {
                assertEquals(ReserveOutcome.Kind.DECLINED, o.kind());
                assertEquals(Metrics.SEAT_TAKEN, o.reason());
            }
        }
        assertTrue(confirmedSeats > 0 && confirmedSeats <= 6, "confirmed seats: " + confirmedSeats);
        // All-or-nothing: a user owns either all the seats they asked for or none at all.
        assertEquals(expectedOwned, ownedSeatsByUser(show));
        assertEquals(confirmedSeats, shows.state(show.id(), false).confirmed());
        assertConsistent(show);
    }

    @Test
    void perUserLimitHoldsWhenOneUserFiresRequestsInParallel() throws Exception {
        ShowInfo show = newShow(12, 4);
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final String seat = String.format("S%03d", i);
            tasks.add(() -> service.reserve("greedy", show.id(), Arrays.asList(seat), uniqueKey()));
        }
        Tally t = Tally.of(runConcurrently(10, tasks));

        assertEquals(4, t.confirmed, t.toString());
        assertEquals(6, t.limit, t.toString());
        assertEquals(0, t.other, t.toString());
        assertEquals(4, shows.state(show.id(), false).confirmed());
        assertConsistent(show);
    }

    @Test
    void sameIdempotencyKeyInParallelCreatesExactlyOneReservation() throws Exception {
        ShowInfo show = newShow(4, 4);
        String key = uniqueKey();
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> service.reserve("retrying", show.id(), Arrays.asList("S001", "S002"), key));
        }
        List<ReserveOutcome> outcomes = runConcurrently(30, tasks);
        Tally t = Tally.of(outcomes);

        assertEquals(1, t.confirmed, t.toString());
        assertEquals(29, t.replay, t.toString());
        assertEquals(0, t.other + t.seatTaken + t.limit + t.keyConflict, t.toString());
        Set<UUID> ids = new HashSet<>();
        for (ReserveOutcome o : outcomes) {
            ids.add(o.reservation().id());
        }
        assertEquals(1, ids.size(), "every retry must see the same reservation");
        assertEquals(2, shows.state(show.id(), false).confirmed());
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE show_id = ?", show.id()));
        assertConsistent(show);
    }

    // ==================================================================================== idempotency semantics

    @Test
    void sameKeyWithDifferentSeatsIsRejectedAndBooksNothing() {
        ShowInfo show = newShow(6, 4);
        String key = uniqueKey();
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("alice", show.id(), Arrays.asList("S001"), key).kind());

        ReserveOutcome conflict = service.reserve("alice", show.id(), Arrays.asList("S002"), key);
        assertEquals(ReserveOutcome.Kind.KEY_CONFLICT, conflict.kind());
        assertEquals(Metrics.IDEMPOTENCY_KEY_CONFLICT, conflict.reason());

        ShowState st = shows.state(show.id(), false);
        assertEquals(1, st.confirmed());
        assertEquals(5, st.available());
    }

    @Test
    void replayIgnoresSeatOrderAndIsScopedToTheUser() throws Exception {
        ShowInfo show = newShow(6, 4);
        String key = uniqueKey();
        ReserveOutcome first = service.reserve("alice", show.id(), Arrays.asList("S003", "S004"), key);
        assertEquals(ReserveOutcome.Kind.CONFIRMED, first.kind());

        ReserveOutcome again = service.reserve("alice", show.id(), Arrays.asList("S004", "S003"), key);
        assertEquals(ReserveOutcome.Kind.REPLAY, again.kind());
        assertEquals(first.reservation().id(), again.reservation().id());

        // The same key string used by somebody else is a different key: it must not leak alice's reservation.
        ReserveOutcome other = service.reserve("bob", show.id(), Arrays.asList("S005"), key);
        assertEquals(ReserveOutcome.Kind.CONFIRMED, other.kind());
        assertTrue(!other.reservation().id().equals(first.reservation().id()));
        assertConsistent(show);
    }

    @Test
    void aDeclinedRequestLeavesNoTraceSoTheKeyCanBeRetried() throws Exception {
        ShowInfo show = newShow(3, 4);
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("alice", show.id(), Arrays.asList("S001"), uniqueKey()).kind());

        String key = uniqueKey();
        ReserveOutcome declined = service.reserve("bob", show.id(), Arrays.asList("S001"), key);
        assertEquals(ReserveOutcome.Kind.DECLINED, declined.kind());
        assertEquals(0, count("SELECT count(*) FROM reservations WHERE show_id = ? AND user_id = 'bob'", show.id()));

        // Bob retries the same key for a free seat: that is a fresh request, not a conflict.
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("bob", show.id(), Arrays.asList("S002"), key).kind());
    }

    // ============================================================================================ per-user limit

    @Test
    void aRequestLargerThanTheLimitIsDeclinedWholeNotTrimmed() {
        ShowInfo show = newShow(10, 4);
        ReserveOutcome o = service.reserve("alice", show.id(),
                Arrays.asList("S001", "S002", "S003", "S004", "S005"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.DECLINED, o.kind());
        assertEquals(Metrics.PER_USER_LIMIT, o.reason());
        assertEquals(4, o.limit());
        assertEquals(10, shows.state(show.id(), false).available());
    }

    @Test
    void aRequestThatWouldCrossTheLimitBooksNoneOfItsSeats() throws Exception {
        ShowInfo show = newShow(10, 4);
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("alice", show.id(), Arrays.asList("S001", "S002", "S003"), uniqueKey()).kind());

        ReserveOutcome over = service.reserve("alice", show.id(), Arrays.asList("S004", "S005"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.DECLINED, over.kind());
        assertEquals(Metrics.PER_USER_LIMIT, over.reason());
        ShowState st = shows.state(show.id(), true);
        assertEquals(3, st.confirmed());
        assertEquals("available", ShowState.STATUS_NAMES[st.statusCodes()[3]]);
        assertEquals("available", ShowState.STATUS_NAMES[st.statusCodes()[4]]);

        // ... and the one seat that still fits is bookable, so the declined attempt did not eat any quota.
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("alice", show.id(), Arrays.asList("S004"), uniqueKey()).kind());
        assertEquals(ReserveOutcome.Kind.DECLINED,
                service.reserve("alice", show.id(), Arrays.asList("S006"), uniqueKey()).kind());
        assertConsistent(show);
    }

    @Test
    void unknownSeatsAreRejectedNotTreatedAsTaken() {
        ShowInfo show = newShow(3, 4);
        try {
            service.reserve("alice", show.id(), Arrays.asList("S001", "NOPE"), uniqueKey());
            fail("expected an unknown_seat error");
        } catch (com.example.paytm.seatManagement.common.ApiException e) {
            assertEquals(422, e.getStatus());
            assertEquals("unknown_seat", e.getCode());
        }
        assertEquals(3, shows.state(show.id(), false).available());
    }

    // ================================================================================================== cancelling

    @Test
    void cancelFreesTheSeatsAndTheQuota() throws Exception {
        ShowInfo show = newShow(8, 2);
        ReserveOutcome first = service.reserve("alice", show.id(), Arrays.asList("S001", "S002"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.CONFIRMED, first.kind());
        // at the limit now
        assertEquals(ReserveOutcome.Kind.DECLINED,
                service.reserve("alice", show.id(), Arrays.asList("S003"), uniqueKey()).kind());
        // somebody else wants alice's seat: taken
        assertEquals(ReserveOutcome.Kind.DECLINED,
                service.reserve("bob", show.id(), Arrays.asList("S001"), uniqueKey()).kind());

        CancelOutcome cancelled = service.cancel("alice", first.reservation().id());
        assertEquals(CancelOutcome.Kind.CANCELLED, cancelled.kind());
        assertEquals(ReservationRecord.CANCELLED, cancelled.reservation().status());
        assertNotNull(cancelled.reservation().cancelledAt());
        assertEquals(8, shows.state(show.id(), false).available());

        // the seat can be sold again, and alice has her quota back
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("bob", show.id(), Arrays.asList("S001"), uniqueKey()).kind());
        assertEquals(ReserveOutcome.Kind.CONFIRMED,
                service.reserve("alice", show.id(), Arrays.asList("S003", "S004"), uniqueKey()).kind());
        assertConsistent(show);
    }

    @Test
    void onlyTheOwnerCanCancelAndUnknownReservationsAreNotFound() {
        ShowInfo show = newShow(3, 4);
        ReserveOutcome r = service.reserve("alice", show.id(), Arrays.asList("S001"), uniqueKey());

        assertEquals(CancelOutcome.Kind.FORBIDDEN, service.cancel("mallory", r.reservation().id()).kind());
        assertEquals(CancelOutcome.Kind.NOT_FOUND, service.cancel("alice", UUID.randomUUID()).kind());
        // nothing moved
        ShowState st = shows.state(show.id(), false);
        assertEquals(1, st.confirmed());
        assertEquals(CancelOutcome.Kind.CANCELLED, service.cancel("alice", r.reservation().id()).kind());
    }

    @Test
    void aStaleCancelCannotFreeASeatThatWasSoldToSomeoneElse() throws Exception {
        ShowInfo show = newShow(3, 4);
        ReserveOutcome a = service.reserve("alice", show.id(), Arrays.asList("S001"), uniqueKey());
        assertEquals(CancelOutcome.Kind.CANCELLED, service.cancel("alice", a.reservation().id()).kind());
        ReserveOutcome b = service.reserve("bob", show.id(), Arrays.asList("S001"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.CONFIRMED, b.kind());

        // alice (or her flaky client) cancels again, long after the seat moved on
        CancelOutcome again = service.cancel("alice", a.reservation().id());
        assertEquals(CancelOutcome.Kind.ALREADY_CANCELLED, again.kind());

        ShowState st = shows.state(show.id(), true);
        assertEquals(1, st.confirmed());
        assertEquals("confirmed", ShowState.STATUS_NAMES[st.statusCodes()[0]]);
        assertConsistent(show);
    }

    @Test
    void concurrentDuplicateCancelsReleaseTheSeatsAndTheQuotaExactlyOnce() throws Exception {
        ShowInfo show = newShow(10, 4);
        ReserveOutcome r1 = service.reserve("alice", show.id(), Arrays.asList("S001", "S002"), uniqueKey());
        ReserveOutcome r2 = service.reserve("alice", show.id(), Arrays.asList("S003"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.CONFIRMED, r1.kind());
        assertEquals(ReserveOutcome.Kind.CONFIRMED, r2.kind());

        List<Callable<CancelOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> service.cancel("alice", r1.reservation().id()));
        }
        int cancelled = 0;
        int replays = 0;
        for (CancelOutcome o : runConcurrently(20, tasks)) {
            if (o.kind() == CancelOutcome.Kind.CANCELLED) {
                cancelled++;
            } else if (o.kind() == CancelOutcome.Kind.ALREADY_CANCELLED) {
                replays++;
            }
        }
        assertEquals(1, cancelled);
        assertEquals(19, replays);

        // alice still holds one seat (r2). A double release would have given her extra quota: she may now take
        // exactly three more seats, not four.
        for (String seat : Arrays.asList("S004", "S005", "S006")) {
            assertEquals(ReserveOutcome.Kind.CONFIRMED,
                    service.reserve("alice", show.id(), Arrays.asList(seat), uniqueKey()).kind(), seat);
        }
        ReserveOutcome overLimit = service.reserve("alice", show.id(), Arrays.asList("S007"), uniqueKey());
        assertEquals(ReserveOutcome.Kind.DECLINED, overLimit.kind());
        assertEquals(Metrics.PER_USER_LIMIT, overLimit.reason());
        assertConsistent(show);
    }

    // =========================================================================================== mixed workload

    @Test
    void aRandomMixOfReservesAndCancelsKeepsEveryInvariant() throws Exception {
        int seats = 30;
        int users = 40;
        ShowInfo show = newShow(seats, 4);
        List<String> labels = labels(seats);
        ConcurrentHashMap<String, Queue<ReservationRecord>> mine = new ConcurrentHashMap<>();
        AtomicInteger liveSeats = new AtomicInteger();

        List<Callable<Void>> ops = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            ops.add(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                String user = "chaos-" + rnd.nextInt(users);
                Queue<ReservationRecord> own = mine.computeIfAbsent(user, k -> new ConcurrentLinkedQueue<>());
                if (rnd.nextInt(100) < 35 && !own.isEmpty()) {
                    // peek() on purpose half of the time: several threads then cancel the same reservation at once
                    ReservationRecord rec = rnd.nextBoolean() ? own.peek() : own.poll();
                    if (rec != null) {
                        CancelOutcome c = service.cancel(user, rec.id());
                        if (c.kind() == CancelOutcome.Kind.CANCELLED) {
                            liveSeats.addAndGet(-rec.seats().size());
                        } else if (c.kind() != CancelOutcome.Kind.ALREADY_CANCELLED) {
                            fail("unexpected cancel outcome " + c.kind());
                        }
                    }
                } else {
                    List<String> pool = new ArrayList<>(labels);
                    Collections.shuffle(pool, rnd);
                    List<String> wanted = new ArrayList<>(pool.subList(0, 1 + rnd.nextInt(2)));
                    ReserveOutcome o = service.reserve(user, show.id(), wanted, uniqueKey());
                    if (o.kind() == ReserveOutcome.Kind.CONFIRMED) {
                        liveSeats.addAndGet(wanted.size());
                        own.add(o.reservation());
                    } else if (o.kind() != ReserveOutcome.Kind.DECLINED) {
                        fail("unexpected reserve outcome " + o.kind());
                    }
                }
                return null;
            });
        }
        runConcurrently(80, ops);

        assertEquals(liveSeats.get(), shows.state(show.id(), false).confirmed(),
                "seats confirmed in the database must equal bookings minus cancellations");
        assertConsistent(show);
    }

    // ===================================================================================================== helpers

    private static List<String> labels(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            out.add(String.format("S%03d", i));
        }
        return out;
    }

    private static ShowInfo newShow(int seats, int limit) {
        return shows.create("concurrency " + UUID.randomUUID(), 25_000L, limit, labels(seats));
    }

    private static String uniqueKey() {
        return "k-" + UUID.randomUUID();
    }

    /** Releases all tasks at once (start gate) on a pool of {@code threads}; rethrows the first task failure. */
    private static <T> List<T> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>(tasks.size());
            for (Future<T> f : futures) {
                results.add(f.get(180, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class Tally {
        int confirmed;
        int replay;
        int seatTaken;
        int limit;
        int keyConflict;
        int other;

        static Tally of(List<ReserveOutcome> outcomes) {
            Tally t = new Tally();
            for (ReserveOutcome o : outcomes) {
                switch (o.kind()) {
                    case CONFIRMED:
                        t.confirmed++;
                        break;
                    case REPLAY:
                        t.replay++;
                        break;
                    case KEY_CONFLICT:
                        t.keyConflict++;
                        break;
                    case DECLINED:
                        if (Metrics.SEAT_TAKEN.equals(o.reason())) {
                            t.seatTaken++;
                        } else if (Metrics.PER_USER_LIMIT.equals(o.reason())) {
                            t.limit++;
                        } else {
                            t.other++;
                        }
                        break;
                    default:
                        t.other++;
                }
            }
            return t;
        }

        @Override
        public String toString() {
            return "confirmed=" + confirmed + " replay=" + replay + " seat_taken=" + seatTaken + " per_user_limit="
                    + limit + " key_conflict=" + keyConflict + " other=" + other;
        }
    }

    /**
     * The database-level truth, independent of what the service returned:
     * <ol>
     *   <li>available + held + confirmed == total_seats (reconciliation invariant);</li>
     *   <li>every non-available seat belongs to a CONFIRMED reservation of the same user that lists that seat;</li>
     *   <li>every CONFIRMED reservation owns every seat it lists;</li>
     *   <li>each user's quota counter equals the number of seats that user actually owns.</li>
     * </ol>
     */
    private static void assertConsistent(ShowInfo show) throws Exception {
        ShowState st = shows.state(show.id(), true);
        assertEquals(show.totalSeats(), st.available() + st.held() + st.confirmed(), "available + held + confirmed");
        assertEquals(show.totalSeats(), st.labels().size(), "seat rows");

        assertEquals(0L, count("SELECT count(*) FROM seats s LEFT JOIN reservations r ON r.id = s.reservation_id "
                + "WHERE s.show_id = ? AND s.status <> 'available' AND (r.id IS NULL OR r.status <> 'confirmed' "
                + "OR r.user_id <> s.user_id OR r.show_id <> s.show_id OR NOT (s.label = ANY (r.seats)))",
                show.id()), "a seat is owned by a reservation that does not justify it");

        assertEquals(0L, count("SELECT count(*) FROM reservations r WHERE r.show_id = ? AND r.status = 'confirmed' "
                + "AND (SELECT count(*) FROM seats s WHERE s.show_id = r.show_id AND s.reservation_id = r.id) "
                + "<> cardinality(r.seats)", show.id()), "a confirmed reservation does not own all of its seats");

        Map<String, Integer> holds = new HashMap<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT user_id, seat_count FROM user_show_holds WHERE show_id = ? AND seat_count > 0")) {
            ps.setObject(1, show.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    holds.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        assertEquals(ownedSeatsByUser(show), holds, "per-user counters drifted from the seats actually owned");
    }

    private static Map<String, Integer> ownedSeatsByUser(ShowInfo show) throws Exception {
        Map<String, Integer> owned = new HashMap<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT user_id, count(*) FROM seats "
                        + "WHERE show_id = ? AND status <> 'available' GROUP BY user_id")) {
            ps.setObject(1, show.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owned.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return owned;
    }

    private static long count(String sql, UUID showId) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, showId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
