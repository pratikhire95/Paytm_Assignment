package com.example.paytm.seatManagement.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request failure that maps to a specific HTTP status and a machine-readable error code.
 *
 * <p>Stackless on purpose: these are expected control-flow outcomes (validation, 404, 503), not bugs,
 * and under a stampede the cost of filling in a stack trace per rejected request is pure waste.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details == null ? null : new LinkedHashMap<>(details);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** Extra structured fields to include in the error body (values: String, Number, Boolean, List, Map). */
    public Map<String, Object> getDetails() {
        return details;
    }
}
