package ge.magti.portal.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTimingFilterTest {

    private final RequestTimingFilter filter = new RequestTimingFilter();

    @Test
    void nginxRequestIdIsReturnedUnchangedForLogCorrelation() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/favorites");
        var response = new MockHttpServletResponse();
        String id = "ab1234567890cd1234567890ef123456";
        request.addHeader("X-Request-ID", id);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(id, response.getHeader("X-Request-ID"));
    }

    @Test
    void malformedHeaderCannotInjectLogLines() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/health");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Request-ID", "spoofed\r\nsecret");

        filter.doFilter(request, response, new MockFilterChain());

        String id = response.getHeader("X-Request-ID");
        assertNotEquals("spoofed\r\nsecret", id);
        assertTrue(id.matches("[0-9a-f]{32}"));
    }
}
