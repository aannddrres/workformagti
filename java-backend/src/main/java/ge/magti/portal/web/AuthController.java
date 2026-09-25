package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.AuthenticationService;
import ge.magti.portal.security.ClientIpResolver;
import ge.magti.portal.security.CorporateAuthClient;
import ge.magti.portal.security.CorporateIdentity;
import ge.magti.portal.security.CorporateLoginService;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.security.LoginRateLimiter;
import ge.magti.portal.security.PortalSessionService;
import ge.magti.portal.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
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
    private final MutationAuditService mutationAuditService;
    private final PortalProperties properties;
    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final UserRepository userRepository;
    private final PortalSessionService sessionService;
    private final CorporateLoginService corporateLoginService;
    private final TransactionTemplate transactionTemplate;

    public AuthController(
            AuthenticationService authenticationService,
            JwtService jwtService,
            MutationAuditService mutationAuditService,
            PortalProperties properties,
            LoginRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            UserRepository userRepository,
            PortalSessionService sessionService,
            CorporateLoginService corporateLoginService,
            TransactionTemplate transactionTemplate) {
        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
        this.mutationAuditService = mutationAuditService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
        this.corporateLoginService = corporateLoginService;
        this.transactionTemplate = transactionTemplate;
    }

    /** PO-26: one sentence for every failed sign-in, so the answer never says which part was wrong. */
    static final String LOGIN_FAILED_DETAIL = "ვერ მოხდა ავტორიზაცია";

    private static final String LOCAL_CHANNEL = "LOCAL_DEVELOPMENT_ONLY";
    private static final String CORPORATE_CHANNEL = "CORPORATE_OAUTH";

    /**
     * Deliberately not {@code @Transactional}. It was, and that put two things
     * in one database transaction that must not share one. The throttle's
     * recorded attempt stayed invisible to simultaneous attempts until the
     * whole sign-in committed, so a burst all counted "one" and all went
     * through. And the directory call kept a pooled connection checked out
     * for as long as the directory took -- up to its 5 s + 10 s timeouts,
     * out of a pool of 30 shared with every request in the portal. Now the
     * attempt commits on its own before the decision, the directory is asked
     * with no connection held, and only what must be atomic -- the account,
     * its session and its audit row -- shares a transaction
     * (ConcurrentLoginIntegrationTest).
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        boolean corporate = properties.getSecurity().getCorporate().isEnabled();
        if (properties.isProduction() && !corporate) {
            // PO-25: production has no local-password fallback. Without the
            // company login switched on there is simply no way in.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "detail", "კომპანიის ავტორიზაცია ჩართული არ არის. production-ში ადგილობრივი პაროლით შესვლა გამორთულია."));
        }
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

        // The demo and test personas keep their password-less bypass even with
        // the company login on -- it exists only outside production, behind
        // ALLOW_DEV_LOGIN (see AuthenticationService.isDevLoginAccount).
        if (corporate && !authenticationService.isDevLoginAccount(request.email())) {
            return corporateLogin(request, clientIp, httpRequest, httpResponse);
        }

        Optional<User> authenticated = authenticationService.authenticate(request.email(), request.password());
        if (authenticated.isEmpty()) {
            transactionTemplate.executeWithoutResult(status ->
                    recordFailure(request.email(), "AUTHENTICATION_REJECTED", LOCAL_CHANNEL, clientIp, httpRequest));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", LOGIN_FAILED_DETAIL));
        }
        return transactionTemplate.execute(status ->
                completeLogin(authenticated.get(), LOCAL_CHANNEL, Map.of(), clientIp, httpRequest, httpResponse));
    }

    /**
     * The company directory's answer, mapped to what the person sees. An
     * unreachable directory is a 503 and writes no failed-login row: it says
     * nothing about their password, and recording it as a failure would put
     * an outage into every account's security history.
     */
    private ResponseEntity<?> corporateLogin(
            LoginRequest request, String clientIp, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        CorporateAuthClient.Outcome answer = corporateLoginService.verify(request.email(), request.password());
        if (answer instanceof CorporateAuthClient.Unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "detail", "კომპანიის ავტორიზაციის სერვისი დროებით მიუწვდომელია. სცადეთ მოგვიანებით."));
        }
        if (answer instanceof CorporateAuthClient.Rejected) {
            transactionTemplate.executeWithoutResult(status ->
                    recordFailure(request.email(), "AUTHENTICATION_REJECTED", CORPORATE_CHANNEL, clientIp, httpRequest));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", LOGIN_FAILED_DETAIL));
        }
        CorporateIdentity identity = ((CorporateAuthClient.Authenticated) answer).identity();
        return transactionTemplate.execute(status -> {
            CorporateLoginService.Result result = corporateLoginService.provision(identity);
            if (result instanceof CorporateLoginService.Rejected) {
                recordFailure(request.email(), "ACCOUNT_DEACTIVATED", CORPORATE_CHANNEL, clientIp, httpRequest);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", LOGIN_FAILED_DETAIL));
            }
            CorporateLoginService.SignedIn signedIn = (CorporateLoginService.SignedIn) result;
            Map<String, Object> directory = new LinkedHashMap<>();
            if (signedIn.created()) {
                directory.put("account_created", true);
            }
            if (signedIn.previousRole() != null) {
                directory.put("directory_role_change",
                        signedIn.previousRole().value() + "->" + signedIn.user().getRole().value());
            }
            return completeLogin(signedIn.user(), CORPORATE_CHANNEL, directory, clientIp, httpRequest, httpResponse);
        });
    }

    /**
     * Audit row only when the account exists (admin_id is a NOT NULL FK) --
     * storing unknown attempted emails would both violate the constraint and
     * hoard enumeration data. Mirrors routers/auth.py:48-56 exactly.
     */
    private void recordFailure(
            String email, String reason, String channel, String clientIp, HttpServletRequest httpRequest) {
        authenticationService.findExistingAccount(email).ifPresent(existing -> mutationAuditService.recordResult(
                existing,
                "LOGIN_FAILED",
                "user",
                existing.getId(),
                existing.getName(),
                "FAILURE",
                reason,
                null,
                Map.of("authenticated", false, "auth_channel", channel),
                clientIp,
                truncatedUserAgent(httpRequest)));
    }

    private ResponseEntity<?> completeLogin(
            User user, String channel, Map<String, Object> extraDetails,
            String clientIp, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        var portalSession = sessionService.create(user, clientIp, truncatedUserAgent(httpRequest));
        String accessToken = jwtService.createAccessTokenFor(user, portalSession.getId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("authenticated", true);
        details.put("session_created", true);
        details.put("auth_channel", channel);
        details.put("role", user.getRole().value());
        details.putAll(extraDetails);
        mutationAuditService.recordResult(
                user,
                "LOGIN",
                "user",
                user.getId(),
                user.getName(),
                "SUCCESS",
                null,
                null,
                details,
                clientIp,
                truncatedUserAgent(httpRequest));

        httpResponse.addHeader(HttpHeaders.SET_COOKIE, accessTokenCookie(accessToken).toString());

        return ResponseEntity.ok(new TokenResponse(accessToken, "bearer"));
    }

    /**
     * Fail-closed seam for the production AD/SSO adapter. Until IT provides
     * the trusted identity-provider metadata and endpoints, the portal never
     * falls back to a local or temporary password.
     */
    @PostMapping("/api/auth/sso/start")
    public ResponseEntity<Map<String, String>> startCorporateSso() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "detail", "კომპანიის ავტორიზაციის სერვისი ჯერ არ არის დაკავშირებული. წვდომა არ გაიცა."));
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
    /**
     * Ends the caller's session, and every other session they hold.
     *
     * <p><b>Requires a live session (PO-20 / DEC-P02, 2026-08-31).</b> This
     * used to answer an anonymous caller too, so that a tab whose token had
     * expired could still ask the server to clear its httpOnly cookie --
     * JavaScript cannot delete that cookie itself.
     *
     * <p>That case no longer arises. The frontend ejects the operator before
     * they can sit in a dead tab: {@code IdleSessionService} signs them out
     * after thirty idle minutes, and {@code unauthorizedInterceptor} sends
     * them to the login page on the first 401 from any request -- which is
     * what a revoked session, a deactivated account, a role change or the
     * eight-hour maximum all produce.
     *
     * <p>What is given up is small and stated: with no live session the
     * server cannot clear the cookie, so a dead one stays in the browser
     * until its own expiry. It authenticates nothing -- the token inside it
     * is exactly the token that was just refused.
     */
    @PostMapping("/api/auth/logout")
    @Transactional
    public ResponseEntity<Map<String, String>> logout(
            @AuthenticationPrincipal User user, HttpServletRequest request, HttpServletResponse httpResponse) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        {
            String sessionId = (String) request.getAttribute(JwtAuthenticationFilter.SESSION_REQUEST_ATTRIBUTE);
            boolean sessionRevoked = sessionId != null && sessionService.revoke(sessionId, user.getId());
            long tokenVersionBefore = user.getTokenVersion();
            // Not save(user): the principal is detached and Phase 6 made
            // User.lockVersion an @Version, so a concurrent edit to the same
            // row would answer a logout with 409 and leave the token valid.
            // See UserRepository.revokeIssuedTokens.
            if (userRepository.revokeIssuedTokens(user.getId()) != 1) {
                throw new IllegalStateException("Authenticated user token revocation did not update exactly one row");
            }
            mutationAuditService.recordResult(
                    user,
                    "LOGOUT",
                    "user",
                    user.getId(),
                    user.getName(),
                    "SUCCESS",
                    null,
                    Map.of(
                            "token_version", tokenVersionBefore,
                            "session_present", sessionId != null),
                    Map.of(
                            "token_version", tokenVersionBefore + 1,
                            "session_revoked", sessionRevoked),
                    clientIpResolver.resolve(request),
                    truncatedUserAgent(request));
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
