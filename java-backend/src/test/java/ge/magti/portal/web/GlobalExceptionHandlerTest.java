package ge.magti.portal.web;

import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR-08: there was no unhandled-exception handler at all before this. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/articles");
        request.setQueryString("secret=should-not-be-logged");
        return request;
    }

    @Test
    void anUnexpectedExceptionBecomesA500WithACorrelationId() {
        ResponseEntity<Map<String, String>> response =
                handler.handleUnexpected(new IllegalStateException("boom"), request());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("correlation_id"));
        assertEquals(8, response.getBody().get("correlation_id").length(),
                "short enough to read over the phone or paste into a ticket");
    }

    /**
     * The exception message is the thing most likely to carry internals -- a
     * JDBC URL, a constraint name, a file path. It belongs in the log, which
     * is what the correlation id is for, not in the response.
     */
    @Test
    void theResponseNeverEchoesTheExceptionMessage() {
        ResponseEntity<Map<String, String>> response = handler.handleUnexpected(
                new IllegalStateException("jdbc:oracle:thin:@db-prod-01:1521/ORCLPDB1"), request());

        assertNotNull(response.getBody());
        assertFalse(String.join(" ", response.getBody().values()).contains("jdbc:oracle"),
                "internal detail must not reach the client");
    }

    /** Attack and crash tests, 2026-10-02: what Oracle refuses, said as what it means. */
    @Test
    void aValueLongerThanItsColumnIs422NotAnUnexpectedError() {
        ResponseEntity<Map<String, String>> response = handler.handleDatabaseRefusal(
                new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                        new java.sql.SQLException("ORA-12899: value too large for column \"MAGTI\".\"TAGS\".\"NAME\" (actual: 150, maximum: 100)")),
                request());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        assertNull(response.getBody().get("correlation_id"));
    }

    /**
     * A rollback on a connection the database has already closed: Hibernate
     * reports "Connection is closed" and carries the ORA-03113 that explains
     * it as a suppressed exception. Read as a 500 it looked like a fault;
     * it is the database being away, and the browser should wait.
     */
    @Test
    void aDeadConnectionFoundOnlyInASuppressedExceptionIs503() {
        java.sql.SQLException closed = new java.sql.SQLException("Connection is closed");
        closed.addSuppressed(new java.sql.SQLRecoverableException("ORA-03113: database connection closed by peer"));
        ResponseEntity<Map<String, String>> response = handler.handleDatabaseRefusal(
                new org.springframework.orm.jpa.JpaSystemException(
                        new RuntimeException("Unable to rollback against JDBC Connection", closed)),
                request());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("service_unavailable", response.getBody().get("code"));
        assertEquals("5", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void anyOtherDatabaseFailureIsStillTheUnexpectedErrorWithACorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleDatabaseRefusal(
                new org.springframework.dao.DataIntegrityViolationException("ORA-02292: integrity constraint violated"),
                request());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody().get("correlation_id"));
    }

    @Test
    void correlationIdsAreDistinctPerRequest() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ids.add(GlobalExceptionHandler.newCorrelationId());
        }

        assertTrue(ids.size() > 490, "correlation ids collided far too often: " + ids.size() + "/500");
    }

    @Test
    void missingResourceRemainsA404WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleMissingResource(
                new NoResourceFoundException(HttpMethod.GET, "/api/removed", "/api/removed"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("მისამართი ვერ მოიძებნა", response.getBody().get("detail"));
        assertFalse(response.getBody().containsKey("correlation_id"));
    }

    @Test
    void oversizedCompleteResultBecomesAStable413WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleActiveUserCardinalityExceeded();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("detail").contains("უსაფრთხო დამუშავების ზღვარს"));
        assertFalse(response.getBody().containsKey("correlation_id"));
    }

    @Test
    void oversizedArticleEvidenceBecomesAStable413WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleArticleEvidenceCardinalityExceeded();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("detail").contains("უსაფრთხო დამუშავების ზღვარს"));
        assertFalse(response.getBody().containsKey("correlation_id"));
    }

    @Test
    void oversizedOrgDirectoryBecomesAStable413WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleOrgDirectoryCardinalityExceeded();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("detail").contains("უსაფრთხო დამუშავების ზღვარს"));
        assertFalse(response.getBody().containsKey("correlation_id"));
    }

    @Test
    void oversizedLegacyListBecomesAStable413WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleListCardinalityExceeded();

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("detail").contains("უსაფრთხო დამუშავების ზღვარს"));
        assertFalse(response.getBody().containsKey("correlation_id"));
    }

    /**
     * Found by an adversarial UAT pass: GET /api/articles/notanumber matched
     * /api/articles/{id}, failed to bind, and answered 500 with a correlation
     * id -- so a stale bookmark counted against the WS3-04 "API error rate
     * <1%" release gate.
     */
    @Test
    void anUnbindablePathParameterBecomesA400WithoutCorrelationId() {
        ResponseEntity<Map<String, String>> response = handler.handleUnbindableParameter(
                new MethodArgumentTypeMismatchException(
                        "notanumber", Long.class, "id", null, new NumberFormatException()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().containsKey("correlation_id"),
                "a client typo is not something an operator needs to investigate");
    }

    @Test
    void anUnbindableParameterResponseNeverEchoesTheOffendingValue() {
        ResponseEntity<Map<String, String>> response = handler.handleUnbindableParameter(
                new MethodArgumentTypeMismatchException(
                        "' OR 1=1 --", Long.class, "id", null, new NumberFormatException()));

        assertNotNull(response.getBody());
        assertFalse(String.join(" ", response.getBody().values()).contains("OR 1=1"),
                "reflecting caller input back is how a stable message becomes an injection surface");
    }

    @Test
    void aWrongMethodBecomesA405ThatNamesWhatIsAllowed() {
        ResponseEntity<Map<String, String>> response = handler.handleWrongMethod(
                new HttpRequestMethodNotSupportedException("GET", Set.of("POST", "PUT")));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        String allow = response.getHeaders().getFirst(HttpHeaders.ALLOW);
        assertNotNull(allow, "RFC 9110 requires a 405 to name the allowed methods");
        assertTrue(allow.contains("POST") && allow.contains("PUT"));
    }

    @Test
    void aWrongMethodWithNoKnownAlternativesStillAnswers405() {
        ResponseEntity<Map<String, String>> response = handler.handleWrongMethod(
                new HttpRequestMethodNotSupportedException("TRACE"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    /**
     * Found 2026-09-21: a form-encoded POST to the JSON login endpoint came
     * back as "an unexpected error occurred" plus a stack trace in the log.
     */
    @Test
    void anUnsupportedBodyFormatIsA415NamingWhatIsAccepted() {
        ResponseEntity<Map<String, String>> response = handler.handleUnsupportedMediaType(
                new HttpMediaTypeNotSupportedException(
                        MediaType.APPLICATION_FORM_URLENCODED, List.of(MediaType.APPLICATION_JSON)));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().getFirst(HttpHeaders.ACCEPT));
        assertNotNull(response.getBody());
        assertFalse(response.getBody().containsKey("correlation_id"),
                "a caller's mistake is not an incident an operator needs to find");
    }

    /**
     * A browser that leaves while the response is being written is not a
     * server fault, and there is nobody left to send a body to. It was logged
     * as ERROR with a full stack trace during the 2026-09-21 E2E run.
     */
    @Test
    void aClientThatDisconnectedGetsNoBodyAndNoIncident() {
        Exception tomcatAbort = new IllegalStateException("write failed",
                new org.apache.catalina.connector.ClientAbortException(
                        new IOException("An established connection was aborted")));

        assertNull(handler.handleUnexpected(tomcatAbort, request()));
        assertNull(handler.handleUnexpected(new AsyncRequestNotUsableException("gone"), request()));
        assertTrue(GlobalExceptionHandler.isClientDisconnect(tomcatAbort));
        assertFalse(GlobalExceptionHandler.isClientDisconnect(new IOException("disk full")),
                "only a disconnect is excused -- an ordinary IOException is still a server fault");
    }
}
