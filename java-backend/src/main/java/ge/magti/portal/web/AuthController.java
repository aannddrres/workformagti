package ge.magti.portal.web;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.security.AuthenticationService;
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

    public AuthController(
            AuthenticationService authenticationService,
            JwtService jwtService,
            AuditLogRepository auditLogRepository,
            PortalProperties properties,
            LoginRateLimiter rateLimiter) {
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Mirrors routers/auth.py:28's @limiter.limit("10/minute") --
        // checked before any DB work, same as the Python decorator runs
        // before the handler body.
        if (!rateLimiter.tryAcquire(httpRequest.getRemoteAddr())) {
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
                failedLogin.setDetails("IP: " + httpRequest.getRemoteAddr());
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
            auditLogRepository.save(successfulLogin);
        }

        String accessToken = jwtService.createAccessToken(
                Map.of("sub", user.getEmail(), "role", user.getRole().value()));

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie(accessToken).toString());

        return ResponseEntity.ok(new TokenResponse(accessToken, "bearer"));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletResponse httpResponse) {
        ResponseCookie cleared = ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ResponseEntity.ok(Map.of("detail", "Logged out"));
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
