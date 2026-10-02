package com.example.paytm.seatManagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.paytm.seatManagement.domain.ReservationRecord;
import com.example.paytm.seatManagement.domain.ReserveOutcome;
import com.example.paytm.seatManagement.domain.ShowInfo;
import com.example.paytm.seatManagement.domain.ShowState;
import com.example.paytm.seatManagement.support.MiniJson;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ViewsTest {

    private static final ShowInfo SHOW =
            new ShowInfo(UUID.randomUUID(), "friday \"night\"", 25000L, 4, 3, Instant.parse("2026-10-01T10:00:00Z"));

    @Test
    void freshShowListsEverySeatAvailableAndReconciles() {
        List<String> seats = Arrays.asList("A1", "A2", "A3");
        Map<String, Object> m = MiniJson.object(Views.show(Views.freshState(SHOW, seats)));
        assertEquals(SHOW.id().toString(), m.get("id"));
        assertEquals("friday \"night\"", m.get("name"));
        assertEquals(25000L, m.get("price_paise"));
        assertEquals(3L, m.get("total_seats"));
        long sum = (Long) m.get("available") + (Long) m.get("held") + (Long) m.get("confirmed");
        assertEquals(m.get("total_seats"), sum);
        Map<?, ?> counts = (Map<?, ?>) m.get("counts");
        assertEquals(3L, counts.get("available"));
        List<?> list = (List<?>) m.get("seats");
        assertEquals(3, list.size());
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals("A1", first.get("seat"));
        assertEquals("available", first.get("status"));
    }

    @Test
    void countsOnlyViewOmitsSeats() {
        ShowState st = new ShowState(SHOW, 1, 0, 2, null, null);
        Map<String, Object> m = MiniJson.object(Views.show(st));
        assertFalse(m.containsKey("seats"));
        assertEquals(2L, m.get("confirmed"));
    }

    @Test
    void perSeatStatusNamesAreMapped() {
        ShowState st = new ShowState(SHOW, 1, 1, 1, Arrays.asList("A1", "A2", "A3"),
                new byte[] {ShowState.AVAILABLE, ShowState.HELD, ShowState.CONFIRMED});
        List<?> list = (List<?>) MiniJson.object(Views.show(st)).get("seats");
        assertEquals("held", ((Map<?, ?>) list.get(1)).get("status"));
        assertEquals("confirmed", ((Map<?, ?>) list.get(2)).get("status"));
    }

    @Test
    void reservationBodyMatchesTheContract() {
        ReservationRecord r = new ReservationRecord(UUID.randomUUID(), SHOW.id(), "alice", "h", Arrays.asList("A12"),
                25000L, ReservationRecord.CONFIRMED, Instant.parse("2026-10-01T10:00:01Z"), null);
        Map<String, Object> m = MiniJson.object(Views.reservation(r));
        assertEquals(r.id().toString(), m.get("reservation_id"));
        assertEquals(SHOW.id().toString(), m.get("show_id"));
        assertEquals("alice", m.get("user_id"));
        assertEquals(Arrays.asList("A12"), m.get("seats"));
        assertEquals(25000L, m.get("amount_paise"));
        assertEquals("confirmed", m.get("status"));
        assertFalse(m.containsKey("cancelled_at"));
    }

    @Test
    void declinedBodiesAreCleanAndMachineReadable() {
        Map<String, Object> taken = MiniJson.object(Views.declined(ReserveOutcome.seatTaken(Arrays.asList("A12"))));
        assertEquals("declined", taken.get("status"));
        assertEquals("seat_taken", taken.get("reason"));
        Map<?, ?> err = (Map<?, ?>) taken.get("error");
        assertEquals("seat_taken", err.get("code"));
        assertEquals(Arrays.asList("A12"), err.get("seats"));

        Map<String, Object> limit = MiniJson.object(Views.declined(ReserveOutcome.perUserLimit(4)));
        assertEquals("per_user_limit", limit.get("reason"));
        assertEquals(4L, ((Map<?, ?>) limit.get("error")).get("limit"));

        Map<String, Object> conflict = MiniJson.object(Views.declined(ReserveOutcome.keyConflict()));
        assertEquals("idempotency_key_conflict", conflict.get("reason"));
        assertTrue(conflict.containsKey("request_id"));
    }
}
