package com.example.paytm.seatManagement.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void everyDeclineReasonIsExportedFromTheFirstScrape() {
        String text = new Metrics().renderApp();
        assertTrue(text.contains("reservations_confirmed_total 0"));
        for (String reason : new String[] {"seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_conflict"}) {
            assertTrue(text.contains("reservations_declined_total{reason=\"" + reason + "\"} 0"), reason);
        }
        assertTrue(text.contains("server_errors_total{type=\"db_unavailable\"} 0"));
        assertTrue(text.contains("# TYPE reservations_confirmed_total counter"));
    }

    @Test
    void countersMoveWithEvents() {
        Metrics m = new Metrics();
        m.reservationConfirmed();
        m.reservationConfirmed();
        m.reservationDeclined(Metrics.SEAT_TAKEN);
        m.reservationDeclined(Metrics.SEAT_TAKEN);
        m.reservationDeclined(Metrics.SEAT_TAKEN);
        m.reservationDeclined(Metrics.PER_USER_LIMIT);
        m.reservationCancelled();
        String text = m.renderApp();
        assertTrue(text.contains("reservations_confirmed_total 2"));
        assertTrue(text.contains("reservations_declined_total{reason=\"seat_taken\"} 3"));
        assertTrue(text.contains("reservations_declined_total{reason=\"per_user_limit\"} 1"));
        assertTrue(text.contains("reservations_cancelled_total 1"));
        assertEquals(2L, m.confirmedCount());
        assertEquals(3L, m.declinedCount(Metrics.SEAT_TAKEN));
    }

    @Test
    void httpHistogramIsCumulativeAndConsistent() {
        Metrics m = new Metrics();
        m.http("POST", "/shows/{id}/reserve", 201, 3_000_000L); // 3 ms
        m.http("POST", "/shows/{id}/reserve", 409, 40_000_000L); // 40 ms
        m.http("POST", "/shows/{id}/reserve", 409, 40_000_000L);
        String text = m.renderApp();
        assertTrue(text.contains("http_requests_total{method=\"POST\",route=\"/shows/{id}/reserve\",status=\"409\"} 2"));
        assertTrue(text.contains("http_request_duration_seconds_bucket{method=\"POST\",route=\"/shows/{id}/reserve\",le=\"0.005\"} 1"));
        assertTrue(text.contains("http_request_duration_seconds_bucket{method=\"POST\",route=\"/shows/{id}/reserve\",le=\"0.05\"} 3"));
        assertTrue(text.contains("http_request_duration_seconds_bucket{method=\"POST\",route=\"/shows/{id}/reserve\",le=\"+Inf\"} 3"));
        assertTrue(text.contains("http_request_duration_seconds_count{method=\"POST\",route=\"/shows/{id}/reserve\"} 3"));
    }

    @Test
    void labelValuesAreEscaped() {
        assertEquals("a\\\"b\\\\c\\nd", PromText.escape("a\"b\\c\nd"));
        assertEquals("plain", PromText.escape("plain"));
        assertEquals("route=\"x\"", PromText.label("route", "x"));
    }
}
