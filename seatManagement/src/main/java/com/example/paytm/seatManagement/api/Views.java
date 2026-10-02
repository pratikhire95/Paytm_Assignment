package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.common.ErrorJson;
import com.example.paytm.seatManagement.common.Json;
import com.example.paytm.seatManagement.domain.ReservationRecord;
import com.example.paytm.seatManagement.domain.ReserveOutcome;
import com.example.paytm.seatManagement.domain.ShowInfo;
import com.example.paytm.seatManagement.domain.ShowState;
import com.example.paytm.seatManagement.observability.Metrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire format of every response body. One place, so the contract is easy to review. */
public final class Views {

    private Views() {
    }

    /** {"reservation_id","show_id","user_id","seats","amount_paise","status","created_at"[, "cancelled_at"]} */
    public static String reservation(ReservationRecord r) {
        Json.Obj o = new Json.Obj()
                .str("reservation_id", r.id().toString())
                .str("show_id", r.showId().toString())
                .str("user_id", r.userId())
                .strings("seats", r.seats())
                .num("amount_paise", r.amountPaise())
                .str("status", r.status())
                .str("created_at", r.createdAt().toString());
        if (r.cancelledAt() != null) {
            o.str("cancelled_at", r.cancelledAt().toString());
        }
        return o.build();
    }

    /**
     * Show with counts (top level AND under "counts", so either style of consumer finds them) and, when the state
     * carries them, every seat as {"seat","status"} in creation order.
     */
    public static String show(ShowState st) {
        ShowInfo show = st.show();
        int total = show.totalSeats();
        StringBuilder sb = new StringBuilder(st.hasSeats() ? 256 + st.labels().size() * 36 : 512);
        sb.append("{\"id\":");
        Json.appendQuoted(sb, show.id().toString());
        sb.append(",\"name\":");
        Json.appendQuoted(sb, show.name());
        sb.append(",\"price_paise\":").append(show.pricePaise());
        sb.append(",\"per_user_limit\":").append(show.perUserLimit());
        sb.append(",\"total_seats\":").append(total);
        sb.append(",\"available\":").append(st.available());
        sb.append(",\"held\":").append(st.held());
        sb.append(",\"confirmed\":").append(st.confirmed());
        sb.append(",\"counts\":{\"total\":").append(total)
                .append(",\"available\":").append(st.available())
                .append(",\"held\":").append(st.held())
                .append(",\"confirmed\":").append(st.confirmed()).append('}');
        sb.append(",\"created_at\":");
        Json.appendQuoted(sb, show.createdAt().toString());
        if (st.hasSeats()) {
            sb.append(",\"seats\":[");
            List<String> labels = st.labels();
            byte[] codes = st.statusCodes();
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"seat\":");
                Json.appendQuoted(sb, labels.get(i));
                sb.append(",\"status\":\"").append(ShowState.STATUS_NAMES[codes[i]]).append("\"}");
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /** A freshly created show: every seat available, no database round trip needed to say so. */
    public static ShowState freshState(ShowInfo show, List<String> labels) {
        return new ShowState(show, show.totalSeats(), 0, 0, labels, new byte[labels.size()]);
    }

    /**
     * Body for a 409 that did not create a reservation. Same envelope as every error, plus top-level
     * "status":"declined" and "reason" so a client can branch without digging.
     */
    public static String declined(ReserveOutcome o) {
        String reason = o.reason();
        String message;
        Map<String, Object> details = new LinkedHashMap<>();
        if (Metrics.SEAT_TAKEN.equals(reason)) {
            message = "One or more requested seats are no longer available";
            details.put("seats", o.seats());
        } else if (Metrics.PER_USER_LIMIT.equals(reason)) {
            message = "This request would exceed the per-user seat limit for this show";
            details.put("limit", o.limit());
        } else if (Metrics.IDEMPOTENCY_KEY_CONFLICT.equals(reason)) {
            message = "This idempotency key was already used for a different request";
        } else {
            message = "Request declined";
        }
        String envelope = ErrorJson.body(reason, message, details);
        // envelope ends with "}"; add the two top-level fields before it
        return envelope.substring(0, envelope.length() - 1)
                + ",\"status\":\"declined\",\"reason\":" + Json.quote(reason) + "}";
    }
}
