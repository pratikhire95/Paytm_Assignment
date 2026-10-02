package com.example.paytm.seatManagement.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.MDC;

/**
 * The one error envelope used everywhere (controllers, exception handler, servlet filters):
 *
 * <pre>{"error":{"code":"seat_taken","message":"...", ...details},"request_id":"..."}</pre>
 *
 * <p>Messages are fixed strings chosen by the server. Internal exception text, SQL, stack traces and file
 * paths never reach a client.
 */
public final class ErrorJson {

    private ErrorJson() {
    }

    public static String body(String code, String message, Map<String, Object> details) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"error\":{\"code\":");
        Json.appendQuoted(sb, code);
        sb.append(",\"message\":");
        Json.appendQuoted(sb, message);
        if (details != null) {
            for (Map.Entry<String, Object> e : details.entrySet()) {
                sb.append(',');
                Json.appendQuoted(sb, e.getKey());
                sb.append(':');
                Json.appendValue(sb, e.getValue());
            }
        }
        sb.append("},\"request_id\":");
        Json.appendQuoted(sb, MDC.get("request_id"));
        sb.append('}');
        return sb.toString();
    }

    /** Writes an error straight to the servlet response (used by filters, which run outside MVC). */
    public static void write(HttpServletResponse resp, int status, String code, String message) throws IOException {
        byte[] bytes = body(code, message, null).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }
}
