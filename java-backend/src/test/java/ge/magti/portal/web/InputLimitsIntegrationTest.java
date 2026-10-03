package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Input the attack tests (2026-10-02) sent that Oracle refused, each of which
 * reached the editor as "unexpected error" with a correlation id: values
 * longer than their column, content the sanitiser emptied, a 30 MB body, a
 * year Oracle cannot bind. Each must now be a 4xx that says what to fix, and
 * must leave nothing half-written behind.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class InputLimitsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(Role role) {
        User user = new User();
        user.setEmail("limits-" + System.nanoTime() + "@magti.ge");
        user.setName("ზღვრების ტესტი");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream().map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private long categoryId() {
        Category category = new Category();
        category.setName("ზღვრები " + System.nanoTime());
        category.setSlug("limits-" + System.nanoTime());
        return categoryRepository.saveAndFlush(category).getId();
    }

    private ResultActions createArticle(String token, Map<String, Object> overrides, Object quiz) throws Exception {
        Map<String, Object> article = new LinkedHashMap<>(Map.of(
                "title", "ზღვრების სტატია", "content", "<p>ტექსტი</p>", "category_id", categoryId(),
                "target_departments", List.of("All"), "status", "published", "is_draft", false,
                "quiz_enabled", quiz != null));
        article.putAll(overrides);
        Map<String, Object> command = new LinkedHashMap<>(Map.of("article", article, "mandatory", false));
        if (quiz != null) command.put("quiz", quiz);
        return mockMvc.perform(post("/api/articles/command").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(command)));
    }

    @Test
    void articleFieldsPastTheirColumnAreRefusedWithTheFieldNamed() throws Exception {
        String token = tokenFor(createUser(Role.CONTENT_ADMIN));
        long before = articleRepository.count();

        createArticle(token, Map.of("title", "ა".repeat(501)), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("სათაური")));
        createArticle(token, Map.of("youtube_id", "x".repeat(51)), null)
                .andExpect(status().isBadRequest());
        createArticle(token, Map.of("content", "<p>" + "x".repeat(ArticleRequest.MAX_CONTENT_CHARS) + "</p>"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ArticleRequest.CONTENT_TOO_LONG));

        assertEquals(before, articleRepository.count());
    }

    /** No record limit covers these: the database's refusal is the net, and it says what it means. */
    @Test
    void whatOnlyTheDatabaseRefusesIsA422NotAnUnexpectedError() throws Exception {
        String token = tokenFor(createUser(Role.CONTENT_ADMIN));
        long before = articleRepository.count();

        // One tag of 150 characters: the tags line fits, the tag table's name (100) does not.
        createArticle(token, Map.of("tags", "თ".repeat(150)), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("სიგრძეს")));
        createArticle(token, Map.of(), Map.of("questions", List.of(Map.of(
                        "question_text", "კითხვა", "position", 0,
                        "answers", List.of(
                                Map.of("answer_text", "პ".repeat(1100), "is_correct", true, "position", 0),
                                Map.of("answer_text", "მეორე", "is_correct", false, "position", 1))))))
                .andExpect(status().isUnprocessableEntity());
        // Nothing but a script: the sanitiser removes it and nothing is left.
        createArticle(token, Map.of("content", "<script>alert(1)</script>"), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("ტექსტი ცარიელია")));

        assertEquals(before, articleRepository.count(), "a refused create leaves no article behind");
    }

    @Test
    void anAdministratorsEditOfAPersonPastTheColumnIsRefused() throws Exception {
        User admin = createUser(Role.SYSTEM_ADMIN);
        User operator = createUser(Role.OPERATOR);
        mockMvc.perform(put("/api/users/" + operator.getId()).header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "role", "operator", "phone", "5".repeat(31), "lock_version", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("ტელეფონი")));
    }

    @Test
    void anExportRangeOracleCannotBindIsRefused() throws Exception {
        String token = tokenFor(createUser(Role.SYSTEM_ADMIN));
        mockMvc.perform(post("/api/admin/exports/audit-ledger?from=2026-01-01&through=9999-12-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("2000–2100")));
    }
}
