package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.common.ApiException;
import com.example.paytm.seatManagement.common.Json;
import com.example.paytm.seatManagement.config.AppSettings;
import com.example.paytm.seatManagement.domain.ShowCounts;
import com.example.paytm.seatManagement.domain.ShowRepository;
import com.example.paytm.seatManagement.observability.LogCapture;
import com.example.paytm.seatManagement.observability.Metrics;
import com.example.paytm.seatManagement.observability.PromText;
import com.example.paytm.seatManagement.observability.ReadinessService;
import com.example.paytm.seatManagement.observability.RuntimeMetrics;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Health, readiness, metrics, logs and a self-describing index. All public and read-only. */
@RestController
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    private static final MediaType PROM_TEXT = MediaType.parseMediaType(PromText.CONTENT_TYPE);

    private final ReadinessService readiness;
    private final Metrics metrics;
    private final ShowRepository shows;
    private final LogCapture logs;
    private final AppSettings settings;
    private final DataSource dataSource;

    public OpsController(ReadinessService readiness, Metrics metrics, ShowRepository shows, LogCapture logs,
            AppSettings settings, DataSource dataSource) {
        this.readiness = readiness;
        this.metrics = metrics;
        this.shows = shows;
        this.logs = logs;
        this.settings = settings;
        this.dataSource = dataSource;
    }

    /** Liveness: the process is up and answering. Deliberately does NOT touch the database. */
    @GetMapping("/healthz")
    public ResponseEntity<String> healthz() {
        return Http.json(200, "{\"status\":\"UP\"}");
    }

    /** Readiness: a real SELECT 1 against the database; fails closed (503) if it cannot be done or on shutdown. */
    @GetMapping("/readyz")
    public ResponseEntity<String> readyz() {
        if (readiness.isShuttingDown()) {
            return Http.json(503, "{\"status\":\"NOT_READY\",\"reason\":\"shutting_down\"}");
        }
        if (!readiness.databaseReachable()) {
            return Http.json(503, "{\"status\":\"NOT_READY\",\"database\":\"DOWN\"}");
        }
        return Http.json(200, "{\"status\":\"READY\",\"database\":\"UP\"}");
    }

    /**
     * Prometheus metrics. Business counters are process-local; seat gauges are computed from the database at
     * scrape time (one statement, one snapshot) so they always reconcile with GET /shows/{id}.
     */
    @GetMapping("/metrics")
    public ResponseEntity<String> metrics() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(metrics.renderApp());
        appendSeatGauges(sb);
        sb.append(RuntimeMetrics.render(dataSource));
        return ResponseEntity.ok().contentType(PROM_TEXT).body(sb.toString());
    }

    private void appendSeatGauges(StringBuilder sb) {
        List<ShowCounts> counts = null;
        try {
            counts = shows.recentCounts(settings.metricsMaxShows());
        } catch (RuntimeException e) {
            log.warn("seat gauges unavailable: {}", e.getClass().getSimpleName());
        }
        PromText.header(sb, "seat_gauges_up", "gauge", "1 if the seat gauges below were read from the database.");
        PromText.sample(sb, "seat_gauges_up", "", counts == null ? 0 : 1);
        if (counts == null) {
            return;
        }
        PromText.header(sb, "seats_available", "gauge",
                "Seats currently available, per show (most recent shows only). Equals GET /shows/{id}.available.");
        for (ShowCounts c : counts) {
            PromText.sample(sb, "seats_available", PromText.label("show_id", c.showId().toString()), c.available());
        }
        PromText.header(sb, "seats_held", "gauge", "Seats currently held, per show.");
        for (ShowCounts c : counts) {
            PromText.sample(sb, "seats_held", PromText.label("show_id", c.showId().toString()), c.held());
        }
        PromText.header(sb, "seats_confirmed", "gauge", "Seats currently confirmed, per show.");
        for (ShowCounts c : counts) {
            PromText.sample(sb, "seats_confirmed", PromText.label("show_id", c.showId().toString()), c.confirmed());
        }
        PromText.header(sb, "seats_total", "gauge", "Total seats, per show. available + held + confirmed == total.");
        for (ShowCounts c : counts) {
            PromText.sample(sb, "seats_total", PromText.label("show_id", c.showId().toString()), c.total());
        }
    }

    /**
     * The most recent log events (newest last) as newline-delimited JSON, e.g.
     * {@code /logs?limit=500} or {@code /logs?request_id=<id from an X-Request-Id response header>}.
     */
    @GetMapping("/logs")
    public ResponseEntity<String> logs(@RequestParam(name = "limit", required = false) String limitParam,
            @RequestParam(name = "request_id", required = false) String requestId) {
        if (!settings.publicLogsEnabled()) {
            throw new ApiException(404, "not_found", "Public log access is disabled on this deployment");
        }
        int limit = 200;
        if (limitParam != null) {
            try {
                limit = Math.max(1, Math.min(5000, Integer.parseInt(limitParam.trim())));
            } catch (NumberFormatException e) {
                throw RequestValidator.bad("limit must be an integer");
            }
        }
        if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
            throw RequestValidator.bad("invalid request_id");
        }
        List<String> lines = logs.snapshot(limit, requestId);
        StringBuilder sb = new StringBuilder(lines.size() * 256 + 1);
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return ResponseEntity.ok().contentType(Http.NDJSON).body(sb.toString());
    }

    /** Self-describing entry point for humans and tools. */
    @GetMapping("/")
    public ResponseEntity<String> index() {
        String json = new Json.Obj()
                .str("service", "seat-reservation")
                .str("docs", "See README.md in the repository")
                .raw("auth", new Json.Obj()
                        .str("users", "POST /auth/token {\"user_id\":\"alice\"} -> {\"token\":...}; send 'Authorization: Bearer <token>'")
                        .str("admin", "Authorization: Bearer <ADMIN_TOKEN> (only for POST /shows)")
                        .build())
                .raw("endpoints", "["
                        + Json.quote("POST /shows (admin)") + ","
                        + Json.quote("GET /shows/{id}[?seats=false]") + ","
                        + Json.quote("POST /shows/{id}/reserve (user; Idempotency-Key header or idempotency_key field)") + ","
                        + Json.quote("POST /reservations/{id}/cancel (owner)") + ","
                        + Json.quote("POST /auth/token") + ","
                        + Json.quote("GET /healthz") + ","
                        + Json.quote("GET /readyz") + ","
                        + Json.quote("GET /metrics") + ","
                        + Json.quote("GET /logs[?limit=N&request_id=ID]")
                        + "]")
                .build();
        return Http.json(200, json);
    }
}
