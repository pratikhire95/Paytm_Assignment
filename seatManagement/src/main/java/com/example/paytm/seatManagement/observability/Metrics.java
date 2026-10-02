package com.example.paytm.seatManagement.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Process-local counters and histograms, rendered in Prometheus text format by {@code GET /metrics}.
 *
 * <p>Hand-rolled on purpose (a few LongAdders): the metric names and labels that reconcile with the API are
 * part of the contract, and there is no framework magic between a business event and the number you scrape.
 * All hot-path updates are lock-free.
 *
 * <p>Counters are per process and reset on restart (standard Prometheus semantics; use rate()/increase()).
 * Gauges that describe database state (seats available...) are NOT kept here: they are computed from the
 * database at scrape time so they always reconcile with {@code GET /shows/{id}}.
 */
@Component
public class Metrics {

    /** Decline reasons (also the {@code reason} label values). */
    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String IDEMPOTENCY_KEY_CONFLICT = "idempotency_key_conflict";

    /** Error types for 5xx accounting. */
    public static final String ERR_DB_UNAVAILABLE = "db_unavailable";
    public static final String ERR_INTERNAL = "internal";

    private static final String[] DECLINE_REASONS = {
        SEAT_TAKEN, PER_USER_LIMIT, IDEMPOTENT_REPLAY, IDEMPOTENCY_KEY_CONFLICT
    };
    private static final String[] ERROR_TYPES = {ERR_DB_UNAVAILABLE, ERR_INTERNAL};

    private static final double[] BOUNDS_SECONDS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};
    private static final String[] LE_LABELS = {
        "0.005", "0.01", "0.025", "0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "+Inf"
    };

    private final LongAdder confirmed = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final Map<String, LongAdder> declined = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errors = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> httpTotals = new ConcurrentHashMap<>();
    private final Map<String, Histogram> httpDurations = new ConcurrentHashMap<>();
    private final long startedAtMillis = System.currentTimeMillis();

    public Metrics() {
        // Pre-create every known series so that it is exported (as 0) from the first scrape.
        for (String r : DECLINE_REASONS) {
            declined.put(r, new LongAdder());
        }
        for (String t : ERROR_TYPES) {
            errors.put(t, new LongAdder());
        }
    }

    // ------------------------------------------------------------------ recording

    public void reservationConfirmed() {
        confirmed.increment();
    }

    public void reservationCancelled() {
        cancelled.increment();
    }

    public void reservationDeclined(String reason) {
        declined.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    public void error(String type) {
        errors.computeIfAbsent(type, k -> new LongAdder()).increment();
    }

    public void http(String method, String route, int status, long durationNanos) {
        httpTotals.computeIfAbsent(method + '|' + route + '|' + status, k -> new LongAdder()).increment();
        httpDurations.computeIfAbsent(method + '|' + route, k -> new Histogram()).observe(durationNanos);
    }

    // ------------------------------------------------------------------ test/inspection hooks

    public long confirmedCount() {
        return confirmed.sum();
    }

    public long declinedCount(String reason) {
        LongAdder a = declined.get(reason);
        return a == null ? 0L : a.sum();
    }

    // ------------------------------------------------------------------ rendering

    /** Business counters + HTTP request counters/latency histograms. */
    public String renderApp() {
        StringBuilder sb = new StringBuilder(4096);

        PromText.header(sb, "reservations_confirmed_total", "counter",
                "Reservations confirmed (HTTP 201). Increments only after the transaction commits.");
        PromText.sample(sb, "reservations_confirmed_total", "", confirmed.sum());

        PromText.header(sb, "reservations_declined_total", "counter",
                "Reservation requests that did not create a new reservation, by reason.");
        List<String> reasons = new ArrayList<>(declined.keySet());
        Collections.sort(reasons);
        for (String r : reasons) {
            PromText.sample(sb, "reservations_declined_total", PromText.label("reason", r), declined.get(r).sum());
        }

        PromText.header(sb, "reservations_cancelled_total", "counter", "Reservations cancelled by their owner.");
        PromText.sample(sb, "reservations_cancelled_total", "", cancelled.sum());

        PromText.header(sb, "server_errors_total", "counter",
                "Requests that ended in a 5xx, by cause. Anything but zero during a burst is a bug or an outage.");
        List<String> types = new ArrayList<>(errors.keySet());
        Collections.sort(types);
        for (String t : types) {
            PromText.sample(sb, "server_errors_total", PromText.label("type", t), errors.get(t).sum());
        }

        PromText.header(sb, "http_requests_total", "counter", "HTTP requests by method, route template and status.");
        List<String> keys = new ArrayList<>(httpTotals.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            String[] p = k.split("\\|", 3);
            PromText.sample(sb, "http_requests_total",
                    PromText.label("method", p[0]) + "," + PromText.label("route", p[1]) + ","
                            + PromText.label("status", p[2]),
                    httpTotals.get(k).sum());
        }

        PromText.header(sb, "http_request_duration_seconds", "histogram",
                "HTTP request latency (server side) by method and route template.");
        List<String> hkeys = new ArrayList<>(httpDurations.keySet());
        Collections.sort(hkeys);
        for (String k : hkeys) {
            String[] p = k.split("\\|", 2);
            String base = PromText.label("method", p[0]) + "," + PromText.label("route", p[1]);
            Histogram h = httpDurations.get(k);
            long cumulative = 0;
            for (int i = 0; i < LE_LABELS.length; i++) {
                cumulative += h.buckets[i].sum();
                PromText.sample(sb, "http_request_duration_seconds_bucket",
                        base + "," + PromText.label("le", LE_LABELS[i]), cumulative);
            }
            PromText.sample(sb, "http_request_duration_seconds_sum", base,
                    String.format(Locale.ROOT, "%.6f", h.sumNanos.sum() / 1e9));
            PromText.sample(sb, "http_request_duration_seconds_count", base, h.count.sum());
        }

        PromText.header(sb, "process_start_time_seconds", "gauge", "Start time of the process, unix epoch seconds.");
        PromText.sample(sb, "process_start_time_seconds", "", startedAtMillis / 1000);

        return sb.toString();
    }

    private static final class Histogram {
        final LongAdder[] buckets = new LongAdder[BOUNDS_SECONDS.length + 1];
        final LongAdder count = new LongAdder();
        final LongAdder sumNanos = new LongAdder();

        Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }

        void observe(long nanos) {
            double seconds = nanos / 1e9;
            int idx = BOUNDS_SECONDS.length; // +Inf
            for (int i = 0; i < BOUNDS_SECONDS.length; i++) {
                if (seconds <= BOUNDS_SECONDS[i]) {
                    idx = i;
                    break;
                }
            }
            buckets[idx].increment();
            count.increment();
            sumNanos.add(nanos);
        }
    }
}
