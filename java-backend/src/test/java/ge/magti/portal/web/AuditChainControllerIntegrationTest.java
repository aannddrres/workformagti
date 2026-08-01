package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the real wiring, not just the logic: a real login through the
 * actual Spring Security filter chain, a real JWT, and {@code
 * @AuthenticationPrincipal} genuinely resolving to the authenticated
 * {@code User} inside AuditChainController -- the first use of that
 * annotation anywhere in this codebase, so it's verified against the real
 * chain rather than assumed to work. {@code @Transactional} rolls back the
 * JIT-provisioned users and the audit rows their logins create.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditChainControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private String loginAndGetToken(String email, String ip) throws Exception {
        String body = mockMvc.perform(withIp(post("/api/auth/login"), ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"anything\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("access_token").asText();
    }

    @Test
    void systemAdminCanVerifyTheAuditRowTheirOwnLoginJustCreated() throws Exception {
        String token = loginAndGetToken("admin@magti.ge", "10.20.0.1");

        Long adminId = userRepository.findByEmail("admin@magti.ge").orElseThrow().getId();
        Long loginRowId = auditLogRepository.findAll().stream()
                .filter(row -> row.getAdminId().equals(adminId) && "LOGIN".equals(row.getAction()))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(get("/api/audit-logs/" + loginRowId + "/verify")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.hash_match").value(true))
                .andExpect(jsonPath("$.chain_match").value(true));
    }

    @Test
    void systemAdminCanReadChainHealth() throws Exception {
        String token = loginAndGetToken("admin@magti.ge", "10.20.0.2");

        mockMvc.perform(get("/api/audit-logs/chain-health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void managerWithoutThePermissionIsForbidden() throws Exception {
        String token = loginAndGetToken("manager@magti.ge", "10.20.0.3");

        mockMvc.perform(get("/api/audit-logs/chain-health")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    @Test
    void noTokenAtAllIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/audit-logs/chain-health"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }
}
