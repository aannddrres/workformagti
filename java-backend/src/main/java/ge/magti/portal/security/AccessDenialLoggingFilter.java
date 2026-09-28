package ge.magti.portal.security;

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
            }
        }
    }
}
