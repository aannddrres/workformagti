package ge.magti.portal.security;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0 regression matrix for named/raw employee-data surfaces that are locked to
 * {@link Role#SYSTEM_ADMIN}. Each non-admin canonical role must be rejected
 * before the handler can query, export, verify or mutate the protected data.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SystemAdminPiiSurfaceIntegrationTest {

    private record ProtectedEndpoint(
            String label,
            Supplier<MockHttpServletRequestBuilder> request) {
    }

    private static final List<Role> NON_ADMIN_ROLES =
            List.of(Role.OPERATOR, Role.MANAGER, Role.CONTENT_ADMIN);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    static Stream<Arguments> nonAdminRoleAndProtectedEndpoint() {
        List<ProtectedEndpoint> endpoints = List.of(
                endpoint("access-diff", () -> get("/api/admin/access-diff")),
                endpoint("article-view-log", () -> get("/api/articles/1/views")),
                endpoint("audit-list", () -> get("/api/audit-logs")),
                endpoint("audit-export", () -> get("/api/audit-logs/export")),
                endpoint("audit-verify", () -> get("/api/audit-logs/1/verify")),
                endpoint("audit-chain-health", () -> get("/api/audit-logs/chain-health")),
                endpoint("export-audit-ledger", () -> post("/api/admin/exports/audit-ledger")),
                endpoint("export-read-evidence", () -> post("/api/admin/exports/read-evidence")),
                endpoint("export-article-views", () -> post("/api/admin/exports/article-views")),
                endpoint("export-search-history", () -> post("/api/admin/exports/search-history")),
                endpoint("export-quiz-attempts", () -> post("/api/admin/exports/quiz-attempts")),
                endpoint("export-change-events", () -> post("/api/admin/exports/change-events")),
                endpoint("org-structure", () -> get("/api/admin/org/structure")),
                endpoint("org-assignments", () -> get("/api/admin/org/assignments")),
                endpoint("org-assignment-deactivate", () -> delete("/api/admin/org/assignments/1")),
                endpoint("policy-shadow", () -> get("/api/admin/policy-shadow")),
                endpoint("org-backfill-report", () -> get("/api/admin/org-backfill/report")),
                endpoint("org-backfill-apply", () -> post("/api/admin/org-backfill/apply")),
                endpoint("all-user-progress", () -> get("/api/statistics/user-progress")),
                endpoint("admin-team-stats", () -> get("/api/admin/stats/team/1")),
                endpoint("group-leaders", () -> get("/api/admin/group-leaders")),
                endpoint("user-directory", () -> get("/api/users")));

        return endpoints.stream()
                .flatMap(endpoint -> NON_ADMIN_ROLES.stream()
                        .map(role -> Arguments.of(role, endpoint.label(), endpoint.request())));
    }

    @ParameterizedTest(name = "{0} cannot access {1}")
    @MethodSource("nonAdminRoleAndProtectedEndpoint")
    void everyNonAdminRoleIsForbiddenFromSystemAdminPiiSurfaces(
            Role role,
            String endpointLabel,
            Supplier<MockHttpServletRequestBuilder> requestFactory) throws Exception {
        User caller = new User();
        caller.setEmail("p0-system-admin-boundary-" + role.value() + "@magti.ge");
        caller.setName("P0 System Admin Boundary " + role.value());
        caller.setRole(role);
        caller.setDepartment("ტექნიკური — ჯგუფი 01");
        caller.setPosition("ტესტი");
        caller.setActive(true);
        caller.setHashedPassword("not-used-by-bearer-auth");
        caller.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        caller = userRepository.saveAndFlush(caller);

        mockMvc.perform(requestFactory.get()
                        .header("Authorization", "Bearer " + jwtService.createAccessTokenFor(caller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    private static ProtectedEndpoint endpoint(
            String label,
            Supplier<MockHttpServletRequestBuilder> request) {
        return new ProtectedEndpoint(label, request);
    }
}
