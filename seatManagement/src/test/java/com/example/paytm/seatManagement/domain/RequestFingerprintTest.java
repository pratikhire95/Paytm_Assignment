package com.example.paytm.seatManagement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    void stableLowercaseSha256Hex() {
        UUID show = UUID.fromString("3f2c1a52-0000-4000-8000-000000000001");
        String h = RequestFingerprint.of(show, Arrays.asList("A12", "A13"));
        assertEquals(64, h.length());
        assertTrue(h.matches("^[0-9a-f]{64}$"));
        assertEquals(h, RequestFingerprint.of(show, Arrays.asList("A12", "A13")));
    }

    @Test
    void differentSeatsOrShowsGiveDifferentFingerprints() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertNotEquals(RequestFingerprint.of(a, Arrays.asList("A12")), RequestFingerprint.of(a, Arrays.asList("A13")));
        assertNotEquals(RequestFingerprint.of(a, Arrays.asList("A12")), RequestFingerprint.of(b, Arrays.asList("A12")));
        assertNotEquals(RequestFingerprint.of(a, Arrays.asList("A1", "A2")), RequestFingerprint.of(a, Arrays.asList("A12")));
    }
}
