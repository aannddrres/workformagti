package ge.magti.portal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Correlates safe request timing with the nginx-generated request ID. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestTimingFilter.class);
    private static final String HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && incoming.matches("[0-9a-fA-F]{32}")
                ? incoming.toLowerCase(java.util.Locale.ROOT)
                : UUID.randomUUID().toString().replace("-", "");
        response.setHeader(HEADER, id);
        long start = System.nanoTime();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        } finally {
            logger.info("request_id={} method={} status={} duration_ms={}", id,
                    request.getMethod(), response.getStatus(), (System.nanoTime() - start) / 1_000_000.0);
        }
    }
}
