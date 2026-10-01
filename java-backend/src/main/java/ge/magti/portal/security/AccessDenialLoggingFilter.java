package ge.magti.portal.security;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * ASVS V16.3.2: every refused request is logged with who asked for what.
 *
 * <p>Handlers refuse by returning 403 from their own require* guard (there is
 * not one @PreAuthorize in this module), and CSRF refusals come from Spring's
 * filter, so the one place that sees all of them is the response status. This
 * wraps the rest of the security chain, which is why the caller is still in
 * the SecurityContext when the answer comes back. A CSRF refusal happens
 * before the token is read, so it is logged without a user.
 */
final class AccessDenialLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AccessDenialLoggingFilter.class);

    private static final Set<String> CHANGES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Null where the filter is built without one; the log line is then the only record. */
    private final MutationAuditService audit;

    AccessDenialLoggingFilter() {
        this(null);
    }

    AccessDenialLoggingFilter(MutationAuditService audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            if (response.getStatus() == HttpServletResponse.SC_FORBIDDEN) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                Object principal = authentication == null ? null : authentication.getPrincipal();
                logger.warn("ACCESS_DENIED user={} method={} path={}",
                        principal instanceof User user ? user.getId() : "anonymous",
                        request.getMethod(), request.getRequestURI());
                if (audit != null && principal instanceof User user && CHANGES.contains(request.getMethod())) {
                    // A refused change by a signed-in person goes into the audit
                    // trail too. A content admin tried five edits after losing
                    // articles.edit and the trail recorded none of them
                    // (simulation, 2026-10-01). Reads stay in the log only.
                    try {
                        String target = request.getMethod() + " " + request.getRequestURI();
                        audit.recordResult(user, "ACCESS_DENIED", "request", 0L, target,
                                "DENIED", "403", null, Map.of("method", request.getMethod(),
                                        "path", request.getRequestURI()), null, null);
                    } catch (RuntimeException e) {
                        logger.warn("ACCESS_DENIED audit row not written: {}", e.toString());
                    }
                }
            }
        }
    }
}
