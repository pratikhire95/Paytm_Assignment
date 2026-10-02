package com.example.paytm.seatManagement.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Canonical fingerprint of "what this reserve request asks for" (show + set of seats; order-insensitive).
 * Stored next to the idempotency key so that reusing a key for a different request is detected exactly.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /** @param sortedSeats seat labels in ascending order (callers sort once and reuse the list) */
    public static String of(UUID showId, List<String> sortedSeats) {
        StringBuilder canonical = new StringBuilder(64 + sortedSeats.size() * 8);
        canonical.append(showId).append('\n');
        for (int i = 0; i < sortedSeats.size(); i++) {
            if (i > 0) {
                canonical.append(',');
            }
            canonical.append(sortedSeats.get(i));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
