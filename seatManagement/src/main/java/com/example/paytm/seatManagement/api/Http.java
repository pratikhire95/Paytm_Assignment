package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.common.ErrorJson;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Small response helpers: every body is JSON text we built ourselves, always declared as UTF-8. */
public final class Http {

    public static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    public static final MediaType NDJSON = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);

    private Http() {
    }

    public static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(JSON).body(body);
    }

    public static ResponseEntity<String> json(int status, String body, String headerName, String headerValue) {
        return ResponseEntity.status(status).contentType(JSON).header(headerName, headerValue).body(body);
    }

    public static ResponseEntity<String> error(int status, String code, String message) {
        return json(status, ErrorJson.body(code, message, null));
    }
}
