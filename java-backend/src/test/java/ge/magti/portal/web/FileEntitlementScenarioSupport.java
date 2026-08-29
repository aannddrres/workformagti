package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One scenario, shared by the shadow and the enforced test.
 *
 * <p>The two differ only in a single property, and the whole point of the pair
 * is that the <i>situation</i> is identical -- so the situation is built once,
 * here. Two copies would eventually drift and the comparison would stop
 * meaning anything.
 *
 * <p>Everything goes through the real endpoints rather than straight into the
 * repositories, because the part most likely to break is the part being
 * skipped: {@code POST /api/articles} is what populates
 * {@code stored_file_references}, and a fixture that inserted rows itself
 * would keep passing after that call site was lost.
 */
abstract class FileEntitlementScenarioSupport {

    /** A real 8-byte PNG signature -- FileTypeVerifier checks the bytes (SEC-09). */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02};

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * An article aimed at one department, carrying a picture, plus one
     * operator inside that audience and one outside it.
     *
     * <p>Department names are suffixed with a nanosecond marker: this suite
     * runs against a shared development Oracle whose real departments would
     * otherwise match the fixture and make the "outsider" an insider.
     */
    protected Fixture createScenario() throws Exception {
        long marker = System.nanoTime();
        String insiderDepartment = "ტესტ-დეპ-A-" + marker;
        String outsiderDepartment = "ტესტ-დეპ-B-" + marker;

        User admin = createUser("fe-admin-" + marker + "@magti.ge", Role.CONTENT_ADMIN, "All");
        User insider = createUser("fe-in-" + marker + "@magti.ge", Role.OPERATOR, insiderDepartment);
        User outsider = createUser("fe-out-" + marker + "@magti.ge", Role.OPERATOR, outsiderDepartment);

        String adminToken = tokenFor(admin);
        String filename = upload(adminToken);
        createArticle(adminToken, marker, insiderDepartment, filename);

        return new Fixture(
                filename,
                insider, tokenFor(insider), insiderDepartment,
                outsider, tokenFor(outsider), outsiderDepartment);
    }

    /** The audited {@code details} of one decision, if it was written at all. */
    protected Optional<JsonNode> decisionFor(User user, String action) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT details FROM audit_logs WHERE admin_id = ? AND action = ?",
                String.class, user.getId(), action);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(rows.get(0)));
        } catch (Exception e) {
            throw new IllegalStateException("unreadable audit details", e);
        }
    }

    private String upload(String adminToken) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "picture.png", "image/png", PNG_BYTES);
        String body = mockMvc.perform(multipart("/api/upload").file(file)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("filename").asText();
    }

    private void createArticle(String adminToken, long marker, String department, String filename)
            throws Exception {
        Category category = new Category();
        category.setName("fe-category-" + marker);
        category.setActive(true);
        category = categoryRepository.saveAndFlush(category);

        Map<String, Object> article = Map.ofEntries(
                Map.entry("title", "fe-article-" + marker),
                Map.entry("content", "<p>ტექსტი <img src=\"/uploads/" + filename + "\"></p>"),
                Map.entry("category_id", category.getId()),
                Map.entry("target_departments", List.of(department)),
                Map.entry("status", "published"),
                Map.entry("is_draft", false),
                Map.entry("quiz_enabled", false));

        mockMvc.perform(post("/api/articles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(article)))
                .andExpect(status().isOk());
    }

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ფაილის უფლების ტესტი");
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(
                Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    protected record Fixture(
            String filename,
            User insider,
            String insiderToken,
            String insiderDepartment,
            User outsider,
            String outsiderToken,
            String outsiderDepartment) {
    }
}
