package com.example.paytm.seatManagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.paytm.seatManagement.common.ApiException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestValidatorTest {

    private static void rejects(Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(400, e.getStatus());
    }

    @Test
    void acceptsWellFormedSeatLists() {
        assertEquals(Arrays.asList("A12", "B-3", "Row_1.Seat:2#"),
                RequestValidator.seatList(Arrays.asList("A12", "B-3", "Row_1.Seat:2#"), 10));
    }

    @Test
    void rejectsMalformedSeatLists() {
        rejects(() -> RequestValidator.seatList(null, 10));
        rejects(() -> RequestValidator.seatList("A1", 10));
        rejects(() -> RequestValidator.seatList(Collections.emptyList(), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("A1", "A1"), 10)); // duplicate
        rejects(() -> RequestValidator.seatList(Arrays.asList("A1", 2), 10)); // non-string
        rejects(() -> RequestValidator.seatList(Arrays.asList("A1", null), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("../etc"), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("A 1"), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("A1'; DROP TABLE seats;--"), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("x".repeat(33)), 10));
        rejects(() -> RequestValidator.seatList(Arrays.asList("A1\nB2"), 10));
    }

    @Test
    void enforcesTheListSizeCap() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add("S" + i);
        }
        rejects(() -> RequestValidator.seatList(many, 10));
        assertEquals(10, RequestValidator.seatList(many.subList(0, 10), 10).size());
    }

    @Test
    void idempotencyKeyFromHeaderOrBody() {
        assertEquals("k-1", RequestValidator.idempotencyKey("k-1", null, null));
        assertEquals("k-2", RequestValidator.idempotencyKey(null, "k-2", null));
        assertEquals("k-3", RequestValidator.idempotencyKey(null, null, "k-3"));
        assertEquals("k-4", RequestValidator.idempotencyKey("k-4", null, "k-4"));
    }

    @Test
    void idempotencyKeyIsRequiredAndMustAgree() {
        ApiException missing = assertThrows(ApiException.class, () -> RequestValidator.idempotencyKey(null, null, null));
        assertEquals("idempotency_key_required", missing.getCode());
        rejects(() -> RequestValidator.idempotencyKey("a", null, "b"));
        rejects(() -> RequestValidator.idempotencyKey(null, null, 42));
        rejects(() -> RequestValidator.idempotencyKey("has space", null, null));
        rejects(() -> RequestValidator.idempotencyKey("x".repeat(129), null, null));
    }

    @Test
    void moneyIsAnIntegerNumberOfPaiseNeverAFloat() {
        assertEquals(25000L, RequestValidator.priceMinorUnits(25000));
        assertEquals(0L, RequestValidator.priceMinorUnits(0));
        assertEquals(25000L, RequestValidator.priceMinorUnits(25000L));
        rejects(() -> RequestValidator.priceMinorUnits(250.5));
        rejects(() -> RequestValidator.priceMinorUnits(25000.0));
        rejects(() -> RequestValidator.priceMinorUnits("25000"));
        rejects(() -> RequestValidator.priceMinorUnits(null));
        rejects(() -> RequestValidator.priceMinorUnits(-1));
        rejects(() -> RequestValidator.priceMinorUnits(RequestValidator.MAX_PRICE_PAISE + 1));
        rejects(() -> RequestValidator.priceMinorUnits(new BigInteger("99999999999999999999")));
    }

    @Test
    void perUserLimit() {
        assertEquals(4, RequestValidator.perUserLimit(null, 4));
        assertEquals(7, RequestValidator.perUserLimit(7, 4));
        rejects(() -> RequestValidator.perUserLimit(0, 4));
        rejects(() -> RequestValidator.perUserLimit(101, 4));
        rejects(() -> RequestValidator.perUserLimit("4", 4));
        rejects(() -> RequestValidator.perUserLimit(2.5, 4));
    }

    @Test
    void showName() {
        assertEquals("friday-night", RequestValidator.showName("  friday-night "));
        rejects(() -> RequestValidator.showName(""));
        rejects(() -> RequestValidator.showName("   "));
        rejects(() -> RequestValidator.showName(5));
        rejects(() -> RequestValidator.showName("bad\u0000name"));
        rejects(() -> RequestValidator.showName("x".repeat(201)));
    }

    @Test
    void malformedIdsAreSimplyNotFound() {
        UUID id = UUID.randomUUID();
        assertEquals(id, RequestValidator.uuidOrNotFound(id.toString(), "c", "m"));
        for (String bad : new String[] {null, "", "abc", "1-1-1-1-1", id.toString() + "x", "../..", id.toString().replace('-', '_')}) {
            ApiException e = assertThrows(ApiException.class, () -> RequestValidator.uuidOrNotFound(bad, "c", "m"));
            assertEquals(404, e.getStatus());
        }
    }
}
