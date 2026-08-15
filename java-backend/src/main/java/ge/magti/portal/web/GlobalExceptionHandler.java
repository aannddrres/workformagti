package ge.magti.portal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

/**
 * The backend's only unhandled-exception handler (audit PR-08).
 *
 * <p>There was none. Every unexpected exception reached the client as
 * Spring's default error body, and the two places the audit found where that
 * actually happens are not hypothetical: a {@code DataIntegrityViolation}
 * from a missing FK cascade surfaced as a bare 500 (BL-01), and nothing
 * correlated that 500 with anything in the logs -- of which there were six
 * statements in the entire backend.
 *
 * <p>What this changes is the pairing. The client gets a stable Georgian
 * message and a short correlation id; the log gets the same id with the full
 * stack trace. A user can read the id off the screen and an operator can
 * find the exact request, which is the difference between "it broke" and a
 * diagnosis.
 *
 * <p><b>Deliberately narrow.</b> It handles {@link Exception} only. Every
 * controller in this codebase returns its own {@code ResponseEntity} for
 * expected failures -- 401/403/404/409/413 with their own Georgian
 * {@code detail} strings -- and those never pass through here. Spring's own
 * {@code ResponseStatusException} and validation failures keep their
 * existing status codes and bodies too, because widening this to catch them
 * would flatten a carefully-built set of error responses into one generic
 * message.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Short enough to read aloud over the phone or type into a ticket, long
     * enough not to collide within a log-retention window. A full UUID is
     * neither.
     */
    static String newCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception, HttpServletRequest request) {
        String correlationId = newCorrelationId();

        // Method and path, never the body or query string: this logs on a
        // path that has already gone wrong, and an exception during login or
        // a password reset must not be the thing that writes a credential to
        // disk.
        logger.error("Unhandled exception [{}] on {} {}", correlationId,
                request.getMethod(), request.getRequestURI(), exception);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "detail", "მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა.",
                "correlation_id", correlationId));
    }
}
