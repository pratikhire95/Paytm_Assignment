/*
 * Burst.java - one-command correctness-under-load test for the seat reservation service.
 *
 *     ADMIN_TOKEN=... ./burst.sh https://your-service.example.com        (wrapper; needs a JDK 11+ or Docker)
 *     ADMIN_TOKEN=... java scripts/Burst.java --url https://your-service.example.com
 *
 * What it does (speed depends on the host: a small free-tier instance is far slower than a laptop):
 *   0. waits for the service to become ready (so a cold start is simply "waiting", not a failure);
 *   1. functional probes on a small show: auth, contract, idempotency, per-user limit, all-or-nothing, cancel, validation;
 *   2. the storm: --requests (default 20,000) reserve calls from as many distinct users, concentrated on a few hot seats,
 *      with some multi-seat requests and some requests sent twice at once (client retries), up to --concurrency in flight;
 *   3. churn: owners cancel (twice, concurrently) while fresh users race to re-book the very same seats;
 *   4. prints the outcome distribution and reconciles EVERYTHING: client-side ledger vs GET /shows/{id} vs /metrics.
 *
 * Exit code: 0 = every check passed, 1 = a correctness check failed, 2 = could not run (service unreachable, bad options).
 * No third-party dependencies: java.net.http only (Java 11+).
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Burst {

    // =============================================================================================== entry point

    public static void main(String[] args) {
        // Drop idle pooled connections after 5s (default 30s): servers usually close idle connections sooner, and reusing one
        // that the server has just closed is the classic source of spurious "EOF" failures. Must be set before the first HttpClient.
        if (System.getProperty("jdk.httpclient.keepalive.timeout") == null) {
            System.setProperty("jdk.httpclient.keepalive.timeout", "5");
        }
        Config cfg;
        try {
            cfg = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            System.err.println(Config.USAGE);
            System.exit(2);
            return;
        }
        if (cfg == null) {
            System.out.println(Config.USAGE);
            return;
        }
        int code;
        try {
            code = new Burst(cfg).run();
        } catch (Abort e) {
            System.err.println();
            System.err.println("ABORTED: " + e.getMessage());
            code = 2;
        } catch (Exception e) {
            e.printStackTrace();
            code = 2;
        }
        System.exit(code);
    }

    /** The test could not be carried out (as opposed to: it ran and found a bug). */
    static final class Abort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Abort(String message) {
            super(message);
        }
    }

    // =============================================================================================== options

    static final class Config {
        static final String USAGE = String.join("\n",
                "Usage: java scripts/Burst.java [URL] [options]      (or: ./burst.sh [URL] [options])",
                "",
                "  --url URL            service base URL                      (default: $BASE_URL, else http://localhost:8080)",
                "  --admin-token T      admin credential used to create shows (default: $ADMIN_TOKEN; prefer the env var)",
                "  --show-id UUID       use an existing show instead of creating one (probes are skipped without an admin token)",
                "  --requests N         reserve requests in the storm          (default 20000)",
                "  --seats N            seats in the storm show                (default 1000)",
                "  --hot-seats N        seats that attract 60% of all traffic  (default 20)",
                "  --concurrency N      maximum requests in flight             (default 1500)",
                "  --churn N            cancel-vs-rebook races after the storm (default 300)",
                "  --wait SECONDS       how long to wait for readiness         (default 240; covers a cold start)",
                "  --timeout SECONDS    per-request timeout                    (default 120)",
                "  --seed N             random seed, to repeat a run exactly",
                "  --one-seat           the classic hot-seat storm: every request fights over one single seat",
                "  --quick              small run: 2000 requests, 200 seats, 5 hot seats, 300 in flight, 60 churn",
                "  --skip-probes        skip the functional probes",
                "  --lenient-metrics    report /metrics counter mismatches as warnings (use when other traffic hits the service)",
                "  --http2              use HTTP/2 (default HTTP/1.1: one connection per in-flight request)",
                "  -h, --help           this text",
                "",
                "Exit code: 0 all checks passed | 1 a check failed | 2 the test could not run");

        String url = firstNonBlank(System.getenv("BASE_URL"), "http://localhost:8080");
        String adminToken = blankToNull(System.getenv("ADMIN_TOKEN"));
        String showId = null;
        int requests = 20_000;
        int seats = 1_000;
        int hotSeats = 20;
        int concurrency = 1_500;
        int churn = 300;
        int waitSeconds = 240;
        int timeoutSeconds = 120;
        long seed = new SecureRandom().nextLong();
        boolean skipProbes = false;
        boolean lenientMetrics = false;
        boolean http2 = false;

        static Config parse(String[] args) {
            Config c = new Config();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        return null;
                    case "--url":
                        c.url = next(args, ++i, a);
                        break;
                    case "--admin-token":
                        c.adminToken = blankToNull(next(args, ++i, a));
                        break;
                    case "--show-id":
                        c.showId = next(args, ++i, a).trim();
                        break;
                    case "--requests":
                        c.requests = intOpt(next(args, ++i, a), a, 1, 1_000_000);
                        break;
                    case "--seats":
                        c.seats = intOpt(next(args, ++i, a), a, 1, 100_000);
                        break;
                    case "--one-seat":
                        c.seats = 1; // the canonical hot-seat storm: everybody fights over the same single seat
                        c.hotSeats = 1;
                        break;
                    case "--hot-seats":
                        c.hotSeats = intOpt(next(args, ++i, a), a, 1, 100_000);
                        break;
                    case "--concurrency":
                        c.concurrency = intOpt(next(args, ++i, a), a, 1, 100_000);
                        break;
                    case "--churn":
                        c.churn = intOpt(next(args, ++i, a), a, 0, 100_000);
                        break;
                    case "--wait":
                        c.waitSeconds = intOpt(next(args, ++i, a), a, 0, 3_600);
                        break;
                    case "--timeout":
                        c.timeoutSeconds = intOpt(next(args, ++i, a), a, 1, 3_600);
                        break;
                    case "--seed":
                        try {
                            c.seed = Long.parseLong(next(args, ++i, a).trim());
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("--seed must be an integer");
                        }
                        break;
                    case "--quick":
                        c.requests = 2_000;
                        c.seats = 200;
                        c.hotSeats = 5;
                        c.concurrency = 300;
                        c.churn = 60;
                        break;
                    case "--skip-probes":
                        c.skipProbes = true;
                        break;
                    case "--lenient-metrics":
                        c.lenientMetrics = true;
                        break;
                    case "--http2":
                        c.http2 = true;
                        break;
                    default:
                        if (!a.startsWith("-") && i == 0) {
                            c.url = a; // positional URL: ./burst.sh https://host
                            break;
                        }
                        throw new IllegalArgumentException("unknown option: " + a);
                }
            }
            c.url = c.url.trim();
            while (c.url.endsWith("/")) {
                c.url = c.url.substring(0, c.url.length() - 1);
            }
            if (!c.url.startsWith("http://") && !c.url.startsWith("https://")) {
                throw new IllegalArgumentException("the URL must start with http:// or https://");
            }
            try {
                URI.create(c.url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the URL is not valid: " + e.getMessage());
            }
            if (c.showId != null && !c.showId.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
                throw new IllegalArgumentException("--show-id must be a UUID");
            }
            if (c.hotSeats > c.seats) {
                c.hotSeats = c.seats;
            }
            return c;
        }

        private static String next(String[] args, int i, String opt) {
            if (i >= args.length) {
                throw new IllegalArgumentException(opt + " needs a value");
            }
            return args[i];
        }

        private static int intOpt(String v, String opt, int min, int max) {
            int n;
            try {
                n = Integer.parseInt(v.trim().replace("_", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(opt + " must be an integer");
            }
            if (n < min || n > max) {
                throw new IllegalArgumentException(opt + " must be between " + min + " and " + max);
            }
            return n;
        }

        private static String blankToNull(String s) {
            return s == null || s.trim().isEmpty() ? null : s.trim();
        }

        private static String firstNonBlank(String a, String b) {
            String t = blankToNull(a);
            return t != null ? t : b;
        }
    }

    // =============================================================================================== state

    private final Config cfg;
    private final Api api;
    private final String runId = Long.toHexString(new SecureRandom().nextInt() & 0xffffffffL);
    private final List<String> failures = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int checks = 0;
    private String sampleRequestId = null;

    private Burst(Config cfg) {
        this.cfg = cfg;
        this.api = new Api(cfg);
    }

    private int run() throws Exception {
        long started = System.nanoTime();
        System.out.println("Seat reservation burst test");
        System.out.printf(Locale.ROOT, "  target       %s%n", cfg.url);
        System.out.printf(Locale.ROOT, "  run id       %s (seed %d)%n", runId, cfg.seed);
        System.out.printf(Locale.ROOT, "  storm        %,d requests, %,d seats (%d hot), up to %,d in flight, churn %d%n",
                cfg.requests, cfg.seats, cfg.hotSeats, cfg.concurrency, cfg.churn);

        phaseReady();
        if (cfg.showId == null && cfg.adminToken == null) {
            throw new Abort("An admin token is needed to create the test show: set ADMIN_TOKEN (or pass --admin-token), "
                    + "or point the test at an existing show with --show-id.");
        }
        if (cfg.skipProbes) {
            System.out.println();
            System.out.println("[1] Functional probes: skipped (--skip-probes)");
        } else if (cfg.adminToken == null) {
            System.out.println();
            System.out.println("[1] Functional probes: skipped (they create their own show and need the admin token)");
        } else {
            phaseProbes();
        }
        phaseStorm();
        return summary(started);
    }

    // =============================================================================================== output helpers

    private void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private void check(boolean ok, String name, String detail) {
        checks++;
        if (!ok) {
            failures.add(name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
        System.out.println("    " + (ok ? "PASS" : "FAIL") + "  " + name + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    private void check(boolean ok, String name) {
        check(ok, name, "");
    }

    /** A metrics comparison: a hard failure, or only a warning with --lenient-metrics. */
    private void checkMetric(boolean ok, String name, String detail) {
        if (ok || !cfg.lenientMetrics) {
            check(ok, name, detail);
        } else {
            warnings.add(name + "  [" + detail + "]");
            System.out.println("    WARN  " + name + "  [" + detail + "]  (--lenient-metrics)");
        }
    }

    private void note(String text) {
        System.out.println("    " + text);
    }

    private void warn(String text) {
        warnings.add(text);
        System.out.println("    WARN  " + text);
    }

    private static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String secs(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1e9);
    }

    // =============================================================================================== phase 0: ready

    private void phaseReady() throws InterruptedException {
        section("[0] Waiting for the service to be ready (a cold start counts as waiting, not failing)");
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(cfg.waitSeconds);
        int attempt = 0;
        String last = "no response";
        while (true) {
            Resp r = api.send("GET", "/readyz", null, null, null, 15).join();
            if (r.status == 200) {
                System.out.printf(Locale.ROOT, "    ready after %s (%d probe%s)%n", secs(System.nanoTime() - start), attempt + 1,
                        attempt == 0 ? "" : "s");
                return;
            }
            last = r.status < 0 ? r.transportError : "HTTP " + r.status;
            attempt++;
            if (System.nanoTime() > deadline) {
                throw new Abort("the service did not become ready within " + cfg.waitSeconds + "s (last answer: " + last
                        + "). Check the URL, the deployment logs and GET /readyz.");
            }
            if (attempt == 1) {
                System.out.println("    not ready yet (" + last + "), retrying ...");
            }
            Thread.sleep(Math.min(5_000L, 500L * attempt));
        }
    }

    // =============================================================================================== phase 1: probes

    private void phaseProbes() throws Exception {
        section("[1] Functional probes (a small dedicated show)");
        String show = createShow("probe " + runId, 40, 4, 25_000L, "P");
        String alice = mintToken("probe-alice-" + runId);
        String bob = mintToken("probe-bob-" + runId);
        String mallory = mintToken("probe-mallory-" + runId);
        String greedy = mintToken("probe-greedy-" + runId);
        String path = "/shows/" + show + "/reserve";

        Resp r = call("POST", path, null, newKey(), seatsBody(Arrays.asList("P0001")));
        check(r.status == 401, "reserve without a token is rejected", "HTTP " + r.status);
        r = call("POST", "/shows", alice, null, showBody("nope", 2, 4, 100L, "X"));
        check(r.status == 403, "a normal user cannot create a show", "HTTP " + r.status);
        r = call("POST", "/shows", null, null, showBody("nope", 2, 4, 100L, "X"));
        check(r.status == 401, "creating a show without a token is rejected", "HTTP " + r.status);

        // contract + identity from the token (the body tries to claim to be mallory)
        String key1 = newKey();
        String spoofed = "{\"seats\":[\"P0001\",\"P0002\"],\"user_id\":\"probe-mallory-" + runId + "\"}";
        Resp first = call("POST", path, alice, key1, spoofed);
        Map<String, Object> m = obj(first.body);
        check(first.status == 201, "reserve answers 201", "HTTP " + first.status);
        check(m != null && ("probe-alice-" + runId).equals(str(m, "user_id")),
                "identity comes from the token, a spoofed user_id in the body is ignored", "user_id=" + (m == null ? null : str(m, "user_id")));
        check(m != null && show.equals(str(m, "show_id")) && "confirmed".equals(str(m, "status"))
                        && m.get("reservation_id") instanceof String && Long.valueOf(50_000L).equals(m.get("amount_paise"))
                        && Arrays.asList("P0001", "P0002").equals(m.get("seats")),
                "reservation body has reservation_id, show_id, user_id, seats, amount_paise (integer paise), status=confirmed",
                first.body == null ? "" : abbreviate(first.body));
        String reservationId = m == null ? null : str(m, "reservation_id");

        // idempotency
        Resp replay = call("POST", path, alice, key1, "{\"seats\":[\"P0002\",\"P0001\"]}");
        Map<String, Object> rm = obj(replay.body);
        check(replay.status == 200 && "true".equalsIgnoreCase(replay.replay) && rm != null
                        && reservationId != null && reservationId.equals(str(rm, "reservation_id")),
                "same key + same seats replays the original (200, Idempotent-Replay: true, same reservation_id)",
                "HTTP " + replay.status);
        Resp conflict = call("POST", path, alice, key1, seatsBody(Arrays.asList("P0003")));
        check(conflict.status == 409 && "idempotency_key_conflict".equals(errorCode(conflict.body)),
                "same key + different seats is rejected (409 idempotency_key_conflict)", "HTTP " + conflict.status);
        Resp noKey = call("POST", path, alice, null, seatsBody(Arrays.asList("P0003")));
        check(noKey.status == 400, "a missing idempotency key is a 400", "HTTP " + noKey.status);
        ShowView afterIdem = fetchShow(show);
        check("available".equals(afterIdem.statusOf("P0003")), "the rejected requests booked nothing");

        // seat taken, all-or-nothing
        Resp taken = call("POST", path, bob, newKey(), seatsBody(Arrays.asList("P0001")));
        check(taken.status == 409 && "seat_taken".equals(errorCode(taken.body)), "a taken seat is a clean 409 seat_taken",
                "HTTP " + taken.status);
        Resp partial = call("POST", path, bob, newKey(), seatsBody(Arrays.asList("P0030", "P0001")));
        check(partial.status == 409 && "available".equals(fetchShow(show).statusOf("P0030")),
                "a multi-seat request is all-or-nothing (one taken seat -> nothing booked)", "HTTP " + partial.status);

        // per-user limit under concurrency: 10 parallel requests, limit 4
        List<CompletableFuture<Resp>> parallel = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            parallel.add(api.send("POST", path, greedy, newKey(), seatsBody(Arrays.asList(String.format("P%04d", 10 + i))),
                    cfg.timeoutSeconds));
        }
        int created = 0;
        int limited = 0;
        int other = 0;
        for (CompletableFuture<Resp> f : parallel) {
            Resp x = f.join();
            if (x.status == 201) {
                created++;
            } else if (x.status == 409 && "per_user_limit".equals(errorCode(x.body))) {
                limited++;
            } else {
                other++;
            }
        }
        check(created == 4 && limited == 6 && other == 0, "per-user limit holds under concurrency (10 parallel requests, limit 4)",
                created + " x 201, " + limited + " x 409 per_user_limit, " + other + " other");

        // cancel
        Resp denied = call("POST", "/reservations/" + reservationId + "/cancel", mallory, null, null);
        check(denied.status == 403, "only the owner can cancel", "HTTP " + denied.status);
        Resp c1 = call("POST", "/reservations/" + reservationId + "/cancel", alice, null, null);
        check(c1.status == 200 && "cancelled".equals(str(obj(c1.body), "status")), "the owner can cancel (200, status=cancelled)",
                "HTTP " + c1.status);
        Resp c2 = call("POST", "/reservations/" + reservationId + "/cancel", alice, null, null);
        check(c2.status == 200 && "true".equalsIgnoreCase(c2.replay), "cancelling twice is harmless (200, Idempotent-Replay: true)",
                "HTTP " + c2.status);
        ShowView afterCancel = fetchShow(show);
        check("available".equals(afterCancel.statusOf("P0001")) && "available".equals(afterCancel.statusOf("P0002")),
                "cancelled seats are available again");
        Resp rebook = call("POST", path, bob, newKey(), seatsBody(Arrays.asList("P0001")));
        check(rebook.status == 201, "another user can now book the released seat", "HTTP " + rebook.status);
        check(afterCancel.total == afterCancel.available + afterCancel.held + afterCancel.confirmed,
                "available + held + confirmed == total_seats", afterCancel.available + " + " + afterCancel.held + " + "
                        + afterCancel.confirmed + " == " + afterCancel.total);

        // validation
        check(call("POST", path, alice, newKey(), "{not json").status == 400, "malformed JSON is a 400");
        check(call("POST", path, alice, newKey(), seatsBody(Arrays.asList("NOPE"))).status == 422, "an unknown seat is a 422");
        check(call("POST", "/shows/" + UUIDS.next() + "/reserve", alice, newKey(), seatsBody(Arrays.asList("P0001"))).status == 404,
                "an unknown show is a 404");
        check(call("POST", "/shows", cfg.adminToken, null,
                "{\"name\":\"x\",\"seats\":[\"A1\"],\"price_paise\":250.5}").status == 400, "a fractional price is rejected (money is integer paise)");
    }

    // =============================================================================================== phase 2-4: storm

    /** What one simulated user asks for. */
    private static final class Attempt {
        final int index;
        final String key;
        final List<String> seats;
        final int copies;

        Attempt(int index, String key, List<String> seats, int copies) {
            this.index = index;
            this.key = key;
            this.seats = seats;
            this.copies = copies;
        }
    }

    private void phaseStorm() throws Exception {
        section("[2] The storm");

        // ---- the show under test
        String showId;
        long price;
        List<String> seatPool;
        Set<String> baseline;
        if (cfg.showId != null) {
            showId = cfg.showId;
            ShowView v = fetchShow(showId);
            price = v.price;
            seatPool = v.availableLabels();
            baseline = v.confirmedSet();
            if (seatPool.size() < 2) {
                throw new Abort("show " + showId + " has fewer than 2 available seats; create a fresh one.");
            }
            note("using existing show " + showId + " (" + seatPool.size() + " available seats, price " + price + " paise)");
        } else {
            price = 49_900L;
            showId = createShow("storm " + runId, cfg.seats, 4, price, "A");
            seatPool = new ArrayList<>();
            int width = Math.max(4, String.valueOf(cfg.seats).length());
            for (int i = 1; i <= cfg.seats; i++) {
                seatPool.add(String.format(Locale.ROOT, "A%0" + width + "d", i));
            }
            baseline = new HashSet<>();
            note("created show " + showId + " with " + n(cfg.seats) + " seats at " + n(price) + " paise");
        }
        final int hotCount = Math.min(cfg.hotSeats, seatPool.size());
        final List<String> hot = new ArrayList<>(seatPool.subList(0, hotCount));
        final List<String> cold = new ArrayList<>(seatPool.subList(hotCount, seatPool.size()));

        final Map<String, Double> metricsBefore = fetchMetrics();
        if (metricsBefore == null) {
            check(false, "GET /metrics is readable and parseable");
        }

        // ---- users
        long t0 = System.nanoTime();
        final String[] tokens = mintTokens(cfg.requests, "storm-" + runId + "-");
        note("minted " + n(cfg.requests) + " user tokens in " + secs(System.nanoTime() - t0));

        // ---- the plan
        Random rnd = new Random(cfg.seed);
        final List<Attempt> attempts = new ArrayList<>(cfg.requests);
        int totalHttp = 0;
        int multi = 0;
        int dupes = 0;
        for (int i = 0; i < cfg.requests; i++) {
            double p = rnd.nextDouble();
            int want = p < 0.10 ? 2 : (p < 0.12 ? 3 : 1);
            want = Math.min(want, seatPool.size());
            Set<String> chosen = new LinkedHashSet<>();
            while (chosen.size() < want) {
                boolean pickHot = cold.isEmpty() || (!hot.isEmpty() && rnd.nextDouble() < 0.60);
                List<String> from = pickHot ? hot : cold;
                chosen.add(from.get(rnd.nextInt(from.size())));
            }
            int copies = rnd.nextDouble() < 0.10 ? 2 : 1;
            attempts.add(new Attempt(i, "storm-" + runId + "-" + i, new ArrayList<>(chosen), copies));
            totalHttp += copies;
            if (want > 1) {
                multi++;
            }
            if (copies > 1) {
                dupes++;
            }
        }
        note(n(cfg.requests) + " users, " + n(multi) + " multi-seat requests, " + n(dupes) + " sent twice at once => "
                + n(totalHttp) + " HTTP requests; "
                + (cold.isEmpty() ? "all of them target the " + hotCount + " hot seat" + (hotCount == 1 ? "" : "s")
                        : hotCount + " hot seat" + (hotCount == 1 ? "" : "s") + " take 60% of the traffic"));

        // ---- fire
        final int retriesAtStart = api.transportRetries.get();
        final Result[] results = new Result[totalHttp];
        final String reservePath = "/shows/" + showId + "/reserve";
        final Semaphore permits = new Semaphore(cfg.concurrency);
        final CountDownLatch done = new CountDownLatch(totalHttp);
        final int totalFinal = totalHttp;
        long fireStart = System.nanoTime();
        Thread progress = progressThread(done, totalFinal, fireStart);
        int slot = 0;
        for (Attempt a : attempts) {
            for (int c = 0; c < a.copies; c++) {
                final int mySlot = slot++;
                final Attempt att = a;
                permits.acquire();
                api.send("POST", reservePath, tokens[a.index], a.key, seatsBody(a.seats), cfg.timeoutSeconds)
                        .whenComplete((resp, ex) -> {
                            try {
                                results[mySlot] = Result.of(att.index, resp, ex);
                            } catch (RuntimeException bug) {
                                results[mySlot] = Result.of(att.index, null, bug); // never leave a hole in the results
                            } finally {
                                permits.release();
                                done.countDown();
                            }
                        });
            }
        }
        done.await();
        long wall = System.nanoTime() - fireStart;
        progress.interrupt();
        note(String.format(Locale.ROOT, "storm finished in %s (%s requests/s)", secs(wall), n(Math.round(totalHttp / (wall / 1e9)))));

        // ---- outcome distribution
        section("[3] Outcome distribution");
        Map<Outcome, Integer> dist = new LinkedHashMap<>();
        for (Outcome o : Outcome.values()) {
            dist.put(o, 0);
        }
        long[] lat = new long[results.length];
        for (int i = 0; i < results.length; i++) {
            dist.merge(results[i].outcome, 1, Integer::sum);
            lat[i] = results[i].nanos;
        }
        for (Outcome o : Outcome.values()) {
            int cnt = dist.get(o);
            if (cnt > 0 || o == Outcome.CREATED || o == Outcome.SEAT_TAKEN || o == Outcome.SERVER_ERROR || o == Outcome.TRANSPORT) {
                System.out.printf(Locale.ROOT, "    %-34s %8s%n", o.label, n(cnt));
            }
        }
        Arrays.sort(lat);
        System.out.printf(Locale.ROOT, "    latency ms  p50 %.1f   p95 %.1f   p99 %.1f   max %.1f%n", ms(lat, 0.50), ms(lat, 0.95),
                ms(lat, 0.99), ms(lat, 1.0));

        // ---- reconciliation, part 1: what the clients were told
        section("[4] Reconciliation: what every client was told vs what the service says");
        int serverErrors = dist.get(Outcome.SERVER_ERROR);
        int transport = dist.get(Outcome.TRANSPORT);
        check(serverErrors == 0 && transport == 0, "zero 5xx responses and zero connection failures",
                serverErrors + " x 5xx, " + transport + " x connection failure" + firstProblems(results, Outcome.SERVER_ERROR, Outcome.TRANSPORT));
        check(dist.get(Outcome.UNEXPECTED) == 0 && dist.get(Outcome.OTHER_409) == 0 && dist.get(Outcome.PER_USER_LIMIT) == 0
                        && dist.get(Outcome.KEY_CONFLICT) == 0,
                "every response is one of: 201 created, 200 idempotent replay, 409 seat_taken",
                "unexpected=" + dist.get(Outcome.UNEXPECTED) + firstProblems(results, Outcome.UNEXPECTED, Outcome.OTHER_409));
        int resent = api.transportRetries.get() - retriesAtStart;
        if (resent > 0) {
            warn(n(resent) + " request(s) hit a connection-level failure (reset / EOF on a stale connection / timeout) and were re-sent "
                    + "with the same Idempotency-Key, which is safe and is accounted for below. A large number means the service or a "
                    + "proxy in front of it is dropping connections under load.");
        }

        Map<Integer, List<Result>> byAttempt = new HashMap<>();
        for (Result r : results) {
            byAttempt.computeIfAbsent(r.attempt, k -> new ArrayList<>(2)).add(r);
        }
        Map<String, String> ledger = new HashMap<>(); // seat -> reservation id, from 201 / replay responses
        List<String> violations = new ArrayList<>();
        Set<String> demandedAlone = new HashSet<>(); // seats someone asked for on their own: they MUST end up sold
        Set<String> namedAsTaken = new HashSet<>();
        int created = 0;
        int seatTaken = 0;
        int replays = 0;
        for (Attempt a : attempts) {
            List<Result> rs = byAttempt.get(a.index);
            if (rs == null || rs.size() != a.copies) {
                violations.add("attempt " + a.index + ": missing results");
                continue;
            }
            int c201 = 0;
            int c200 = 0;
            int c409 = 0;
            boolean retried = false;
            Result winner = null;
            Result replayed = null;
            for (Result r : rs) {
                retried |= r.retries > 0;
                if (r.outcome == Outcome.CREATED) {
                    c201++;
                    winner = r;
                } else if (r.outcome == Outcome.REPLAY) {
                    c200++;
                    replayed = r;
                } else if (r.outcome == Outcome.SEAT_TAKEN) {
                    c409++;
                }
            }
            created += c201;
            replays += c200;
            seatTaken += c409;
            boolean definitive = c201 + c200 + c409 == rs.size(); // every copy got a proper answer
            if (definitive) {
                if (a.seats.size() == 1) {
                    demandedAlone.add(a.seats.get(0));
                }
                // One key, one reservation: at most one 201; its twin (if any) must replay it. Without any 201 the copies were
                // all declined - or, only if a request had to be re-sent after a lost response, replays of an applied one.
                boolean ok = c201 <= 1 && (c201 == 1 ? c409 == 0 : (c200 == 0 || (retried && c409 == 0)));
                if (!ok) {
                    violations.add("attempt " + a.index + " (" + a.copies + " cop" + (a.copies == 1 ? "y" : "ies") + ", seats " + a.seats
                            + ") got " + describe(rs));
                }
            }
            Result booked = winner != null ? winner : (c409 == 0 ? replayed : null);
            if (booked != null) {
                String expectedUser = "storm-" + runId + "-" + a.index;
                if (!expectedUser.equals(booked.userId) || !showId.equals(booked.showId)
                        || booked.amount != price * a.seats.size() || !a.seats.equals(booked.seats)) {
                    violations.add("attempt " + a.index + ": the reservation body does not match the request (user=" + booked.userId
                            + ", amount=" + booked.amount + ", seats=" + booked.seats + ")");
                }
                for (String seat : booked.seats) {
                    String prev = ledger.putIfAbsent(seat, booked.reservationId);
                    if (prev != null) {
                        violations.add("DOUBLE SALE: seat " + seat + " was sold to reservations " + prev + " and " + booked.reservationId);
                    }
                }
                for (Result r : rs) {
                    if (r.outcome == Outcome.REPLAY && (!booked.reservationId.equals(r.reservationId) || !booked.seats.equals(r.seats)
                            || booked.amount != r.amount)) {
                        violations.add("attempt " + a.index + ": the replay differs from the original reservation");
                    }
                }
            }
            for (Result r : rs) {
                if (r.outcome == Outcome.SEAT_TAKEN) {
                    namedAsTaken.addAll(r.unavailable);
                }
            }
        }
        check(violations.isEmpty(), "no seat sold twice; every retry replays its original; reservation bodies match the requests",
                violations.size() + " violation(s)" + sample(violations));

        // ---- reconciliation, part 2: the service's own view
        ShowView after = fetchShow(showId);
        check(after.total == after.available + after.held + after.confirmed && after.labels.size() == after.total,
                "available + held + confirmed == total_seats",
                after.available + " + " + after.held + " + " + after.confirmed + " == " + after.total);
        Set<String> confirmedNow = after.confirmedSet();
        Set<String> newlyConfirmed = new HashSet<>(confirmedNow);
        newlyConfirmed.removeAll(baseline);
        check(newlyConfirmed.equals(ledger.keySet()), "seats confirmed in GET /shows/{id} == seats clients were told they got",
                "service says " + n(newlyConfirmed.size()) + " confirmed, clients hold " + n(ledger.size()) + diffHint(newlyConfirmed, ledger.keySet()));
        Set<String> unsold = new HashSet<>(demandedAlone);
        unsold.removeAll(confirmedNow);
        check(unsold.isEmpty(), "every seat someone asked for on its own ended up sold (no lost seats)",
                n(demandedAlone.size()) + " seats demanded, " + unsold.size() + " unsold" + sample(new ArrayList<>(unsold)));
        Set<String> wronglyTaken = new HashSet<>(namedAsTaken);
        wronglyTaken.removeAll(confirmedNow);
        check(wronglyTaken.isEmpty(), "every seat named in a 409 seat_taken really is taken",
                wronglyTaken.size() + " not taken" + sample(new ArrayList<>(wronglyTaken)));
        int hotSold = 0;
        for (String h : hot) {
            if (confirmedNow.contains(h) && ledger.containsKey(h)) {
                hotSold++;
            }
        }
        long hotDemanded = hot.stream().filter(demandedAlone::contains).count();
        note(hotSold + " of " + hot.size() + " hot seats sold, each exactly once (" + hotDemanded + " had a single-seat contender)");

        // ---- churn
        ChurnOutcome churn = phaseChurn(showId, byAttempt, attempts, tokens);

        // ---- final state + metrics
        section("[6] Final reconciliation");
        ShowView last = fetchShow(showId);
        check(last.total == last.available + last.held + last.confirmed && last.labels.size() == last.total,
                "available + held + confirmed == total_seats (final)",
                last.available + " + " + last.held + " + " + last.confirmed + " == " + last.total);
        Set<String> expected = new HashSet<>(ledger.keySet());
        expected.removeAll(churn.cancelledSeats);
        expected.addAll(churn.rebookedSeats);
        Set<String> actual = new HashSet<>(last.confirmedSet());
        actual.removeAll(baseline);
        check(actual.equals(expected), "final seat map == bookings - cancellations + re-bookings",
                "expected " + n(expected.size()) + ", service has " + n(actual.size()) + diffHint(actual, expected));

        Map<String, Double> metricsAfter = fetchMetrics();
        if (metricsBefore != null && metricsAfter != null) {
            double dConfirmed = delta(metricsAfter, metricsBefore, "reservations_confirmed_total");
            double dTaken = delta(metricsAfter, metricsBefore, "reservations_declined_total{reason=\"seat_taken\"}");
            double dReplay = delta(metricsAfter, metricsBefore, "reservations_declined_total{reason=\"idempotent_replay\"}");
            double dLimit = delta(metricsAfter, metricsBefore, "reservations_declined_total{reason=\"per_user_limit\"}");
            double dCancelled = delta(metricsAfter, metricsBefore, "reservations_cancelled_total");
            double dErrors = delta(metricsAfter, metricsBefore, "server_errors_total{type=\"db_unavailable\"}")
                    + delta(metricsAfter, metricsBefore, "server_errors_total{type=\"internal\"}");
            long wantConfirmed = created + churn.rebooked;
            long wantTaken = seatTaken + churn.rebookDeclined;
            // A request that was re-sent after a lost response may have been counted by the service although its first answer
            // never arrived, so each re-send widens the tolerance by one. Without any re-send the numbers must match exactly.
            long tol = Math.max(0, api.transportRetries.get() - retriesAtStart);
            String tolText = tol == 0 ? "" : " (+/-" + tol + " tolerated: " + tol + " re-sent request(s))";
            checkMetric(Math.abs(dConfirmed - wantConfirmed) <= tol, "metrics: reservations_confirmed_total moved by the number of 201s",
                    "metric +" + (long) dConfirmed + ", clients saw " + n(wantConfirmed) + tolText);
            checkMetric(Math.abs(dTaken - wantTaken) <= tol,
                    "metrics: reservations_declined_total{reason=seat_taken} moved by the number of seat_taken 409s",
                    "metric +" + (long) dTaken + ", clients saw " + n(wantTaken) + tolText);
            checkMetric(Math.abs(dReplay - replays) <= tol,
                    "metrics: reservations_declined_total{reason=idempotent_replay} moved by the number of replays",
                    "metric +" + (long) dReplay + ", clients saw " + n(replays) + tolText);
            checkMetric(dLimit == 0, "metrics: no per_user_limit declines in the storm", "metric +" + (long) dLimit);
            checkMetric(Math.abs(dCancelled - churn.cancelled) <= tol, "metrics: reservations_cancelled_total moved by the number of cancellations",
                    "metric +" + (long) dCancelled + ", clients saw " + n(churn.cancelled) + tolText);
            checkMetric(dErrors == 0, "metrics: server_errors_total did not move", "metric +" + (long) dErrors);
            String gauge = "seats_available{show_id=\"" + showId + "\"}";
            Double g = metricsAfter.get(gauge);
            if (g == null) {
                warn("seats_available gauge for this show is not exported (only the most recent shows are)");
            } else {
                check(g.longValue() == last.available, "metrics: seats_available gauge == GET /shows/{id}.available",
                        "gauge " + g.longValue() + ", API " + last.available);
            }
        } else if (metricsBefore != null) {
            check(false, "GET /metrics is readable after the storm");
        }
        if (sampleRequestId != null) {
            note("every response carries X-Request-Id; look one up with  GET " + cfg.url + "/logs?request_id=" + sampleRequestId);
        }
    }

    // ------------------------------------------------------------------------------------------- churn

    private static final class ChurnOutcome {
        final Set<String> cancelledSeats = new HashSet<>();
        final Set<String> rebookedSeats = new HashSet<>();
        int cancelled = 0;
        int rebooked = 0;
        int rebookDeclined = 0;
    }

    /**
     * Cancels a sample of the storm's single-seat reservations (each cancel sent twice at once) while a fresh user races
     * to re-book the very same seat. Whichever wins, the end state must be consistent.
     */
    private ChurnOutcome phaseChurn(String showId, Map<Integer, List<Result>> byAttempt, List<Attempt> attempts,
            String[] tokens) throws Exception {
        ChurnOutcome out = new ChurnOutcome();
        section("[5] Churn: cancels racing re-bookings of the same seats");
        List<Result> candidates = new ArrayList<>();
        for (Attempt a : attempts) {
            if (a.seats.size() != 1) {
                continue;
            }
            for (Result r : byAttempt.get(a.index)) {
                if (r.outcome == Outcome.CREATED) {
                    candidates.add(r);
                }
            }
        }
        Collections.shuffle(candidates, new Random(cfg.seed ^ 0x5DEECE66DL));
        int count = Math.min(cfg.churn, candidates.size());
        if (count == 0) {
            note("nothing to do");
            return out;
        }
        List<Result> picked = candidates.subList(0, count);
        String[] late = mintTokens(count, "late-" + runId + "-");
        String reservePath = "/shows/" + showId + "/reserve";

        List<CompletableFuture<Resp>> cancelA = new ArrayList<>();
        List<CompletableFuture<Resp>> cancelB = new ArrayList<>();
        List<CompletableFuture<Resp>> rebook = new ArrayList<>();
        Semaphore permits = new Semaphore(cfg.concurrency);
        List<CompletableFuture<Resp>> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Result r = picked.get(i);
            String owner = tokens[r.attempt];
            String cancelPath = "/reservations/" + r.reservationId + "/cancel";
            CompletableFuture<Resp> a = throttled(permits, "POST", cancelPath, owner, null, null);
            CompletableFuture<Resp> b = throttled(permits, "POST", cancelPath, owner, null, null);
            CompletableFuture<Resp> c = throttled(permits, "POST", reservePath, late[i], "late-" + runId + "-" + i,
                    seatsBody(r.seats));
            cancelA.add(a);
            cancelB.add(b);
            rebook.add(c);
            all.add(a);
            all.add(b);
            all.add(c);
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0])).join();

        int bad = 0;
        int serverErrors = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Result r = picked.get(i);
            Resp a = cancelA.get(i).join();
            Resp b = cancelB.get(i).join();
            Resp c = rebook.get(i).join();
            serverErrors += (a.status >= 500 || a.status < 0 ? 1 : 0) + (b.status >= 500 || b.status < 0 ? 1 : 0)
                    + (c.status >= 500 || c.status < 0 ? 1 : 0);
            boolean aOk = a.status == 200;
            boolean bOk = b.status == 200;
            int fresh = (aOk && !"true".equalsIgnoreCase(a.replay) ? 1 : 0) + (bOk && !"true".equalsIgnoreCase(b.replay) ? 1 : 0);
            boolean resent = a.retries + b.retries > 0; // a lost answer can turn "the" fresh cancel into a replay
            if (!(aOk && bOk && (fresh == 1 || (fresh == 0 && resent)))) {
                bad++;
                problems.add("reservation " + r.reservationId + ": cancel answers were HTTP " + a.status + " / " + b.status
                        + " (fresh=" + fresh + ")");
            } else {
                out.cancelled++;
                out.cancelledSeats.add(r.seats.get(0));
            }
            Outcome o = classify(c);
            if (o == Outcome.REPLAY && c.retries > 0) {
                o = Outcome.CREATED; // applied, the answer was lost, the re-send replayed it
            }
            if (o == Outcome.CREATED) {
                out.rebooked++;
                out.rebookedSeats.add(r.seats.get(0));
            } else if (o == Outcome.SEAT_TAKEN) {
                out.rebookDeclined++;
            } else {
                bad++;
                problems.add("re-booking " + r.seats.get(0) + " answered " + o.label + " (HTTP " + c.status + ")");
            }
        }
        check(serverErrors == 0, "zero 5xx / connection failures during the churn", serverErrors + " problem(s)");
        check(bad == 0, "each reservation is cancelled exactly once (the duplicate cancel is a replay); each re-booking is 201 or 409 seat_taken",
                bad + " problem(s)" + sample(problems));
        note(count + " cancel pair" + (count == 1 ? "" : "s") + " raced against " + count + " re-booking" + (count == 1 ? "" : "s") + ": "
                + out.rebooked + " won the seat after the cancel, " + out.rebookDeclined + " arrived too early and were declined");
        return out;
    }

    private CompletableFuture<Resp> throttled(Semaphore permits, String method, String path, String bearer, String key, String body)
            throws InterruptedException {
        permits.acquire();
        return api.send(method, path, bearer, key, body, cfg.timeoutSeconds).whenComplete((r, e) -> permits.release());
    }

    // =============================================================================================== results

    private enum Outcome {
        CREATED("201 created"),
        REPLAY("200 idempotent replay"),
        SEAT_TAKEN("409 seat_taken"),
        PER_USER_LIMIT("409 per_user_limit"),
        KEY_CONFLICT("409 idempotency_key_conflict"),
        OTHER_409("409 other"),
        SERVER_ERROR("5xx server error"),
        TRANSPORT("connection failure / timeout"),
        UNEXPECTED("other status");

        final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private static Outcome classify(Resp r) {
        if (r.status < 0) {
            return Outcome.TRANSPORT;
        }
        if (r.status >= 500) {
            return Outcome.SERVER_ERROR;
        }
        if (r.status == 201) {
            return Outcome.CREATED;
        }
        if (r.status == 200) {
            return "true".equalsIgnoreCase(r.replay) ? Outcome.REPLAY : Outcome.UNEXPECTED;
        }
        if (r.status == 409) {
            String code = errorCode(r.body);
            if ("seat_taken".equals(code)) {
                return Outcome.SEAT_TAKEN;
            }
            if ("per_user_limit".equals(code)) {
                return Outcome.PER_USER_LIMIT;
            }
            if ("idempotency_key_conflict".equals(code)) {
                return Outcome.KEY_CONFLICT;
            }
            return Outcome.OTHER_409;
        }
        return Outcome.UNEXPECTED;
    }

    /** One HTTP response, digested. */
    private static final class Result {
        final int attempt;
        final Outcome outcome;
        final int status;
        final long nanos;
        final int retries;
        final String requestId;
        final String detail;
        final String reservationId;
        final String userId;
        final String showId;
        final List<String> seats;
        final long amount;
        final List<String> unavailable;

        private Result(int attempt, Outcome outcome, Resp r, String detail, String reservationId, String userId, String showId,
                List<String> seats, long amount, List<String> unavailable) {
            this.attempt = attempt;
            this.outcome = outcome;
            this.status = r == null ? -1 : r.status;
            this.nanos = r == null ? 0 : r.nanos;
            this.retries = r == null ? 0 : r.retries;
            this.requestId = r == null ? null : r.requestId;
            this.detail = detail;
            this.reservationId = reservationId;
            this.userId = userId;
            this.showId = showId;
            this.seats = seats;
            this.amount = amount;
            this.unavailable = unavailable;
        }

        @SuppressWarnings("unchecked")
        static Result of(int attempt, Resp r, Throwable failure) {
            if (r == null) {
                return new Result(attempt, Outcome.TRANSPORT, null, String.valueOf(failure), null, null, null,
                        Collections.<String>emptyList(), 0, Collections.<String>emptyList());
            }
            Outcome o = classify(r);
            String detail = r.status < 0 ? r.transportError : "HTTP " + r.status + " " + abbreviate(r.body);
            String rid = null;
            String uid = null;
            String sid = null;
            List<String> seats = Collections.emptyList();
            long amount = -1;
            List<String> unavailable = Collections.emptyList();
            if (o == Outcome.CREATED || o == Outcome.REPLAY) {
                Map<String, Object> m = obj(r.body);
                if (m != null) {
                    rid = str(m, "reservation_id");
                    uid = str(m, "user_id");
                    sid = str(m, "show_id");
                    Object s = m.get("seats");
                    if (s instanceof List) {
                        seats = strings((List<Object>) s);
                    }
                    Object a = m.get("amount_paise");
                    if (a instanceof Long) {
                        amount = (Long) a;
                    }
                }
                if (rid == null) {
                    o = Outcome.UNEXPECTED;
                }
            } else if (o == Outcome.SEAT_TAKEN) {
                Map<String, Object> m = obj(r.body);
                Object e = m == null ? null : m.get("error");
                if (e instanceof Map) {
                    Object s = ((Map<String, Object>) e).get("seats");
                    if (s instanceof List) {
                        unavailable = strings((List<Object>) s);
                    }
                }
            }
            return new Result(attempt, o, r, detail, rid, uid, sid, seats, amount, unavailable);
        }
    }

    private static String describe(List<Result> rs) {
        StringBuilder sb = new StringBuilder();
        for (Result r : rs) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(r.outcome.label);
        }
        return sb.toString();
    }

    private static String firstProblems(Result[] results, Outcome a, Outcome b) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Result r : results) {
            if ((r.outcome == a || r.outcome == b) && shown < 3) {
                sb.append(shown == 0 ? "; e.g. " : " | ").append(r.detail);
                if (r.requestId != null) {
                    sb.append(" (request ").append(r.requestId).append(")");
                }
                shown++;
            }
        }
        return sb.toString();
    }

    private static String sample(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        List<String> head = items.subList(0, Math.min(3, items.size()));
        return "; e.g. " + String.join(" | ", head);
    }

    private static String diffHint(Set<String> a, Set<String> b) {
        Set<String> onlyA = new HashSet<>(a);
        onlyA.removeAll(b);
        Set<String> onlyB = new HashSet<>(b);
        onlyB.removeAll(a);
        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            return "";
        }
        return "; only in service: " + new ArrayList<>(onlyA).subList(0, Math.min(3, onlyA.size())) + ", only in client ledger: "
                + new ArrayList<>(onlyB).subList(0, Math.min(3, onlyB.size()));
    }

    private static double ms(long[] sortedNanos, double q) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sortedNanos.length - 1, Math.max(0, Math.ceil(q * sortedNanos.length) - 1));
        return sortedNanos[idx] / 1e6;
    }

    private static double delta(Map<String, Double> after, Map<String, Double> before, String series) {
        return after.getOrDefault(series, 0.0) - before.getOrDefault(series, 0.0);
    }

    private Thread progressThread(CountDownLatch done, int total, long startNanos) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(2_000);
                    long finished = total - done.getCount();
                    double secs = (System.nanoTime() - startNanos) / 1e9;
                    System.out.printf(Locale.ROOT, "    ... %s / %s done (%.0f req/s)%n", n(finished), n(total), finished / secs);
                }
            } catch (InterruptedException e) {
                // storm finished
            }
        }, "burst-progress");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // =============================================================================================== service helpers

    private Resp call(String method, String path, String bearer, String idemKey, String body) {
        Resp r = api.send(method, path, bearer, idemKey, body, cfg.timeoutSeconds).join();
        if (sampleRequestId == null && r.requestId != null) {
            sampleRequestId = r.requestId;
        }
        return r;
    }

    private static String newKey() {
        return "probe-" + UUIDS.next();
    }

    private String mintToken(String userId) {
        Resp r = call("POST", "/auth/token", null, null, "{\"user_id\":\"" + userId + "\"}");
        String token = r.status == 200 ? str(obj(r.body), "token") : null;
        if (token == null) {
            throw new Abort("could not get a token for " + userId + " from POST /auth/token: " + (r.status < 0 ? r.transportError : "HTTP " + r.status)
                    + " (token issuance must be enabled on the target: ALLOW_TOKEN_MINT=true)");
        }
        return token;
    }

    /** Mints {@code count} tokens for users {@code prefix + i}, 200 at a time. */
    private String[] mintTokens(int count, String prefix) throws InterruptedException {
        String[] tokens = new String[count];
        Semaphore permits = new Semaphore(Math.min(200, cfg.concurrency));
        CountDownLatch done = new CountDownLatch(count);
        AtomicInteger failed = new AtomicInteger();
        StringBuilder firstError = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            permits.acquire();
            api.send("POST", "/auth/token", null, null, "{\"user_id\":\"" + prefix + i + "\"}", cfg.timeoutSeconds)
                    .whenComplete((r, e) -> {
                        try {
                            String token = r != null && r.status == 200 ? str(obj(r.body), "token") : null;
                            if (token == null) {
                                if (failed.getAndIncrement() == 0) {
                                    synchronized (firstError) {
                                        firstError.append(r == null ? String.valueOf(e) : (r.status < 0 ? r.transportError : "HTTP " + r.status));
                                    }
                                }
                            } else {
                                tokens[idx] = token;
                            }
                        } finally {
                            permits.release();
                            done.countDown();
                        }
                    });
        }
        done.await();
        if (failed.get() > 0) {
            throw new Abort(failed.get() + " of " + count + " token requests failed (first: " + firstError + ")");
        }
        return tokens;
    }

    private String createShow(String name, int seats, int limit, long price, String prefix) {
        Resp r = call("POST", "/shows", cfg.adminToken, null, showBody(name, seats, limit, price, prefix));
        Map<String, Object> m = r.status == 201 ? obj(r.body) : null;
        if (m == null || str(m, "id") == null) {
            throw new Abort("POST /shows failed: " + (r.status < 0 ? r.transportError : "HTTP " + r.status + " " + abbreviate(r.body))
                    + (r.status == 401 || r.status == 403 ? " (is ADMIN_TOKEN the admin credential of this deployment?)" : ""));
        }
        return str(m, "id");
    }

    private static String showBody(String name, int seats, int limit, long price, String prefix) {
        StringBuilder sb = new StringBuilder(64 + seats * 9);
        sb.append("{\"name\":").append(quote(name)).append(",\"price_paise\":").append(price).append(",\"per_user_limit\":")
                .append(limit).append(",\"seats\":[");
        int width = Math.max(4, String.valueOf(seats).length());
        for (int i = 1; i <= seats; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append('"').append(String.format(Locale.ROOT, "%s%0" + width + "d", prefix, i)).append('"');
        }
        return sb.append("]}").toString();
    }

    private static String seatsBody(List<String> seats) {
        StringBuilder sb = new StringBuilder("{\"seats\":[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(seats.get(i)));
        }
        return sb.append("]}").toString();
    }

    /** The service's view of one show. */
    private static final class ShowView {
        long price;
        int total;
        int available;
        int held;
        int confirmed;
        final List<String> labels = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        String statusOf(String label) {
            int i = labels.indexOf(label);
            return i < 0 ? null : statuses.get(i);
        }

        Set<String> confirmedSet() {
            Set<String> s = new HashSet<>();
            for (int i = 0; i < labels.size(); i++) {
                if ("confirmed".equals(statuses.get(i))) {
                    s.add(labels.get(i));
                }
            }
            return s;
        }

        List<String> availableLabels() {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < labels.size(); i++) {
                if ("available".equals(statuses.get(i))) {
                    l.add(labels.get(i));
                }
            }
            return l;
        }
    }

    @SuppressWarnings("unchecked")
    private ShowView fetchShow(String id) {
        Resp r = call("GET", "/shows/" + id, null, null, null);
        Map<String, Object> m = r.status == 200 ? obj(r.body) : null;
        if (m == null) {
            throw new Abort("GET /shows/" + id + " failed: " + (r.status < 0 ? r.transportError : "HTTP " + r.status));
        }
        ShowView v = new ShowView();
        v.price = lng(m, "price_paise");
        v.total = (int) lng(m, "total_seats");
        v.available = (int) lng(m, "available");
        v.held = (int) lng(m, "held");
        v.confirmed = (int) lng(m, "confirmed");
        Object seats = m.get("seats");
        if (seats instanceof List) {
            for (Object o : (List<Object>) seats) {
                if (o instanceof Map) {
                    v.labels.add(str((Map<String, Object>) o, "seat"));
                    v.statuses.add(str((Map<String, Object>) o, "status"));
                }
            }
        }
        return v;
    }

    private Map<String, Double> fetchMetrics() {
        Resp r = call("GET", "/metrics", null, null, null);
        if (r.status != 200 || r.body == null) {
            return null;
        }
        Map<String, Double> out = new HashMap<>();
        for (String line : r.body.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) {
                continue;
            }
            try {
                out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1).trim()));
            } catch (NumberFormatException ignored) {
                // not a sample line
            }
        }
        return out;
    }

    // =============================================================================================== summary

    private int summary(long startedNanos) {
        System.out.println();
        System.out.println("=".repeat(78));
        if (!warnings.isEmpty()) {
            System.out.println(warnings.size() + " warning(s):");
            for (String w : warnings) {
                System.out.println("  - " + w);
            }
        }
        if (failures.isEmpty()) {
            System.out.printf(Locale.ROOT, "RESULT: PASS - all %d checks passed in %s%n", checks, secs(System.nanoTime() - startedNanos));
            return 0;
        }
        System.out.printf(Locale.ROOT, "RESULT: FAIL - %d of %d checks failed:%n", failures.size(), checks);
        for (String f : failures) {
            System.out.println("  - " + f);
        }
        return 1;
    }

    // =============================================================================================== http

    static final class Resp {
        final int status;
        final String body;
        final String replay;
        final String requestId;
        final long nanos;
        final String transportError;
        /** How many times the request had to be re-sent (same Idempotency-Key) after a connection failure. */
        final int retries;

        Resp(int status, String body, String replay, String requestId, long nanos, String transportError, int retries) {
            this.status = status;
            this.body = body;
            this.replay = replay;
            this.requestId = requestId;
            this.nanos = nanos;
            this.transportError = transportError;
            this.retries = retries;
        }

        Resp with(long totalNanos, int totalRetries) {
            return new Resp(status, body, replay, requestId, totalNanos, transportError, totalRetries);
        }
    }

    static final class Api {
        /** Re-sends after a connection-level failure (never after an HTTP response, whatever its status). */
        private static final int MAX_RETRIES = 3;

        private final HttpClient client;
        private final ExecutorService executor;
        private final String base;
        /** Total re-sends so far; any non-zero value is reported, because a re-sent request may have been applied already. */
        final AtomicInteger transportRetries = new AtomicInteger();

        Api(Config cfg) {
            this.executor = Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2), r -> {
                Thread t = new Thread(r, "burst-http");
                t.setDaemon(true);
                return t;
            });
            this.client = HttpClient.newBuilder()
                    .version(cfg.http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(Math.min(30, cfg.timeoutSeconds)))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(executor)
                    .build();
            this.base = cfg.url;
        }

        /**
         * Sends a request and never completes exceptionally: a failure becomes a Resp with status -1.
         *
         * <p>A connection-level failure (reset, EOF on a stale keep-alive connection, timeout) is retried with the SAME
         * Idempotency-Key. That is exactly what the key is for: if the first attempt was in fact applied, the retry
         * returns the original reservation (200 + Idempotent-Replay) instead of booking twice.
         */
        CompletableFuture<Resp> send(String method, String path, String bearer, String idemKey, String body, int timeoutSeconds) {
            CompletableFuture<Resp> result = new CompletableFuture<>();
            attempt(result, method, path, bearer, idemKey, body, timeoutSeconds, 0, System.nanoTime());
            return result;
        }

        private void attempt(CompletableFuture<Resp> result, String method, String path, String bearer, String idemKey, String body,
                int timeoutSeconds, int retries, long firstStart) {
            sendOnce(method, path, bearer, idemKey, body, timeoutSeconds).whenComplete((r, e) -> {
                if (r.status >= 0 || retries >= MAX_RETRIES) {
                    result.complete(r.with(System.nanoTime() - firstStart, retries));
                    return;
                }
                transportRetries.incrementAndGet();
                long delayMs = 100L * (1L << (2 * retries)) + (System.nanoTime() & 0x3F); // 100, 400, 1600 ms (+ jitter)
                CompletableFuture.runAsync(
                        () -> attempt(result, method, path, bearer, idemKey, body, timeoutSeconds, retries + 1, firstStart),
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS, executor));
            });
        }

        private CompletableFuture<Resp> sendOnce(String method, String path, String bearer, String idemKey, String body,
                int timeoutSeconds) {
            final long t0 = System.nanoTime();
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Accept", "application/json");
                if (bearer != null) {
                    b.header("Authorization", "Bearer " + bearer);
                }
                if (idemKey != null) {
                    b.header("Idempotency-Key", idemKey);
                }
                if (body != null) {
                    b.header("Content-Type", "application/json");
                    b.method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                return client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).handle((r, e) -> {
                    long dt = System.nanoTime() - t0;
                    if (e != null || r == null) {
                        Throwable root = e;
                        while (root != null && root.getCause() != null) {
                            root = root.getCause();
                        }
                        String what = root == null ? "no response" : root.getClass().getSimpleName() + ": " + root.getMessage();
                        return new Resp(-1, null, null, null, dt, what, 0);
                    }
                    return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Idempotent-Replay").orElse(null),
                            r.headers().firstValue("X-Request-Id").orElse(null), dt, null, 0);
                });
            } catch (RuntimeException e) {
                return CompletableFuture.completedFuture(new Resp(-1, null, null, null, 0, e.toString(), 0));
            }
        }
    }

    // =============================================================================================== small utilities

    private static final class UUIDS {
        static String next() {
            return java.util.UUID.randomUUID().toString();
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "..." : t;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static List<String> strings(List<Object> in) {
        List<String> out = new ArrayList<>(in.size());
        for (Object o : in) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(String body) {
        if (body == null) {
            return null;
        }
        try {
            Object o = Json.parse(body);
            return o instanceof Map ? (Map<String, Object>) o : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static long lng(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Long ? (Long) v : (v instanceof Double ? ((Double) v).longValue() : -1L);
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(String body) {
        Map<String, Object> m = obj(body);
        Object e = m == null ? null : m.get("error");
        return e instanceof Map ? str((Map<String, Object>) e, "code") : null;
    }

    /** A small, strict JSON reader (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i = 0;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != p.s.length()) {
                throw new IllegalArgumentException("trailing characters at " + p.i);
            }
            return v;
        }

        private Object value() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + (i - 1));
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = next();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + (i - 1));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
        }

        private Object number() {
            int start = i;
            boolean fractional = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    fractional = true;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("unexpected character '" + s.charAt(i) + "' at " + i);
            }
            String t = s.substring(start, i);
            return fractional ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        }

        private void literal(String word) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("bad literal at " + i);
            }
            i += word.length();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + (i - 1));
            }
        }
    }
}
