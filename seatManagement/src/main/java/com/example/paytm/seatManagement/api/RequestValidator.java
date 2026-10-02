package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.common.ApiException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server-side validation of everything that arrives in a request. Allow-lists, not deny-lists: a value is
 * accepted only if it matches a strict shape, so nothing surprising can reach SQL parameters, logs or metrics.
 * (SQL is parameterised regardless; this is defence in depth and keeps garbage out of the data.)
 */
public final class RequestValidator {

    public static final Pattern SEAT_LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:#-]{0,31}$");
    public static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:~=@+-]{0,127}$");
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** 1,00,00,000 rupees in paise: a sanity cap, also keeps price * seats far from overflowing a long. */
    public static final long MAX_PRICE_PAISE = 1_000_000_000L;

    private RequestValidator() {
    }

    public static ApiException bad(String message) {
        return new ApiException(400, "invalid_request", message);
    }

    /** Parses a UUID path segment strictly; a malformed id is simply "not found". */
    public static UUID uuidOrNotFound(String raw, String notFoundCode, String notFoundMessage) {
        if (raw == null || !UUID_FORMAT.matcher(raw).matches()) {
            throw new ApiException(404, notFoundCode, notFoundMessage);
        }
        return UUID.fromString(raw);
    }

    /**
     * @param raw the JSON value of "seats"
     * @param maxSeats upper bound on the list length
     * @return the labels in request order; non-empty, well-formed, no duplicates
     */
    public static List<String> seatList(Object raw, int maxSeats) {
        if (!(raw instanceof List)) {
            throw bad("seats must be a JSON array of seat labels");
        }
        List<?> in = (List<?>) raw;
        if (in.isEmpty()) {
            throw bad("seats must contain at least one seat");
        }
        if (in.size() > maxSeats) {
            throw bad("seats may contain at most " + maxSeats + " entries");
        }
        List<String> out = new ArrayList<>(in.size());
        Set<String> seen = new HashSet<>(in.size() * 2);
        for (Object o : in) {
            if (!(o instanceof String)) {
                throw bad("every seat label must be a string");
            }
            String label = (String) o;
            if (!SEAT_LABEL.matcher(label).matches()) {
                throw bad("invalid seat label (allowed: letters, digits and _ . : # - ; max 32 characters)");
            }
            if (!seen.add(label)) {
                throw bad("duplicate seat label in request");
            }
            out.add(label);
        }
        return out;
    }

    /** Header wins over nothing; if both header and body carry a key they must agree. */
    public static String idempotencyKey(String header, String altHeader, Object bodyValue) {
        String h = blankToNull(header);
        if (h == null) {
            h = blankToNull(altHeader);
        }
        String b = null;
        if (bodyValue != null) {
            if (!(bodyValue instanceof String)) {
                throw bad("idempotency_key must be a string");
            }
            b = blankToNull((String) bodyValue);
        }
        if (h != null && b != null && !h.equals(b)) {
            throw bad("Idempotency-Key header and idempotency_key body field disagree");
        }
        String key = h != null ? h : b;
        if (key == null) {
            throw new ApiException(400, "idempotency_key_required",
                    "An idempotency key is required: send an Idempotency-Key header or an idempotency_key field");
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw bad("invalid idempotency key (allowed: letters, digits and _ . : ~ = @ + - ; max 128 characters)");
        }
        return key;
    }

    public static String showName(Object raw) {
        if (!(raw instanceof String)) {
            throw bad("name must be a string");
        }
        String name = ((String) raw).trim();
        if (name.isEmpty() || name.length() > 200) {
            throw bad("name must be 1 to 200 characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw bad("name must not contain control characters");
            }
        }
        return name;
    }

    /** Money is an integer number of paise. JSON numbers with a fraction or exponent are rejected outright. */
    public static long priceMinorUnits(Object raw) {
        long v;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            v = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger) {
            BigInteger bi = (BigInteger) raw;
            if (bi.bitLength() > 62) {
                throw bad("price_paise is out of range");
            }
            v = bi.longValue();
        } else {
            throw bad("price_paise must be an integer number of paise (no decimals, no strings)");
        }
        if (v < 0 || v > MAX_PRICE_PAISE) {
            throw bad("price_paise must be between 0 and " + MAX_PRICE_PAISE);
        }
        return v;
    }

    public static int perUserLimit(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)) {
            throw bad("per_user_limit must be an integer");
        }
        long v = ((Number) raw).longValue();
        if (v < 1 || v > 100) {
            throw bad("per_user_limit must be between 1 and 100");
        }
        return (int) v;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
