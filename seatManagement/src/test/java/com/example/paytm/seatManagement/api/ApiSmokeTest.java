package com.example.paytm.seatManagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paytm.seatManagement.support.MiniJson;
import com.example.paytm.seatManagement.support.TestDb;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Black-box checks of the HTTP contract against the fully wired application on a random port and a real PostgreSQL
 * (skipped unless TEST_DATABASE_URL is set). Secrets are generated per run.
 */
@EnabledIfEnvironmentVariable(named = TestDb.ENV_URL, matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {

    private static final Map<String, String> ENV = TestDb.env();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for (Map.Entry<String, String> e : ENV.entrySet()) {
            final String value = e.getValue();
            if (value != null) {
                registry.add(e.getKey(), () -> value);
            }
        }
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ================================================================================================ operations

    @Test
    void operationalEndpointsAreUpAndPublic() throws Exception {
        Resp health = get("/healthz", null);
        assertEquals(200, health.status);
        assertEquals("UP", health.json().get("status"));

        Resp ready = get("/readyz", null);
        assertEquals(200, ready.status);
        assertEquals("UP", ready.json().get("database"));

        Resp metrics = get("/metrics", null);
        assertEquals(200, metrics.status);
        assertTrue(metrics.body.contains("reservations_confirmed_total"));
        assertTrue(metrics.body.contains("reservations_declined_total{reason=\"seat_taken\"}"));
        assertTrue(metrics.body.contains("reservations_declined_total{reason=\"per_user_limit\"}"));
        assertTrue(metrics.body.contains("reservations_declined_total{reason=\"idempotent_replay\"}"));

        Resp logs = get("/logs?limit=20", null);
        assertEquals(200, logs.status);
        for (String line : logs.body.split("\n")) {
            if (!line.isEmpty()) {
                MiniJson.object(line); // every line is a JSON document
            }
        }
        assertNotNull(health.header("X-Request-Id"));
    }

    @Test
    void aClientSuppliedRequestIdIsEchoedAndAppearsInTheLogs() throws Exception {
        String id = "trace-" + UUID.randomUUID();
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(uri("/healthz")).header("X-Request-Id", id).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(id, r.headers().firstValue("X-Request-Id").orElse(null));
        Resp logs = null;
        for (int attempt = 0; attempt < 30; attempt++) { // the completion log line is written just after the response
            logs = get("/logs?request_id=" + id, null);
            assertEquals(200, logs.status);
            if (logs.body.contains(id)) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(logs.body.contains(id), "the request id should be searchable in the public logs");
    }

    // ================================================================================================ auth

    @Test
    void creatingAShowNeedsTheAdminCredential() throws Exception {
        String body = showBody("auth check", 3, 4, 25_000L);
        assertEquals(401, post("/shows", null, null, body).status);
        assertEquals(401, post("/shows", "not-a-real-token", null, body).status);
        assertEquals(403, post("/shows", userToken("alice"), null, body).status);

        Resp created = post("/shows", ENV.get("ADMIN_TOKEN"), null, body);
        assertEquals(201, created.status);
        assertEquals(3L, created.json().get("total_seats"));
    }

    @Test
    void reservingNeedsAValidUserToken() throws Exception {
        String show = createShow(2, 4);
        Resp noAuth = post("/shows/" + show + "/reserve", null, "k-" + UUID.randomUUID(), seatsBody("S001"));
        assertEquals(401, noAuth.status);
        assertEquals("unauthorized", errorCode(noAuth));
    }

    // ================================================================================================ reserve

    @Test
    void reserveReturnsTheDocumentedContractAndUpdatesTheShow() throws Exception {
        String show = createShow(5, 4);
        String alice = userToken("alice");

        Resp r = post("/shows/" + show + "/reserve", alice, "k-" + UUID.randomUUID(), seatsBody("S001", "S002"));
        assertEquals(201, r.status);
        Map<String, Object> m = r.json();
        assertNotNull(m.get("reservation_id"));
        assertEquals(show, m.get("show_id"));
        assertEquals("alice", m.get("user_id"));
        assertEquals(java.util.Arrays.asList("S001", "S002"), m.get("seats"));
        assertEquals(50_000L, m.get("amount_paise"));
        assertEquals("confirmed", m.get("status"));

        Map<String, Object> state = get("/shows/" + show, null).json();
        assertEquals(5L, state.get("total_seats"));
        assertEquals(3L, state.get("available"));
        assertEquals(0L, state.get("held"));
        assertEquals(2L, state.get("confirmed"));
        assertEquals((Long) state.get("total_seats"),
                (Long) state.get("available") + (Long) state.get("held") + (Long) state.get("confirmed"));
        List<?> seats = (List<?>) state.get("seats");
        assertEquals(5, seats.size());
        assertEquals("confirmed", ((Map<?, ?>) seats.get(0)).get("status"));
        assertEquals("available", ((Map<?, ?>) seats.get(2)).get("status"));

        Map<String, Object> countsOnly = get("/shows/" + show + "?seats=false", null).json();
        assertFalse(countsOnly.containsKey("seats"));
        assertEquals(2L, countsOnly.get("confirmed"));
    }

    @Test
    void identityComesFromTheTokenNeverFromTheBody() throws Exception {
        String show = createShow(3, 4);
        String body = "{\"seats\":[\"S001\"],\"user_id\":\"mallory\",\"userId\":\"mallory\"}";
        Resp r = post("/shows/" + show + "/reserve", userToken("alice"), "k-" + UUID.randomUUID(), body);
        assertEquals(201, r.status);
        assertEquals("alice", r.json().get("user_id"));

        // mallory cannot cancel alice's reservation with her own valid token
        String reservation = (String) r.json().get("reservation_id");
        assertEquals(403, post("/reservations/" + reservation + "/cancel", userToken("mallory"), null, null).status);
        assertEquals(1L, get("/shows/" + show + "?seats=false", null).json().get("confirmed"));
    }

    @Test
    void retriesWithTheSameKeyReplayTheOriginalAndConflictingReuseIsRejected() throws Exception {
        String show = createShow(5, 4);
        String alice = userToken("alice");
        String key = "k-" + UUID.randomUUID();

        Resp first = post("/shows/" + show + "/reserve", alice, key, seatsBody("S001"));
        assertEquals(201, first.status);
        assertNull(first.header("Idempotent-Replay"));

        Resp replay = post("/shows/" + show + "/reserve", alice, key, seatsBody("S001"));
        assertEquals(200, replay.status);
        assertEquals("true", replay.header("Idempotent-Replay"));
        assertEquals(first.json().get("reservation_id"), replay.json().get("reservation_id"));

        Resp conflict = post("/shows/" + show + "/reserve", alice, key, seatsBody("S002"));
        assertEquals(409, conflict.status);
        assertEquals("idempotency_key_conflict", errorCode(conflict));

        assertEquals(1L, get("/shows/" + show + "?seats=false", null).json().get("confirmed"));
        Resp noKey = post("/shows/" + show + "/reserve", alice, null, seatsBody("S003"));
        assertEquals(400, noKey.status);
        assertEquals("idempotency_key_required", errorCode(noKey));
    }

    @Test
    void declinesAreCleanConflictsNotServerErrors() throws Exception {
        String show = createShow(6, 2);
        String alice = userToken("alice");
        String bob = userToken("bob");

        assertEquals(201, post("/shows/" + show + "/reserve", alice, "k-" + UUID.randomUUID(), seatsBody("S001")).status);

        Resp taken = post("/shows/" + show + "/reserve", bob, "k-" + UUID.randomUUID(), seatsBody("S001"));
        assertEquals(409, taken.status);
        assertEquals("seat_taken", errorCode(taken));
        assertEquals(java.util.Arrays.asList("S001"), ((Map<?, ?>) taken.json().get("error")).get("seats"));
        assertEquals("declined", taken.json().get("status"));

        // limit is 2: alice holds 1, asking for 2 more must fail as a whole
        Resp limit = post("/shows/" + show + "/reserve", alice, "k-" + UUID.randomUUID(), seatsBody("S002", "S003"));
        assertEquals(409, limit.status);
        assertEquals("per_user_limit", errorCode(limit));
        assertEquals(1L, get("/shows/" + show + "?seats=false", null).json().get("confirmed"));
    }

    @Test
    void badInputIsRejectedWithAClearClientError() throws Exception {
        String show = createShow(2, 4);
        String alice = userToken("alice");
        String path = "/shows/" + show + "/reserve";
        String key = "k-" + UUID.randomUUID();

        assertEquals(400, post(path, alice, key, "{not json").status);
        assertEquals(400, post(path, alice, key, "{\"seats\":[]}").status);
        assertEquals(400, post(path, alice, key, "{\"seats\":\"S001\"}").status);
        assertEquals(400, post(path, alice, key, "{\"seats\":[\"S001\",\"S001\"]}").status);
        assertEquals(400, post(path, alice, key, "{\"seats\":[\"bad label!\"]}").status);
        assertEquals(422, post(path, alice, key, seatsBody("NOPE")).status);
        assertEquals(404, post("/shows/" + UUID.randomUUID() + "/reserve", alice, key, seatsBody("S001")).status);
        assertEquals(404, post("/shows/not-a-uuid/reserve", alice, key, seatsBody("S001")).status);
        assertEquals(404, get("/shows/" + UUID.randomUUID(), null).status);

        Resp badPrice = post("/shows", ENV.get("ADMIN_TOKEN"), null,
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":250.5}");
        assertEquals(400, badPrice.status);
        assertEquals(400, post("/shows", ENV.get("ADMIN_TOKEN"), null,
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":\"250\"}").status);
        assertEquals(400, post("/shows", ENV.get("ADMIN_TOKEN"), null,
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":-1}").status);
    }

    // ================================================================================================ cancel

    @Test
    void cancelReleasesTheSeatAndIsIdempotent() throws Exception {
        String show = createShow(2, 4);
        String alice = userToken("alice");
        String bob = userToken("bob");
        Resp r = post("/shows/" + show + "/reserve", alice, "k-" + UUID.randomUUID(), seatsBody("S001"));
        String reservation = (String) r.json().get("reservation_id");

        Resp c1 = post("/reservations/" + reservation + "/cancel", alice, null, null);
        assertEquals(200, c1.status);
        assertEquals("cancelled", c1.json().get("status"));
        Resp c2 = post("/reservations/" + reservation + "/cancel", alice, null, null);
        assertEquals(200, c2.status);
        assertEquals("true", c2.header("Idempotent-Replay"));

        assertEquals(2L, get("/shows/" + show + "?seats=false", null).json().get("available"));
        assertEquals(201, post("/shows/" + show + "/reserve", bob, "k-" + UUID.randomUUID(), seatsBody("S001")).status);
        assertEquals(404, post("/reservations/" + UUID.randomUUID() + "/cancel", alice, null, null).status);
        assertEquals(401, post("/reservations/" + reservation + "/cancel", null, null, null).status);
    }

    // ================================================================================================ metrics

    @Test
    void metricsMovePreciselyWithOutcomesAndGaugesMatchTheShow() throws Exception {
        String show = createShow(4, 4);
        String alice = userToken("alice");
        String bob = userToken("bob");
        String key = "k-" + UUID.randomUUID();

        Resp before = get("/metrics", null);
        double confirmed0 = metric(before.body, "reservations_confirmed_total");
        double taken0 = metric(before.body, "reservations_declined_total{reason=\"seat_taken\"}");
        double replay0 = metric(before.body, "reservations_declined_total{reason=\"idempotent_replay\"}");

        assertEquals(201, post("/shows/" + show + "/reserve", alice, key, seatsBody("S001")).status);
        assertEquals(200, post("/shows/" + show + "/reserve", alice, key, seatsBody("S001")).status);
        assertEquals(409, post("/shows/" + show + "/reserve", bob, "k-" + UUID.randomUUID(), seatsBody("S001")).status);

        Resp after = get("/metrics", null);
        assertEquals(confirmed0 + 1, metric(after.body, "reservations_confirmed_total"));
        assertEquals(taken0 + 1, metric(after.body, "reservations_declined_total{reason=\"seat_taken\"}"));
        assertEquals(replay0 + 1, metric(after.body, "reservations_declined_total{reason=\"idempotent_replay\"}"));
        assertEquals(3.0, metric(after.body, "seats_available{show_id=\"" + show + "\"}"));
        assertEquals(1.0, metric(after.body, "seats_confirmed{show_id=\"" + show + "\"}"));
    }

    // ================================================================================================ a small burst

    @Test
    void aHundredUsersFightingOverOneSeatOverHttpYieldOneWinnerAndNoServerErrors() throws Exception {
        String show = createShow(3, 4);
        int contenders = 100;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            tokens.add(userToken("burst-" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String token : tokens) {
                Callable<Integer> call = () -> post("/shows/" + show + "/reserve", token, "k-" + UUID.randomUUID(),
                        seatsBody("S001")).status;
                futures.add(pool.submit(call));
            }
            int created = 0;
            int conflicts = 0;
            int other = 0;
            for (Future<Integer> f : futures) {
                int status = f.get(60, TimeUnit.SECONDS);
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                } else {
                    other++;
                }
            }
            assertEquals(1, created);
            assertEquals(contenders - 1, conflicts);
            assertEquals(0, other, "no 5xx (or anything else) allowed");
        } finally {
            pool.shutdownNow();
        }
        Map<String, Object> state = get("/shows/" + show, null).json();
        assertEquals(1L, state.get("confirmed"));
        assertEquals(2L, state.get("available"));
    }

    // ===================================================================================================== helpers

    private static String showBody(String name, int seats, int limit, long pricePaise) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(name).append("\",\"price_paise\":").append(pricePaise)
                .append(",\"per_user_limit\":").append(limit).append(",\"seats\":[");
        for (int i = 1; i <= seats; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append('"').append(String.format("S%03d", i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private static String seatsBody(String... seats) {
        StringBuilder sb = new StringBuilder("{\"seats\":[");
        for (int i = 0; i < seats.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(seats[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    /** Creates a show as the admin and returns its id. */
    private String createShow(int seats, int limit) throws Exception {
        Resp r = post("/shows", ENV.get("ADMIN_TOKEN"), null, showBody("smoke " + UUID.randomUUID(), seats, limit, 25_000L));
        assertEquals(201, r.status, r.body);
        return (String) r.json().get("id");
    }

    private String userToken(String userId) throws Exception {
        Resp r = post("/auth/token", null, null, "{\"user_id\":\"" + userId + "\"}");
        assertEquals(200, r.status, r.body);
        return (String) r.json().get("token");
    }

    private static String errorCode(Resp r) {
        return (String) ((Map<?, ?>) r.json().get("error")).get("code");
    }

    /** Value of the sample whose series (name plus labels) is exactly {@code series}. */
    private static double metric(String text, String series) {
        for (String line : text.split("\n")) {
            if (line.startsWith(series + " ")) {
                return Double.parseDouble(line.substring(series.length() + 1).trim());
            }
        }
        throw new AssertionError("metric not found: " + series);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private Resp get(String path, String bearer) throws Exception {
        return send("GET", path, bearer, null, null);
    }

    private Resp post(String path, String bearer, String idempotencyKey, String body) throws Exception {
        return send("POST", path, bearer, idempotencyKey, body);
    }

    private Resp send(String method, String path, String bearer, String idempotencyKey, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    private static final class Resp {
        final int status;
        final String body;
        private final java.net.http.HttpHeaders headers;

        Resp(int status, String body, java.net.http.HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        Map<String, Object> json() {
            return MiniJson.object(body);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
