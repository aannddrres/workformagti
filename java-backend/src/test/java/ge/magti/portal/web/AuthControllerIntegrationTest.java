package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.PortalSession;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.PortalSessionRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hits POST /api/auth/login and /api/auth/logout through the real Spring
 * MVC + Security filter chain, against the real Oracle instance -- proves
 * the whole path (JIT provisioning -&gt; JWT issuance -&gt; cookie -&gt; audit
 * row) actually works end-to-end, not just that each piece compiles.
 * {@code @Transactional} rolls back the JIT-provisioned user and audit
 * rows afterward.
 *
 * <p>Each test uses its own fake remote address ({@link #withIp}) --
 * {@link ge.magti.portal.security.LoginRateLimiter} is a real singleton
 * shared across every test in this class (one Spring context), so without
 * distinct IPs, the rate-limit test would consume attempts other tests
 * need, and test order would silently start mattering.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private PortalSessionRepository portalSessionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<AuditLog> audits(String action, Long userId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> action.equals(row.getAction()))
                .filter(row -> userId.equals(row.getItemId()))
                .toList();
    }

    private AuditLog latestAudit(String action, Long userId) {
        return audits(action, userId).stream()
                .max(java.util.Comparator.comparing(AuditLog::getId))
                .orElseThrow();
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void loginWithKnownTestEmailIssuesTokenAndSetsCookie() throws Exception {
        // Looked up AFTER the login, not before. This account is
        // JIT-provisioned on first sign-in, so on a database nobody has used
        // yet it does not exist until the request below creates it -- which
        // is exactly the state OracleTestcontainer boots for a developer who
        // has Docker and no local Oracle. Reading it first passed only
        // because the shared dev database had been logged into at some point,
        // which is a dependency on history rather than on the code.
        int before = userRepository.findByEmailIgnoreCase("content@magti.ge")
                .map(existing -> audits("LOGIN", existing.getId()).size())
                .orElse(0);

        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.1")
                        .header("User-Agent", "auth-audit-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(cookie().exists("access_token"))
                .andExpect(cookie().httpOnly("access_token", true));

        User account = userRepository.findByEmailIgnoreCase("content@magti.ge").orElseThrow();
        assertEquals(before + 1, audits("LOGIN", account.getId()).size());
        AuditLog audit = latestAudit("LOGIN", account.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("10.0.0.1", audit.getIpAddress());
        assertEquals("auth-audit-test", audit.getUserAgent());
        assertEquals("SUCCESS", details.path("result").asText());
        assertEquals("LOCAL_DEVELOPMENT_ONLY", details.path("after").path("auth_channel").asText());
        assertFalse(audit.getDetails().contains("access_token"));
    }

    @Test
    void loginWithUnknownEmailIsRejected() throws Exception {
        long before = auditLogRepository.findAll().stream()
                .filter(row -> "LOGIN_FAILED".equals(row.getAction())).count();
        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody.real@magti.ge\",\"password\":\"whatever\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        long after = auditLogRepository.findAll().stream()
                .filter(row -> "LOGIN_FAILED".equals(row.getAction())).count();
        assertEquals(before, after, "unknown attempted emails must not be retained in audit storage");
    }

    @Test
    void knownAccountFailureIsSchemaAuditedWithoutPasswordOrEmailInDetails() throws Exception {
        User account = new User();
        account.setEmail("auth-failure-" + System.nanoTime() + "@example.invalid");
        account.setName("Auth failure target");
        account.setRole(Role.OPERATOR);
        account.setDepartment("All");
        account.setActive(true);
        account.setHashedPassword(passwordEncoder.encode("CorrectPass1"));
        account.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        account = userRepository.saveAndFlush(account);

        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + account.getEmail() + "\",\"password\":\"WrongPass1\"}"))
                .andExpect(status().isUnauthorized());

        AuditLog audit = latestAudit("LOGIN_FAILED", account.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("FAILURE", details.path("result").asText());
        assertEquals("AUTHENTICATION_REJECTED", details.path("reason").asText());
        assertEquals("10.0.0.22", audit.getIpAddress());
        assertFalse(audit.getDetails().contains(account.getEmail()));
        assertFalse(audit.getDetails().contains("WrongPass1"));
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        // Signed in first: since PO-20 the endpoint refuses an anonymous
        // caller, so the cookie clear can only be observed on a live session.
        String loginBody = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.31")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginBody, "$.access_token");

        mockMvc.perform(post("/api/auth/logout").with(csrf()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0));
    }

    /**
     * SEC-14, end to end through the real filter chain: log in, prove the
     * bearer token works, log out, prove the SAME token no longer does.
     * Before this, clearing the cookie was all logout did -- the token in
     * this test's hand would have kept working for another 60 minutes,
     * which is exactly the copy an attacker would be holding.
     */
    @Test
    void aBearerTokenStopsWorkingAfterTheUserLogsOut() throws Exception {
        String loginBody = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginBody, "$.access_token");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        User account = userRepository.findByEmailIgnoreCase("content@magti.ge").orElseThrow();
        AuditLog logoutAudit = latestAudit("LOGOUT", account.getId());
        JsonNode logoutDetails = objectMapper.readTree(logoutAudit.getDetails());
        assertEquals("SUCCESS", logoutDetails.path("result").asText());
        assertEquals(logoutDetails.path("before").path("token_version").asLong() + 1,
                logoutDetails.path("after").path("token_version").asLong());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** ...and logging in again issues a token that works. */
    @Test
    void loggingInAgainAfterLogoutIssuesAWorkingToken() throws Exception {
        String firstBody = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String firstToken = JsonPath.read(firstBody, "$.access_token");

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());

        String secondBody = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String secondToken = JsonPath.read(secondBody, "$.access_token");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * PO-20 / DEC-P02 (2026-08-31): logging out needs a live session.
     *
     * <p>It used to answer an anonymous caller, so that a tab whose token had
     * expired could still have the server clear its httpOnly cookie. That tab
     * no longer exists: IdleSessionService signs the operator out after
     * thirty idle minutes, and unauthorizedInterceptor sends them to /login
     * on the first 401 from anything. What is given up is the cookie clear --
     * a dead cookie stays in the browser until it expires, authenticating
     * nothing.
     */
    @Test
    void logoutWithoutATokenIsRefused() throws Exception {
        mockMvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void revokingTheCurrentSessionIsAuditedAndInvalidatesThatToken() throws Exception {
        String loginBody = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(loginBody, "$.access_token");

        String sessions = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> currentSessionIds = JsonPath.read(sessions, "$[?(@.current == true)].id");
        assertEquals(1, currentSessionIds.size());
        String sessionId = currentSessionIds.getFirst();

        mockMvc.perform(delete("/api/auth/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.6");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        User account = userRepository.findByEmailIgnoreCase("manager@magti.ge").orElseThrow();
        AuditLog audit = latestAudit("REVOKE_SESSION", account.getId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("SUCCESS", details.path("result").asText());
        assertEquals(true, details.path("before").path("session_active").asBoolean());
        assertEquals(false, details.path("after").path("session_active").asBoolean());
        assertFalse(audit.getDetails().contains(sessionId));
    }

    @Test
    void foreignSessionIdIsOpaqueAndCannotBeRevoked() throws Exception {
        String ownerLogin = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ownerToken = JsonPath.read(ownerLogin, "$.access_token");
        String ownerSessions = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ownerSessionIds = JsonPath.read(ownerSessions, "$[?(@.current == true)].id");
        assertEquals(1, ownerSessionIds.size());
        String ownerSessionId = ownerSessionIds.getFirst();

        String intruderLogin = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String intruderToken = JsonPath.read(intruderLogin, "$.access_token");
        User intruder = userRepository.findByEmailIgnoreCase("manager@magti.ge").orElseThrow();
        int revokeAuditsBefore = audits("REVOKE_SESSION", intruder.getId()).size();

        mockMvc.perform(delete("/api/auth/sessions/" + ownerSessionId)
                        .header("Authorization", "Bearer " + intruderToken)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.8");
                            return request;
                        }))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("content@magti.ge"));
        String ownerSessionsAfter = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(ownerSessionsAfter.contains(ownerSessionId),
                "foreign revoke must leave the owner's session active");
        String intruderSessions = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(intruderSessions.contains(ownerSessionId),
                "session listing must not disclose another user's session id");
        assertEquals(revokeAuditsBefore, audits("REVOKE_SESSION", intruder.getId()).size(),
                "a no-op foreign revoke must not create a success audit");
    }

    @Test
    void heartbeatRequiresAuthenticationAndTouchesOnlyTheCallersSession() throws Exception {
        mockMvc.perform(post("/api/auth/session/heartbeat").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));

        String ownerLogin = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"content@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ownerToken = JsonPath.read(ownerLogin, "$.access_token");
        String ownerSessions = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ownerSessionIds = JsonPath.read(ownerSessions, "$[?(@.current == true)].id");
        String ownerSessionId = ownerSessionIds.getFirst();

        String otherLogin = mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@magti.ge\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String otherToken = JsonPath.read(otherLogin, "$.access_token");
        String otherSessions = mockMvc.perform(get("/api/auth/sessions")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> otherSessionIds = JsonPath.read(otherSessions, "$[?(@.current == true)].id");
        String otherSessionId = otherSessionIds.getFirst();

        var backdated = TbilisiTime.now().minusMinutes(2);
        PortalSession ownerSession = portalSessionRepository.findById(ownerSessionId).orElseThrow();
        PortalSession otherSession = portalSessionRepository.findById(otherSessionId).orElseThrow();
        ownerSession.setLastSeenAt(backdated);
        otherSession.setLastSeenAt(backdated);
        portalSessionRepository.saveAndFlush(ownerSession);
        portalSessionRepository.saveAndFlush(otherSession);
        entityManager.flush();
        entityManager.clear();
        var ownerBeforeHeartbeat = portalSessionRepository.findById(ownerSessionId).orElseThrow().getLastSeenAt();
        var otherBeforeHeartbeat = portalSessionRepository.findById(otherSessionId).orElseThrow().getLastSeenAt();

        mockMvc.perform(post("/api/auth/session/heartbeat")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();
        assertTrue(portalSessionRepository.findById(ownerSessionId).orElseThrow().getLastSeenAt()
                .isAfter(ownerBeforeHeartbeat));
        assertEquals(otherBeforeHeartbeat,
                portalSessionRepository.findById(otherSessionId).orElseThrow().getLastSeenAt(),
                "one caller's heartbeat must not touch another user's session");
    }

    @Test
    void eleventhLoginAttemptWithinAMinuteIsRateLimited() throws Exception {
        String body = "{\"email\":\"nobody.real@magti.ge\",\"password\":\"whatever\"}";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.3")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(withIp(post("/api/auth/login"), "10.0.0.3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}
