package com.example.paytm.seatManagement.api;

import com.example.paytm.seatManagement.common.ApiException;
import com.example.paytm.seatManagement.common.ErrorJson;
import com.example.paytm.seatManagement.observability.Metrics;
import com.example.paytm.seatManagement.observability.ObservabilityFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns every failure into the same JSON envelope. Principles:
 * <ul>
 *   <li>Expected failures (validation, 404, 403, 503) carry a stable machine-readable code.</li>
 *   <li>Anything unexpected becomes a generic 500 with a fixed message: no stack traces, SQL, hostnames or
 *       exception text ever reach the client; the details go to the server log (correlated by request id).</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Metrics metrics;

    public ApiExceptionHandler(Metrics metrics) {
        this.metrics = metrics;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApi(ApiException e, HttpServletRequest req) {
        req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, e.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.getStatus()).contentType(Http.JSON);
        if (e.getStatus() >= 500) {
            metrics.error(Metrics.ERR_DB_UNAVAILABLE);
            log.warn("request failed with {} ({})", e.getStatus(), e.getCode());
            response = response.header("Retry-After", "1");
        }
        return response.body(ErrorJson.body(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest req) {
        req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "invalid_json");
        return Http.error(400, "invalid_json", "The request body is missing or is not a valid JSON object");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleOther(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse) {
            // Spring MVC's own client errors: 404 unknown route, 405 wrong method, 415 wrong content type, ...
            ErrorResponse er = (ErrorResponse) e;
            int status = er.getStatusCode().value();
            if (status < 500) {
                req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "http_" + status);
                ResponseEntity.BodyBuilder response = ResponseEntity.status(status).contentType(Http.JSON);
                response = response.headers(er.getHeaders()); // e.g. Allow on a 405
                return response.body(ErrorJson.body(codeFor(status), messageFor(status), null));
            }
        }
        log.error("unhandled exception while serving request", e);
        metrics.error(Metrics.ERR_INTERNAL);
        req.setAttribute(ObservabilityFilter.ATTR_OUTCOME, "internal_error");
        return Http.error(500, "internal_error", "An unexpected error occurred");
    }

    private static String codeFor(int status) {
        switch (status) {
            case 400:
                return "invalid_request";
            case 404:
                return "not_found";
            case 405:
                return "method_not_allowed";
            case 406:
                return "not_acceptable";
            case 413:
                return "payload_too_large";
            case 415:
                return "unsupported_media_type";
            default:
                return "http_" + status;
        }
    }

    private static String messageFor(int status) {
        switch (status) {
            case 400:
                return "The request is invalid";
            case 404:
                return "The requested resource was not found";
            case 405:
                return "This method is not supported for this resource";
            case 406:
                return "The requested response format is not available";
            case 413:
                return "The request body is too large";
            case 415:
                return "Send the body as application/json";
            default:
                return "The request could not be processed";
        }
    }
}
