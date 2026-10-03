package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-authentication-filter-unit-tests-1234567890";

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        PortalProperties properties = new PortalProperties();
        properties.getSecurity().getJwt().setSecret(TEST_SECRET);
        jwtService = new JwtService(properties);
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private User activeUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("Test User");
        user.setRole(role);
        user.setActive(true);
        return user;
    }

    @Test
    void validAuthorizationHeaderPopulatesSecurityContext() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "operator@magti.ge"));
        User user = activeUser("operator@magti.ge", Role.OPERATOR);
        when(userRepository.findByEmail("operator@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(user, auth.getPrincipal());
        verify(chain).doFilter(request, response);
    }

    @Test
    void cookieTokenWithoutSessionIdIsRejected() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "manager@magti.ge"));
        User user = activeUser("manager@magti.ge", Role.MANAGER);
        when(userRepository.findByEmail("manager@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("access_token", token)});

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void headerTakesPrecedenceOverCookie() throws Exception {
        String headerToken = jwtService.createAccessToken(Map.of("sub", "header.user@magti.ge"));
        String cookieToken = jwtService.createAccessToken(Map.of("sub", "cookie.user@magti.ge"));
        User headerUser = activeUser("header.user@magti.ge", Role.OPERATOR);
        when(userRepository.findByEmail("header.user@magti.ge")).thenReturn(Optional.of(headerUser));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + headerToken);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("access_token", cookieToken)});

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals(headerUser, auth.getPrincipal());
    }

    @Test
    void missingTokenLeavesContextEmptyButChainStillProceeds() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    /**
     * ASVS V14.2.1: a token in a URL ends up in access logs, browser history
     * and Referer headers. The filter reads only the header and the cookie,
     * so a valid token offered as a query parameter authenticates nobody.
     */
    @Test
    void aTokenInTheQueryStringIsIgnored() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "operator@magti.ge"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(null);
        when(request.getQueryString()).thenReturn("access_token=" + token + "&token=" + token);
        when(request.getParameter("access_token")).thenReturn(token);
        when(request.getParameter("token")).thenReturn(token);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void malformedTokenLeavesContextEmptyButChainStillProceeds() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer not-a-real-token");
        when(request.getCookies()).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void disabledAccountGetsImmediate401AndChainNeverRuns() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "disabled@magti.ge"));
        User disabledUser = activeUser("disabled@magti.ge", Role.OPERATOR);
        disabledUser.setActive(false);
        when(userRepository.findByEmail("disabled@magti.ge")).thenReturn(Optional.of(disabledUser));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        java.io.StringWriter body = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(body));
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        // 401 sends the browser to the login screen; the code lets it say why (2026-10-02).
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        org.junit.jupiter.api.Assertions.assertTrue(body.toString().contains("\"code\":\"account_disabled\""), body.toString());
        verify(chain, never()).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * A database that drops out for a moment must not end anyone's session.
     * The exception used to escape to Spring's /error page, which answered
     * 401, and the browser signs out on 401 -- every operator at once (crash
     * test, 2026-10-02). 503 keeps them signed in until it is back.
     */
    @Test
    void databaseOutageAnswers503AndNever401() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "operator@magti.ge"));
        when(userRepository.findByEmail("operator@magti.ge"))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("ORA-03113"));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        java.io.StringWriter body = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(body));
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(body.toString().contains("\"code\":\"service_unavailable\""), body.toString());
        verify(chain, never()).doFilter(any(), any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void unknownUserLeavesContextEmptyButChainStillProceeds() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "ghost@magti.ge"));
        when(userRepository.findByEmail("ghost@magti.ge")).thenReturn(Optional.empty());

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void authoritiesIncludeRolePrefixAndEachPermission() throws Exception {
        String token = jwtService.createAccessToken(Map.of("sub", "admin@magti.ge"));
        User admin = activeUser("admin@magti.ge", Role.SYSTEM_ADMIN);
        admin.setPermissions(java.util.Set.of("users.manage"));
        when(userRepository.findByEmail("admin@magti.ge")).thenReturn(Optional.of(admin));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        var authorityStrings = SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                .stream().map(Object::toString).toList();
        assertTrue(authorityStrings.contains("ROLE_SYSTEM_ADMIN"));
        assertTrue(authorityStrings.contains("users.manage"));
    }

    /**
     * SEC-14. The token below is correctly signed and nowhere near expiry --
     * the only thing wrong with it is that the user has logged out since it
     * was minted. That was previously enough to keep full access for the
     * rest of the hour.
     */
    @Test
    void aTokenMintedBeforeLogoutNoLongerAuthenticates() throws Exception {
        User user = activeUser("loggedout@magti.ge", Role.OPERATOR);
        String token = jwtService.createAccessTokenFor(user);
        user.invalidateIssuedTokens();
        when(userRepository.findByEmail("loggedout@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    /** ...while a token minted after it is fine. */
    @Test
    void aTokenMintedAfterLogoutStillAuthenticates() throws Exception {
        User user = activeUser("relogin@magti.ge", Role.OPERATOR);
        user.invalidateIssuedTokens();
        String token = jwtService.createAccessTokenFor(user);
        when(userRepository.findByEmail("relogin@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(request, response, chain);

        assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    /**
     * The deployment must not log the whole company out. A token issued
     * before this feature existed carries no "tv" claim at all, and every
     * existing row migrates to token_version 0 -- so it keeps working until
     * it expires on its own.
     */
    @Test
    void aTokenPredatingTheVersionClaimIsStillAccepted() throws Exception {
        String legacyToken = jwtService.createAccessToken(Map.of("sub", "legacy@magti.ge"));
        User user = activeUser("legacy@magti.ge", Role.OPERATOR);
        when(userRepository.findByEmail("legacy@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + legacyToken);

        filter.doFilter(request, response, chain);

        assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    /**
     * ...but only until that user logs out once. After the first bump, a
     * claimless legacy token reads as version 0 against a row at 1 and is
     * rejected like any other stale token -- so the compatibility window
     * closes by itself rather than staying open forever.
     */
    @Test
    void aLegacyTokenStopsWorkingOnceTheUserHasLoggedOutOnce() throws Exception {
        String legacyToken = jwtService.createAccessToken(Map.of("sub", "legacy2@magti.ge"));
        User user = activeUser("legacy2@magti.ge", Role.OPERATOR);
        user.invalidateIssuedTokens();
        when(userRepository.findByEmail("legacy2@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + legacyToken);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * A stale cookie must not resurrect a session either -- the cookie is
     * the second candidate the filter tries, so it needs its own proof.
     */
    @Test
    void aStaleCookieTokenIsRejectedToo() throws Exception {
        User user = activeUser("staleCookie@magti.ge", Role.OPERATOR);
        String token = jwtService.createAccessTokenFor(user);
        user.invalidateIssuedTokens();
        when(userRepository.findByEmail("staleCookie@magti.ge")).thenReturn(Optional.of(user));

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("access_token", token)});

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
