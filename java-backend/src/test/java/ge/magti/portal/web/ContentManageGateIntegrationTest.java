package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.repository.UserPermissionOverrideRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** HTTP proof that the 23 content-mutation/lifecycle endpoints share the content.manage decision. */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContentManageGateIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserPermissionOverrideRepository overrideRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private record RequestCase(String name, Supplier<RequestBuilder> request) {}

    private User user(String suffix, Role role, UserPermissionOverride.State overrideState) {
        User user = new User();
        user.setEmail("phase6-" + suffix + "@magti.ge");
        user.setName("Phase 6");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user = userRepository.saveAndFlush(user);
        if (overrideState != null) {
            UserPermissionOverride override = new UserPermissionOverride();
            override.setUserId(user.getId());
            override.setPermission(Permission.CONTENT_MANAGE.value());
            override.setState(overrideState);
            override.setUpdatedAt(TbilisiTime.now());
            override.setUpdatedBy(user.getId());
            overrideRepository.saveAndFlush(override);
        }
        return user;
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<RequestCase> cases(String token) {
        String auth = "Bearer " + token;
        return List.of(
                new RequestCase("create news", () -> json(post("/api/news"), "{\"title\":\"Phase 6\",\"content\":\"x\"}").header("Authorization", auth)),
                new RequestCase("update news", () -> json(put("/api/news/999999999"), "{\"title\":\"Phase 6\",\"content\":\"x\"}").header("Authorization", auth)),
                new RequestCase("delete news", () -> delete("/api/news/999999999").header("Authorization", auth)),
                new RequestCase("autosave news", () -> json(patch("/api/news/999999999/autosave"), "{}").header("Authorization", auth)),
                new RequestCase("news history", () -> get("/api/news/999999999/history").header("Authorization", auth)),
                new RequestCase("news history summary", () -> get("/api/news/999999999/history-summary").header("Authorization", auth)),
                new RequestCase("news history detail", () -> get("/api/news/999999999/history/999999999").header("Authorization", auth)),
                new RequestCase("restore news", () -> post("/api/news/999999999/history/999999999/restore").header("Authorization", auth)),
                new RequestCase("create video", () -> json(post("/api/videos"), "{\"title\":\"Phase 6\",\"video_url\":\"https://youtu.be/abcdefghijk\"}").header("Authorization", auth)),
                new RequestCase("update video", () -> json(put("/api/videos/999999999"), "{\"title\":\"Phase 6\",\"video_url\":\"https://youtu.be/abcdefghijk\"}").header("Authorization", auth)),
                new RequestCase("delete video", () -> delete("/api/videos/999999999").header("Authorization", auth)),
                new RequestCase("create category", () -> json(post("/api/categories"), "{\"name\":\"Phase 6 category\"}").header("Authorization", auth)),
                new RequestCase("update category", () -> json(put("/api/categories/999999999"), "{\"name\":\"Phase 6 category\"}").header("Authorization", auth)),
                new RequestCase("delete category", () -> delete("/api/categories/999999999").header("Authorization", auth)),
                new RequestCase("upload", () -> multipart("/api/upload")
                        .file(new MockMultipartFile("file", "phase6.png", "image/png", new byte[]{1, 2, 3}))
                        .header("Authorization", auth)),
                new RequestCase("verify article", () -> post("/api/articles/999999999/verify").header("Authorization", auth)),
                new RequestCase("article history", () -> get("/api/articles/999999999/history").header("Authorization", auth)),
                new RequestCase("article history summary", () -> get("/api/articles/999999999/history-summary").header("Authorization", auth)),
                new RequestCase("article history detail", () -> get("/api/articles/999999999/history/999999999").header("Authorization", auth)),
                new RequestCase("restore article", () -> post("/api/articles/999999999/history/999999999/restore").header("Authorization", auth)),
                new RequestCase("stale articles", () -> get("/api/admin/articles/stale").header("Authorization", auth)),
                new RequestCase("quiz admin", () -> get("/api/articles/999999999/quiz/admin").header("Authorization", auth)),
                new RequestCase("update quiz admin", () -> json(put("/api/articles/999999999/quiz/admin"), "{\"questions\":[]}").header("Authorization", auth)));
    }

    private void assertAllForbidden(User caller) throws Exception {
        for (RequestCase requestCase : cases(tokenFor(caller))) {
            int status = mockMvc.perform(requestCase.request().get()).andReturn().getResponse().getStatus();
            assertEquals(403, status, requestCase.name());
        }
    }

    private void assertAllPassTheGate(User caller) throws Exception {
        assertAllPassTheGate(caller, java.util.Set.of());
    }

    /** Cases also gated by articles.edit (2026-10-01): content.manage alone is refused there. */
    private static final java.util.Set<String> ALSO_ARTICLES_EDIT = java.util.Set.of("restore article", "update quiz admin");

    private void assertAllPassTheGate(User caller, java.util.Set<String> expectForbidden) throws Exception {
        for (RequestCase requestCase : cases(tokenFor(caller))) {
            int status = mockMvc.perform(requestCase.request().get()).andReturn().getResponse().getStatus();
            if (expectForbidden.contains(requestCase.name())) {
                assertEquals(403, status, requestCase.name() + " also needs articles.edit");
            } else {
                assertNotEquals(403, status, requestCase.name());
            }
        }
    }

    @Test
    void roleDefaultsAndOverridesApplyToEveryContentEndpoint() throws Exception {
        assertAllPassTheGate(user("content-default", Role.CONTENT_ADMIN, null));
        assertAllForbidden(user("operator-default", Role.OPERATOR, null));
        assertAllForbidden(user("manager-default", Role.MANAGER, null));
        // content.manage alone: a restore or a quiz change rewrites an article, so
        // those two also need articles.edit, which this operator does not hold.
        assertAllPassTheGate(user("operator-allow", Role.OPERATOR, UserPermissionOverride.State.ALLOW), ALSO_ARTICLES_EDIT);
        assertAllForbidden(user("content-deny", Role.CONTENT_ADMIN, UserPermissionOverride.State.DENY));
    }
}
