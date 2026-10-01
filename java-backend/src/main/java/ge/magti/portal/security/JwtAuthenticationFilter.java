package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mirrors security.py's {@code get_current_user} (security.py:258-310):
 * runs on every request, tries each candidate token, and populates
 * Spring Security's context from the DB row when one validates -- the
 * same "authorization is derived from the database on every request, not
 * trusted from token claims" design, so a role change or deactivation
 * takes effect on the very next request rather than waiting for the token
 * to expire.
 *
 * <p>SEC-14 extends that same principle to the token's own validity: the
 * {@code tv} claim is compared against {@code users.token_version}, so a
 * logout revokes a token that is still correctly signed and not yet
 * expired. Before this, logout cleared the cookie and nothing else -- a
 * copied bearer token outlived it by up to an hour.
 *
 * <p>Deliberately NOT ported here: issuing tokens (login/SSO), JIT
 * provisioning of test accounts, and the {@code TEST_EMAILS} bypass --
 * those are login-endpoint concerns (routers/auth.py), a separate,
 * later increment. This filter only ever validates a token that already
 * exists; it never creates a session or a user.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String SESSION_REQUEST_ATTRIBUTE = "portal.session_id";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final PortalSessionService sessionService;
    private final PermissionChecker permissionChecker;

    /** PortalProperties.Cookie#sessionCookieName: __Host-access_token wherever cookies are Secure. */
    private final String sessionCookieName;

    @Autowired
    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
            PortalSessionService sessionService, PortalProperties properties,
            PermissionChecker permissionChecker) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.permissionChecker = permissionChecker;
        this.sessionCookieName = properties.getSecurity().getCookie().sessionCookieName();
    }

    /** Keeps the DB-free filter tests focused on JWT validation. Browser
     * session enforcement is covered by the integration/session tests. */
    JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this(jwtService, userRepository, null, new PortalProperties(), null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        for (String token : candidateTokens(request)) {
            Optional<Claims> claims = jwtService.parseAndValidate(token);
            if (claims.isEmpty() || claims.get().getSubject() == null) {
                continue;
            }
            Optional<User> user = userRepository.findByEmail(claims.get().getSubject());
            if (user.isEmpty()) {
                continue;
            }
            if (JwtService.tokenVersionOf(claims.get()) != user.get().getTokenVersion()) {
                // SEC-14: this token was minted before the user logged out (or
                // before an admin cut their sessions), so it is dead even
                // though its signature and expiry are both still good. Treated
                // like any other invalid candidate -- fall through to the next
                // one, and end up unauthenticated if there is none.
                continue;
            }
            String sessionId = claims.get().get(JwtService.SESSION_ID_CLAIM, String.class);
            if (sessionId != null && sessionService != null
                    && !sessionService.validateAndTouch(sessionId, user.get().getId())) {
                continue;
            }
            if (sessionId == null && isCookieToken(request, token)) {
                // Browser cookies are always session-bound. Only explicit
                // bearer tokens without sid remain available for integration
                // clients and test automation.
                continue;
            }
            if (!user.get().isActive()) {
                // Mirrors get_current_user raising 403 immediately for a
                // deactivated account, even though the token itself is
                // validly signed -- a still-valid token must not survive
                // a deactivation.
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "User account is disabled");
                return;
            }
            authenticate(user.get());
            if (sessionId != null) {
                request.setAttribute(SESSION_REQUEST_ATTRIBUTE, sessionId);
            }
            break;
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(User user) {
        if (permissionChecker != null) {
            // Once per request, beside the role and deactivation re-read above,
            // so a grant or a DENY of content.manage changes what this person
            // can read on their very next request (User#seesAllContent).
            user.setSeesAllContent(permissionChecker.hasPermission(user, Permission.CONTENT_MANAGE));
        }
        List<GrantedAuthority> authorities = Stream.concat(
                        Stream.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                        user.getPermissions().stream().map(SimpleGrantedAuthority::new))
                .collect(Collectors.toList());

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    /**
     * Mirrors security.py's {@code _candidate_tokens}: the Authorization
     * header is tried first, then the httpOnly session
     * cookie -- same precedence, same reasoning (an explicit bearer token
     * wins over a possibly-stale cookie).
     */
    private List<String> candidateTokens(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String bearerToken = (header != null && header.startsWith("Bearer "))
                ? header.substring("Bearer ".length())
                : null;

        String cookieToken = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (sessionCookieName.equals(cookie.getName())) {
                    cookieToken = cookie.getValue();
                    break;
                }
            }
        }

        return Stream.of(bearerToken, cookieToken).filter(Objects::nonNull).toList();
    }

    private boolean isCookieToken(HttpServletRequest request, String token) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        for (Cookie cookie : cookies) {
            if (sessionCookieName.equals(cookie.getName()) && token.equals(cookie.getValue())) return true;
        }
        return false;
    }
}
