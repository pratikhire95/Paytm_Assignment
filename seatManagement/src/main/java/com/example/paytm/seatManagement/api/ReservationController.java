package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.auth.AuthFilter;
import com.example.paytm.seatManagement.auth.Principal;
import com.example.paytm.seatManagement.common.ApiException;
import com.example.paytm.seatManagement.config.AppSettings;
import com.example.paytm.seatManagement.domain.CancelOutcome;
import com.example.paytm.seatManagement.domain.ReservationService;
import com.example.paytm.seatManagement.domain.ReserveOutcome;
import com.example.paytm.seatManagement.observability.ObservabilityFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reserve and cancel. The acting user is ALWAYS the authenticated principal handed over by the AuthFilter; request
 * bodies are never consulted for identity (a spoofed user_id field is ignored and logged).
 */
@RestController
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservations;
    private final AppSettings settings;

    public ReservationController(ReservationService reservations, AppSettings settings) {
        this.reservations = reservations;
        this.settings = settings;
    }

    /**
     * Reserve seats - all-or-nothing: either every requested seat is booked for this user in one atomic step, or
     * none is and the response is a 409 naming the seats that were not available.
     *
     * <ul>
     *   <li>201 - reservation created</li>
     *   <li>200 + Idempotent-Replay: true - same key, same request: the original reservation, nothing new booked</li>
     *   <li>409 seat_taken | per_user_limit | idempotency_key_conflict - clean declines</li>
     * </ul>
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<String> reserve(@PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idemHeader,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String altIdemHeader,
            @RequestAttribute(name = AuthFilter.PRINCIPAL_ATTR) Principal principal,
            HttpServletRequest req) {
        UUID showId = RequestValidator.uuidOrNotFound(id, "show_not_found", "No such show");
        if (body == null) {
            throw RequestValidator.bad("A JSON body is required");
        }
        List<String> seats = RequestValidator.seatList(body.get("seats"), settings.maxSeatsPerRequest());
        String key = RequestValidator.idempotencyKey(idemHeader, altIdemHeader, body.get("idempotency_key"));
        noteIgnoredIdentityField(body, principal);

        ReserveOutcome outcome = reservations.reserve(principal.userId(), showId, seats, key);

        switch (outcome.kind()) {
            case CONFIRMED:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "confirmed");
                return Http.json(201, Views.reservation(outcome.reservation()));
            case REPLAY:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "idempotent_replay");
                return Http.json(200, Views.reservation(outcome.reservation()), "Idempotent-Replay", "true");
            case KEY_CONFLICT:
            case DECLINED:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "declined:" + outcome.reason());
                return Http.json(409, Views.declined(outcome));
            default:
                throw new IllegalStateException("unexpected outcome " + outcome.kind());
        }
    }

    /** Owner-only; repeating it is harmless (200 with the already-cancelled reservation). */
    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<String> cancel(@PathVariable("id") String id,
            @RequestAttribute(name = AuthFilter.PRINCIPAL_ATTR) Principal principal,
            HttpServletRequest req) {
        UUID reservationId = RequestValidator.uuidOrNotFound(id, "reservation_not_found", "No such reservation");
        CancelOutcome outcome = reservations.cancel(principal.userId(), reservationId);
        switch (outcome.kind()) {
            case CANCELLED:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "cancelled");
                return Http.json(200, Views.reservation(outcome.reservation()));
            case ALREADY_CANCELLED:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "cancel_replay");
                return Http.json(200, Views.reservation(outcome.reservation()), "Idempotent-Replay", "true");
            case FORBIDDEN:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "forbidden");
                log.warn("cancel refused: reservation belongs to another user");
                throw new ApiException(403, "forbidden", "You can only cancel your own reservations");
            case NOT_FOUND:
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "not_found");
                throw new ApiException(404, "reservation_not_found", "No such reservation");
            default:
                throw new IllegalStateException("unexpected outcome " + outcome.kind());
        }
    }

    /**
     * A client may try to smuggle an identity in the body ("user_id": "someone-else"). It is ignored - the token is
     * the only source of identity - but it is a signal worth a log line (the claimed value is deliberately not logged).
     */
    private static void noteIgnoredIdentityField(Map<String, Object> body, Principal principal) {
        Object claimed = body.get("user_id");
        if (claimed == null) {
            claimed = body.get("userId");
        }
        if (claimed != null && !principal.userId().equals(String.valueOf(claimed))) {
            log.warn("ignored a client-supplied user id that differs from the authenticated user");
        }
    }
}
