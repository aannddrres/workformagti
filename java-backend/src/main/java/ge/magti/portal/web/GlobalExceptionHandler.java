package ge.magti.portal.web;

import ge.magti.portal.article.ArticleEvidenceCardinalityGuard;
import ge.magti.portal.history.HistoryPayloadGuard;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.user.UserDirectoryQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
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
 * call sites that parse client enums therefore handle them beside their
 * request contracts rather than weakening error classification globally.
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

    /**
     * A path or query parameter that will not bind to its declared type is a
     * client error, the parameter-shaped twin of the malformed body above.
     *
     * <p>Found by an adversarial UAT pass: {@code GET /api/articles/notanumber}
     * matches {@code /api/articles/{id}}, fails to parse as a Long, and
     * reached {@link #handleUnexpected} -- so a stale bookmark or a crawler
     * produced a 500 with a correlation id and a stack trace. The same held
     * for news, videos, and an integer overflow.
     *
     * <p>That mattered beyond tidiness: WS3-04 sets a hard release gate at
     * "API error rate <1%", and k6 fails the build on it, so client mistakes
     * were inflating the server-error rate. Real failures were also getting
     * harder to find in a log filling with these.
     *
     * <p>This is narrow on purpose and does <b>not</b> reopen the
     * {@code IllegalArgumentException → 400} question argued against above.
     * Spring raises this exception only while binding caller-supplied request
     * values, never from server state, so blaming the caller is correct here
     * in a way it is not there.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleUnbindableParameter(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "detail", "მოთხოვნის პარამეტრი არასწორია"));
    }

    /**
     * A single-valued parameter given twice (RepeatedParameterGuard, ASVS
     * V15.3.7). The caller's error for the same reason as above, and answered
     * the same way.
     */
    @ExceptionHandler(RepeatedParameterGuard.RepeatedParameterException.class)
    public ResponseEntity<Map<String, String>> handleRepeatedParameter(
            RepeatedParameterGuard.RepeatedParameterException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "detail", "მოთხოვნის პარამეტრი არასწორია"));
    }

    /**
     * The route exists but not for this method -- 405, and per RFC 9110 a 405
     * must name what is allowed, so the header is not optional decoration.
     *
     * <p>Same UAT finding as above: a GET on a POST-only path answered 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleWrongMethod(
            HttpRequestMethodNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        String[] supported = exception.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            response.header(HttpHeaders.ALLOW, String.join(", ", supported));
        }
        return response.body(Map.of("detail", "მოთხოვნის მეთოდი არ არის დაშვებული"));
    }

    /**
     * A body in a format the endpoint does not read -- form-encoded sent to a
     * JSON endpoint -- is the caller's mistake: 415, naming what is accepted,
     * the way the 405 above names what is allowed.
     *
     * <p>Found 2026-09-21: a form-encoded POST to {@code /api/auth/login}
     * answered "an unexpected error occurred" with a correlation id, and wrote
     * a full stack trace to the log as an unhandled exception.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        List<MediaType> supported = exception.getSupportedMediaTypes();
        if (!supported.isEmpty()) {
            response.header(HttpHeaders.ACCEPT, MediaType.toString(supported));
        }
        return response.body(Map.of("detail", "მოთხოვნის ფორმატი არ არის მხარდაჭერილი"));
    }

    /** Missing API/static routes are ordinary 404s, not unexpected server failures. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleMissingResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "detail", "მისამართი ვერ მოიძებნა"));
    }

    /**
     * A body over Spring's multipart limit (11MB) is the sender's mistake:
     * the same 413 and reason as a file over the application's own 10MB
     * check, not the catch-all's 500 and an ERROR-level stack trace.
     * Behind the production nginx the body is refused at 11MB before it
     * gets here; straight to the backend it was not
     * (UploadSizeLimitIntegrationTest).
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("detail", UploadController.TOO_LARGE_DETAIL));
    }

    /** Complete-result administrative views fail loudly instead of truncating. */
    @ExceptionHandler(UserDirectoryQueryService.UserDirectoryCardinalityExceededException.class)
    public ResponseEntity<Map<String, String>> handleActiveUserCardinalityExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", "მომხმარებლების რაოდენობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    /** Complete-result history/evidence views fail loudly instead of truncating. */
    @ExceptionHandler(ArticleEvidenceCardinalityGuard.ArticleEvidenceCardinalityExceededException.class)
    public ResponseEntity<Map<String, String>> handleArticleEvidenceCardinalityExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", "სტატიის ისტორიის ჩანაწერების რაოდენობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    /** Legacy full-history arrays have a hard aggregate CLOB budget. */
    @ExceptionHandler(HistoryPayloadGuard.HistoryPayloadExceededException.class)
    public ResponseEntity<Map<String, String>> handleHistoryPayloadExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", "ისტორიის სრული ტექსტის მოცულობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    /** Complete-result organization reference views fail loudly instead of truncating. */
    @ExceptionHandler(OrgDirectoryQueryService.OrgDirectoryCardinalityExceededException.class)
    public ResponseEntity<Map<String, String>> handleOrgDirectoryCardinalityExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", "ორგანიზაციული ჩანაწერების რაოდენობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    /** Legacy complete-result arrays fail loudly instead of truncating. */
    @ExceptionHandler(CompleteResultGuard.CompleteResultCardinalityExceededException.class)
    public ResponseEntity<Map<String, String>> handleListCardinalityExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "detail", "ჩანაწერების რაოდენობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (isClientDisconnect(exception)) {
            // The browser left -- a closed tab, a navigation away -- while the
            // response was still being written. Nothing on the server went
            // wrong and there is nobody left to answer, so there is no body
            // to build either. Logged as ERROR with a stack trace this looked
            // exactly like an outage in the log (seen during the 2026-09-21
            // E2E run on GET /api/articles).
            logger.debug("Client disconnected during {} {}", request.getMethod(), request.getRequestURI());
            return null;
        }
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

    /**
     * Spring's own wrapper for a response that can no longer be written, or
     * Tomcat's abort exception anywhere in the cause chain. Matched by class
     * name for Tomcat's, so this class does not tie itself to one servlet
     * container.
     */
    static boolean isClientDisconnect(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof AsyncRequestNotUsableException
                    || "org.apache.catalina.connector.ClientAbortException".equals(cause.getClass().getName())) {
                return true;
            }
        }
        return false;
    }
}
