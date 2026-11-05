package koboolean.router.error;

import com.inframesh.node.enums.DataRoutingPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.core.JacksonException;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error body for a refusal Console must be able to tell apart: Spring Boot's default error shape
 * plus a machine-readable {@code code} and the (content-free) reason. A body that cannot be read
 * is a 400 that names an invalid {@code dataRoutingPolicy}, as in the Laya Routers. Every other
 * error keeps Spring Boot's default body.
 */
@RestControllerAdvice
public class RoutingErrorHandler {

    @ExceptionHandler(RoutingRejectedException.class)
    public ResponseEntity<Map<String, Object>> routingRejected(RoutingRejectedException e, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", e.getStatusCode().value());
        body.put("error", HttpStatus.valueOf(e.getStatusCode().value()).getReasonPhrase());
        body.put("code", e.getCode());
        body.put("message", e.getReason());
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(e.getStatusCode()).body(body);
    }

    // OUTBOUND reports the same case as infra-node's MALFORMED_PAYLOAD. The parser's own message
    // is not echoed: it may quote the request content.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        String message = isAboutDataRoutingPolicy(e)
                ? "Malformed RoutingRequest: dataRoutingPolicy must be one of "
                        + Arrays.toString(DataRoutingPolicy.values()) + "."
                : "Malformed RoutingRequest.";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    private static boolean isAboutDataRoutingPolicy(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && jackson.getPath().stream()
                    .anyMatch(reference -> "dataRoutingPolicy".equals(reference.getPropertyName()))) {
                return true;
            }
        }
        return false;
    }
}
