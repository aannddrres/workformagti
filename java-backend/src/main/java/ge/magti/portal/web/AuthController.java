package ge.magti.portal.web;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.AuthenticationService;
import ge.magti.portal.security.ClientIpResolver;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.security.LoginRateLimiter;
import ge.magti.portal.util.TbilisiTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/auth.py's login/logout (routers/auth.py:27-86). Only the
 * password-credential path is ported here -- SSO mock endpoints are
 * deliberately deferred to a later increment. {@code forgot-password} is
 * NOT deferred, it's out for good -- password resets happen through a
 * separate channel, not this app (user decision, 2026-07-30).
 */
@RestController
public class AuthController {

    private final AuthenticationService authenticationService;
    private final JwtService jwtService;
    private final AuditLogRepository auditLogRepository;
    private final PortalProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final UserRepository userRepository;

    public AuthController(
            AuthenticationService authenticationService,
            JwtService jwtService,
            AuditLogRepository auditLogRepository,
            PortalProperties properties,
            LoginRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            UserRepository userRepository) {
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.userRepository = userRepository;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Mirrors routers/auth.py:28's @limiter.limit("10/minute") --
        // checked before any DB work, same as the Python decorator runs
        // before the handler body. SEC-04/PR-04: the key is the resolved
        // client address, not the socket peer (which was the proxy for
        // every user), and now includes the account being tried.
        String clientIp = clientIpResolver.resolve(httpRequest);
        if (!rateLimiter.tryAcquire(request.email(), clientIp)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("detail", "ძალიან ბევრი მცდელობა. სცადეთ მოგვიანებით."));
        }

        Optional<User> authenticated = authenticationService.authenticate(request.email(), request.password());

        if (authenticated.isEmpty()) {
            // Audit row only when the account exists (admin_id is a NOT
            // NULL FK) -- storing unknown attempted emails would both
            // violate the constraint and hoard enumeration data. Mirrors
            // routers/auth.py:48-56 exactly.
            authenticationService.findExistingAccount(request.email()).ifPresent(existing -> {
                AuditLog failedLogin = new AuditLog();
                failedLogin.setAdminId(existing.getId());
                failedLogin.setAction("LOGIN_FAILED");
                failedLogin.setItemType("user");
                failedLogin.setItemId(existing.getId());
                failedLogin.setTimestamp(TbilisiTime.now());
                failedLogin.setDetails("IP: " + clientIp);
                failedLogin.setIpAddress(clientIp);
                failedLogin.setUserAgent(truncatedUserAgent(httpRequest));
                auditLogRepository.save(failedLogin);
            });
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "არასწორი ელ. ფოსტა ან მომხმარებელი არ არსებობს"));
        }

        User user = authenticated.get();

        if (!request.email().startsWith("test_operator_")) {
            AuditLog successfulLogin = new AuditLog();
            successfulLogin.setAdminId(user.getId());
            successfulLogin.setAction("LOGIN");
            successfulLogin.setItemType("user");
            successfulLogin.setItemId(user.getId());
            successfulLogin.setTimestamp(TbilisiTime.now());
            successfulLogin.setIpAddress(clientIp);
            successfulLogin.setUserAgent(truncatedUserAgent(httpRequest));
            auditLogRepository.save(successfulLogin);
        }

        String accessToken = jwtService.createAccessTokenFor(user);

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie(accessToken).toString());

        return ResponseEntity.ok(new TokenResponse(accessToken, "bearer"));
    }

    /**
     * SEC-14. Clearing the cookie was all this used to do, which meant it
     * revoked nothing: the token itself stayed valid for the rest of its
     * 60 minutes, so a copy held anywhere else -- a shared workstation's
     * storage, a proxy log, a pasted Authorization header -- kept working
     * after the user believed they were out.
     *
     * <p>Now it also bumps {@code users.token_version}, which
     * {@link ge.magti.portal.security.JwtAuthenticationFilter} checks on
     * every request. That is deliberately "log out everywhere": for the
     * shared-workstation case the finding is actually about, ending only
     * the current session would leave the token on the shared machine
     * alive, which is the bug rather than the fix.
     *
     * <p>Still succeeds for an unauthenticated caller. Logging out when
     * you are already out is not an error, and returning 401 here would
     * turn "my token expired while the tab was open" into a dead-end for
     * the frontend's own logout path.
     */
    @PostMapping("/api/auth/logout")
    @Transactional
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal User user, HttpServletResponse httpResponse) {
        if (user != null) {
            // Not save(user): the principal is detached and Phase 6 made
            // User.lockVersion an @Version, so a concurrent edit to the same
            // row would answer a logout with 409 and leave the token valid.
            // See UserRepository.revokeIssuedTokens.
            userRepository.revokeIssuedTokens(user.getId());
        }
        ResponseCookie cleared = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ResponseEntity.ok(Map.of("detail", "Logged out"));
    }

    /** Mirrors audit_trail.py's actor_context_middleware bounding user_agent to
     *  the audit_logs.user_agent column's 500-char width before it's stored. */
    private static String truncatedUserAgent(HttpServletRequest httpRequest) {
        String userAgent = httpRequest.getHeader("User-Agent");
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
    }

    private ResponseCookie accessTokenCookie(String accessToken) {
        PortalProperties.Cookie cookieConfig = properties.getSecurity().getCookie();
        return ResponseCookie.from("access_token", accessToken)
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .maxAge(Duration.ofMinutes(properties.getSecurity().getJwt().getAccessTokenExpireMinutes()))
                .path("/")
                .build();
    }
}
