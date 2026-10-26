package koboolean.jevrouter.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error body for a refusal Console must be able to tell apart: Spring Boot's default error shape
 * plus a machine-readable {@code code} and the (content-free) reason. Every other error keeps
 * Spring Boot's default body.
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
}
