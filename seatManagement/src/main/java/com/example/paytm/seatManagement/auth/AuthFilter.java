package com.example.paytm.seatManagement.auth;

import com.example.paytm.seatManagement.common.ErrorJson;
import com.example.paytm.seatManagement.observability.ObservabilityFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Centralised, deny-by-default authentication and coarse authorisation.
 *
 * <p>Every route is protected unless it is explicitly listed as public in {@link #classify}. Identity is taken
 * ONLY from the verified bearer token and handed to controllers as a request attribute; no controller ever
 * reads a user id from a request body, header or query string. Fine-grained ownership checks (e.g. "only the
 * owner may cancel") are done next to the data, in the SQL.
 *
 * <p>Plain servlet filter instead of Spring Security: the whole scheme is "verify an HMAC, read two fields", the
 * API is stateless with no cookies (so CSRF does not apply), and this keeps the per-request overhead of the
 * stampede path to a few microseconds.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTR = "seat.principal";

    enum Access { PUBLIC, USER, ADMIN }

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    private final TokenService tokens;

    public AuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        Access access = classify(req.getMethod(), req.getRequestURI());
        if (access == Access.PUBLIC) {
            chain.doFilter(req, resp);
            return;
        }

        Principal principal = tokens.authenticate(bearerToken(req));
        if (principal == null) {
            req.setAttribute(ObservabilityFilter.ATTR_ROUTE, "(auth_rejected)");
            req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "unauthorized");
            resp.setHeader("WWW-Authenticate", "Bearer");
            ErrorJson.write(resp, 401, "unauthorized", "Missing or invalid bearer token");
            return;
        }
        MDC.put("user_id", principal.userId());
        if (access == Access.ADMIN && !principal.isAdmin()) {
            req.setAttribute(ObservabilityFilter.ATTR_ROUTE, "(auth_rejected)");
            req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "forbidden");
            log.warn("non-admin caller attempted an admin operation");
            ErrorJson.write(resp, 403, "forbidden", "This operation requires the admin credential");
            return;
        }

        req.setAttribute(PRINCIPAL_ATTR, principal);
        chain.doFilter(req, resp);
    }

    /** Package-private for unit tests. Anything not matched here is USER-level (deny by default). */
    static Access classify(String method, String path) {
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        if (read) {
            switch (path) {
                case "/":
                case "/healthz":
                case "/readyz":
                case "/metrics":
                case "/logs":
                    return Access.PUBLIC;
                default:
                    break;
            }
            // GET /shows/{id} only: exactly two segments, so /shows/{id}/anything stays protected.
            String prefix = "/shows/";
            if (path.startsWith(prefix) && path.length() > prefix.length()
                    && path.indexOf('/', prefix.length()) < 0) {
                return Access.PUBLIC;
            }
        }
        if ("POST".equals(method) && "/auth/token".equals(path)) {
            return Access.PUBLIC;
        }
        if ("POST".equals(method) && "/shows".equals(path)) {
            return Access.ADMIN;
        }
        return Access.USER;
    }

    private static String bearerToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }
}
