package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.CorporateAuthClient;
import ge.magti.portal.security.CorporateIdentity;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The company login end to end -- real filter chain, real Oracle, real
 * session and audit rows -- with only the directory itself replaced, because
 * a test suite must not send anyone's password to Magti's authorization
 * server. What the directory answers is shaped after the 2026-09-21
 * measurement (docs/QUESTIONS_FOR_IT.md No.13).
 */
@RequiresOracle
@SpringBootTest(properties = {
        "portal.security.corporate.enabled=true",
        "portal.security.corporate.service-uri=https://oauth.example.test/auth/",
        "portal.security.corporate.client-id=InfoPortal",
        "portal.security.corporate.domain=@example.ge"
})
@AutoConfigureMockMvc
@Transactional
class CorporateLoginIntegrationTest {

    @DynamicPropertySource
    static void syntheticClientCredential(DynamicPropertyRegistry registry) {
        registry.add("portal.security.corporate.client-credential", () -> Base64.getEncoder()
                .encodeToString("InfoPortal:fixture".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private CorporateAuthClient directory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static MockHttpServletRequestBuilder login(String email, String password, String ip) {
        return post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private void directoryAccepts(String email, Set<String> authorities) {
        when(directory.authenticate(email, "pw")).thenReturn(new CorporateAuthClient.Authenticated(
                new CorporateIdentity(email.substring(0, email.indexOf('@')), email, "1001", authorities, null, null)));
    }

    private User account(String email, Role role, String department, boolean active) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ " + email);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(active);
        user.setHashedPassword(passwordEncoder.encode("unused-Pass1"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private List<AuditLog> audits(String action, Long userId) {
        return auditLogRepository.findAll().stream()
                .filter(row -> action.equals(row.getAction()) && userId.equals(row.getItemId()))
                .sorted(Comparator.comparing(AuditLog::getId))
                .toList();
    }

    private String failureReason(User user) throws Exception {
        return objectMapper.readTree(audits("LOGIN_FAILED", user.getId()).getLast().getDetails()).path("reason").asText();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    @Test
    void theFirstCompanySignInCreatesTheAccountAndItsSession() throws Exception {
        directoryAccepts("corp.newcomer@example.ge", Set.of("INFOPORTAL_MANAGER", "MAGTICOM_USER"));

        mockMvc.perform(login("corp.newcomer@example.ge", "pw", "10.9.0.1"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Set-Cookie"));

        User created = userRepository.findByEmailIgnoreCase("corp.newcomer@example.ge").orElseThrow();
        assertEquals(Role.MANAGER, created.getRole());
        assertNull(created.getDepartment(), "PO-23: no department until one is known");
        JsonNode details = objectMapper.readTree(audits("LOGIN", created.getId()).getLast().getDetails());
        assertEquals("CORPORATE_OAUTH", details.path("after").path("auth_channel").asText());
        assertTrue(details.path("after").path("account_created").asBoolean());
    }

    @Test
    void theDirectorysRejectionIsOneSentenceAndARecordedFailure() throws Exception {
        User user = account("corp.wrongpass@example.ge", Role.OPERATOR, null, true);
        when(directory.authenticate("corp.wrongpass@example.ge", "nope")).thenReturn(new CorporateAuthClient.Rejected());

        mockMvc.perform(login("corp.wrongpass@example.ge", "nope", "10.9.0.2"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthController.LOGIN_FAILED_DETAIL));

        assertEquals("AUTHENTICATION_REJECTED", failureReason(user));
    }

    /** An outage says nothing about anyone's password, so it is no one's failed sign-in. */
    @Test
    void anUnreachableDirectoryIs503AndWritesNoFailedLogin() throws Exception {
        User user = account("corp.outage@example.ge", Role.OPERATOR, null, true);
        when(directory.authenticate("corp.outage@example.ge", "pw")).thenReturn(new CorporateAuthClient.Unavailable("HTTP 503"));

        mockMvc.perform(login("corp.outage@example.ge", "pw", "10.9.0.3"))
                .andExpect(status().isServiceUnavailable());

        assertTrue(audits("LOGIN_FAILED", user.getId()).isEmpty());
    }

    /** PO-24: switching a leaver off must outlast their next correct password. */
    @Test
    void aDeactivatedAccountStaysOutWithACorrectPassword() throws Exception {
        User user = account("corp.leaver@example.ge", Role.OPERATOR, null, false);
        directoryAccepts("corp.leaver@example.ge", Set.of("INFOPORTAL_OPERATOR"));

        mockMvc.perform(login("corp.leaver@example.ge", "pw", "10.9.0.4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthController.LOGIN_FAILED_DETAIL));

        assertEquals("ACCOUNT_DEACTIVATED", failureReason(user));
    }

    @Test
    void aRoleWithdrawnInTheDirectoryRefusesEntryAndRevokesOldToken() throws Exception {
        User user = account("corp.formerlead@example.ge", Role.MANAGER, "ტექნიკური — ჯგუფი 03", true);
        String oldBearer = bearer(user);
        mockMvc.perform(get("/api/users/me").header("Authorization", oldBearer))
                .andExpect(status().isOk());
        directoryAccepts("corp.formerlead@example.ge", Set.of("MAGTICOM_USER"));

        mockMvc.perform(login("corp.formerlead@example.ge", "pw", "10.9.0.5"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthController.LOGIN_FAILED_DETAIL))
                .andExpect(result -> assertTrue(result.getResponse().getHeaders("Set-Cookie").stream()
                        .noneMatch(cookie -> cookie.startsWith("access_token="))));
        mockMvc.perform(get("/api/users/me").header("Authorization", oldBearer))
                .andExpect(status().isUnauthorized());

        User after = userRepository.findById(user.getId()).orElseThrow();
        assertEquals(Role.MANAGER, after.getRole());
        assertEquals("ტექნიკური — ჯგუფი 03", after.getDepartment(), "a department is never blanked");
        assertEquals(1, after.getTokenVersion());
        JsonNode details = objectMapper.readTree(audits("CORPORATE_ROLE_ACCESS_REVOKED", user.getId())
                .getLast().getDetails());
        assertEquals("NO_PORTAL_ROLE", details.path("reason").asText());
        assertEquals(0, details.path("before").path("token_version").asInt());
        assertEquals(1, details.path("after").path("token_version").asInt());
    }

    @Test
    void unmappedNewIdentityDoesNotCreateAnAccountOrSession() throws Exception {
        directoryAccepts("corp.roleless@example.ge", Set.of("MAGTICOM_USER"));

        mockMvc.perform(login("corp.roleless@example.ge", "pw", "10.9.0.7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthController.LOGIN_FAILED_DETAIL))
                .andExpect(result -> assertTrue(result.getResponse().getHeaders("Set-Cookie").stream()
                        .noneMatch(cookie -> cookie.startsWith("access_token="))));
        assertTrue(userRepository.findByEmailIgnoreCase("corp.roleless@example.ge").isEmpty());
    }

    @Test
    void rejectedPasswordAndDirectoryOutageDoNotRevokeAnExistingToken() throws Exception {
        User user = account("corp-temporary-failure@example.ge", Role.OPERATOR, "All", true);
        String oldBearer = bearer(user);
        when(directory.authenticate(user.getEmail(), "wrong")).thenReturn(new CorporateAuthClient.Rejected());
        when(directory.authenticate(user.getEmail(), "pw"))
                .thenReturn(new CorporateAuthClient.Unavailable("HTTP 503"));

        mockMvc.perform(login(user.getEmail(), "wrong", "10.9.0.8"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(login(user.getEmail(), "pw", "10.9.0.8"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/users/me").header("Authorization", oldBearer))
                .andExpect(status().isOk());
        assertEquals(0, userRepository.findById(user.getId()).orElseThrow().getTokenVersion());
    }

    /** The demo and test personas keep their bypass outside production, directory or not. */
    @Test
    void developmentPersonasStillSignInWithoutTheDirectory() throws Exception {
        mockMvc.perform(login("admin@magti.ge", "anything", "10.9.0.6")).andExpect(status().isOk());

        verify(directory, never()).authenticate(anyString(), anyString());
    }

    /**
     * With roles owned by the directory, a role typed in here would revert at
     * that person's next sign-in. The rest of the profile stays editable.
     */
    @Test
    void theAdminScreensRefuseARoleChangeButKeepTheRestOfTheProfile() throws Exception {
        User admin = account("corp.admin@example.ge", Role.SYSTEM_ADMIN, "Administration", true);
        User operator = account("corp.operator@example.ge", Role.OPERATOR, null, true);

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles_managed_by_directory").value(true));

        mockMvc.perform(put("/api/users/" + operator.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"manager\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(UserController.ROLE_MANAGED_BY_DIRECTORY_DETAIL));

        mockMvc.perform(put("/api/users/" + operator.getId()).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"operator\",\"department\":\"ოფისი — ჯგუფი 02\"}"))
                .andExpect(status().isOk());
        assertEquals("ოფისი — ჯგუფი 02", userRepository.findById(operator.getId()).orElseThrow().getDepartment());

        mockMvc.perform(post("/api/admin/roles/bulk-reassign").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + operator.getId() + "],\"new_role\":\"manager\"}"))
                .andExpect(status().isConflict());
        assertEquals(Role.OPERATOR, userRepository.findById(operator.getId()).orElseThrow().getRole());
    }
}
