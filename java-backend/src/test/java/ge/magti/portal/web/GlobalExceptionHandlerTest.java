package ge.magti.portal.web;

import ge.magti.portal.user.UserDirectoryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
