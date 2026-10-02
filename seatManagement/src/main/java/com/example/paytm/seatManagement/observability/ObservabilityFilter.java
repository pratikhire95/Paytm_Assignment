package com.example.paytm.seatManagement.observability;

import com.example.paytm.seatManagement.common.ErrorJson;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * First filter in the chain. For every request it:
 * <ul>
 *   <li>establishes the correlation id ({@code X-Request-Id}; a client-supplied id is honoured only if it is a
 *       boring token, otherwise one is generated - this also prevents log injection) and puts it in the
 *       logging MDC so every log line of the request carries it;</li>
 *   <li>adds defensive response headers;</li>
 *   <li>rejects absurdly large bodies before any parsing happens;</li>
 *   <li>records the HTTP metrics and writes one structured access-log line when the request completes.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    /** Request attribute set by controllers: what the request amounted to (confirmed, declined:seat_taken...). */
    public static final String ATTR_OUTCOME = "seat.outcome";
    /** Request attribute set by filters that reject before MVC routing, to keep the route label meaningful. */
    public static final String ATTR_ROUTE = "seat.route";

    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    private static final Pattern IP_LIKE = Pattern.compile("^[0-9a-fA-F:.]{3,45}$");
    /** A 100k-seat show is ~2 MB of JSON; anything beyond this is not a legitimate request. */
    private static final long MAX_BODY_BYTES = 8L * 1024 * 1024;

    private static final Logger access = LoggerFactory.getLogger("access");

    private final Metrics metrics;

    public ObservabilityFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String requestId = requestIdFor(req);
        MDC.put("request_id", requestId);
        resp.setHeader(REQUEST_ID_HEADER, requestId);
        addSecurityHeaders(req, resp);
        try {
            if (req.getContentLengthLong() > MAX_BODY_BYTES) {
                ErrorJson.write(resp, 413, "payload_too_large", "Request body is too large");
            } else {
                chain.doFilter(req, resp);
            }
        } finally {
            record(req, resp, System.nanoTime() - startNanos);
            MDC.clear();
        }
    }

    private void record(HttpServletRequest req, HttpServletResponse resp, long nanos) {
        int status = resp.getStatus();
        String method = methodLabel(req.getMethod());
        String route = routeOf(req);
        metrics.http(method, route, status, nanos);

        boolean noisy = route.equals("/healthz") || route.equals("/readyz") || route.equals("/metrics")
                || route.equals("/logs");
        if (noisy && status < 500) {
            if (!access.isDebugEnabled()) {
                return;
            }
        }
        Object outcome = req.getAttribute(ATTR_OUTCOME);
        MDC.put("method", method);
        MDC.put("route", route);
        MDC.put("status", Integer.toString(status));
        MDC.put("duration_ms", String.format(Locale.ROOT, "%.2f", nanos / 1e6));
        MDC.put("client_ip", clientIp(req));
        if (outcome != null) {
            MDC.put("outcome", outcome.toString());
        }
        if (noisy && status < 500) {
            access.debug("http_request");
        } else if (status >= 500) {
            access.error("http_request");
        } else {
            access.info("http_request");
        }
    }

    private static String requestIdFor(HttpServletRequest req) {
        String inbound = req.getHeader(REQUEST_ID_HEADER);
        if (inbound != null && REQUEST_ID.matcher(inbound).matches()) {
            return inbound;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return String.format("%016x%016x", r.nextLong(), r.nextLong());
    }

    private static void addSecurityHeaders(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        resp.setHeader("Referrer-Policy", "no-referrer");
        if ("https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"))) {
            resp.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    /** Route template (low cardinality) rather than the raw path. */
    private static String routeOf(HttpServletRequest req) {
        Object forced = req.getAttribute(ATTR_ROUTE);
        if (forced != null) {
            return forced.toString();
        }
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "unmatched" : pattern.toString();
    }

    private static String methodLabel(String m) {
        switch (m) {
            case "GET":
            case "POST":
            case "PUT":
            case "DELETE":
            case "PATCH":
            case "HEAD":
            case "OPTIONS":
                return m;
            default:
                return "OTHER";
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (IP_LIKE.matcher(first).matches()) {
                return first;
            }
        }
        return req.getRemoteAddr();
    }
}
