package ge.magti.portal.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
 * <p><b>Deliberately narrow.</b> Apart from the concurrent-edit case below,
 * it handles {@link Exception} only. Every
 * controller in this codebase returns its own {@code ResponseEntity} for
 * expected failures -- 401/403/404/409/413 with their own Georgian
 * {@code detail} strings -- and those never pass through here. Spring's own
 * {@code ResponseStatusException} and validation failures keep their
 * existing status codes and bodies too, because widening this to catch them
 * would flatten a carefully-built set of error responses into one generic
 * message.
 *
 * <p><b>No {@code IllegalArgumentException} → 400 mapping, deliberately.</b>
 * Audit SEC-15 suggested one. It would have fixed the symptom it was written
 * about ({@code Role.fromValue} on a client-supplied string) and mislabelled
 * everything else: {@code IllegalArgumentException} is what
 * {@code Map.of(k, null)}, {@code Integer.parseInt} over a database value,
 * and half the JDK throw when <i>server</i> state is wrong. Turning those
 * into 400s would blame the caller for our own bugs, and — worse — silence
 * them, since a 400 carries no correlation id and writes no stack trace. The
 * one call site the audit named is fixed where it lives, in
 * {@code MessagingController.postBroadcast}, next to the three in
 * {@code UserController} that already did it that way.
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

    /**
     * BL-11. Two people saving the same article in the same instant is a
     * normal thing for humans to do, not a server fault -- so it gets a 409
     * and a sentence the editor can act on, rather than the generic 500
     * below. Before {@code Article} carried an optimistic lock this surfaced
     * as a unique-constraint violation on
     * {@code ux_article_history_article_version}, i.e. an opaque 500 with
     * the second editor's work silently gone.
     *
     * <p>No correlation id and no stack trace: nothing went wrong that an
     * operator needs to investigate.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentEdit(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        logger.info("Concurrent edit rejected on {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "detail", "ამ ჩანაწერს სხვამ თქვენზე ადრე შეცვალა. "
                        + "გთხოვთ, გადატვირთოთ გვერდი და ცვლილება თავიდან შეიტანოთ."));
    }

    /** Malformed or Bean-Validation-rejected request bodies are client errors. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> handleInvalidRequestBody(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "detail", "მოთხოვნის მონაცემები არასწორია"));
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
