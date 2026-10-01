package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.compliance.OpenMaterial;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.reminder.ReminderSweepService;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PO-40 end to end: a mandatory reading binds only people who can open its
 * material. Each test works in departments of its own, named with a nonce, so
 * the directory's other users (other tests' fixtures) can neither be counted
 * nor named here.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MandatoryReachIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository articleTargetDepartmentRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReminderRepository reminderRepository;
    @Autowired
    private ReminderSweepService reminderSweepService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void anUnpublishedArticleCannotBeMadeMandatoryAndTheRefusalCountsWhoItWouldHaveMissed() throws Exception {
        String department = department();
        user(Role.OPERATOR, department);
        user(Role.OPERATOR, department);
        Article draft = article(department);
        draft.setStatus("draft");
        articleRepository.saveAndFlush(draft);

        assign(admin(), draft.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("unpublished"))
                .andExpect(jsonPath("$.blocked_total").value(2))
                .andExpect(jsonPath("$.blocked_departments[0].department").value(department))
                .andExpect(jsonPath("$.blocked_departments[0].count").value(2))
                .andExpect(jsonPath("$.blocked_users").doesNotExist());

        assertTrue(requiredReadingRepository.findByItemTypeAndItemId("article", draft.getId()).isEmpty(),
                "a refused assignment leaves nothing behind");
    }

    @Test
    void aTargetWiderThanTheArticleIsRefusedAndACoveredOneIsNot() throws Exception {
        String department = department();
        String groupOne = department + " — ჯგუფი 01";
        String groupTwo = department + " — ჯგუფი 02";
        user(Role.OPERATOR, groupOne);
        user(Role.OPERATOR, groupTwo);
        Article forGroupOne = article(groupOne);
        User admin = admin();

        assign(admin, forGroupOne.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("outside_audience"))
                .andExpect(jsonPath("$.blocked_total").value(1))
                .andExpect(jsonPath("$.blocked_departments[0].department").value(groupTwo));

        assign(admin, forGroupOne.getId(), groupOne, TbilisiTime.now().plusDays(3))
                .andExpect(status().isOk());
    }

    @Test
    void aScheduledArticleBindsFromItsPublicationWhenItsAssignmentGoesOut() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article scheduled = article(department);
        scheduled.setStatus("scheduled");
        scheduled.setPublishedAt(TbilisiTime.now().plusDays(2));
        articleRepository.saveAndFlush(scheduled);
        User admin = admin();

        assign(admin, scheduled.getId(), department, TbilisiTime.now().plusDays(1))
                .andExpect(status().isUnprocessableEntity());
        long readingId = idOf(assign(admin, scheduled.getId(), department, TbilisiTime.now().plusDays(5))
                .andExpect(status().isOk()));

        RequiredReading pending = requiredReadingRepository.findById(readingId).orElseThrow();
        assertNull(pending.getAssignmentDeliveredAt(), "nothing goes out before publication");
        assertEquals(0, assignmentsFor(readingId));
        assertFalse(myReadingIds(operator).contains(readingId), "not owed before it can be opened");

        scheduled.setPublishedAt(TbilisiTime.now().minusMinutes(1));
        articleRepository.saveAndFlush(scheduled);
        reminderSweepService.runOnce();
        reminderSweepService.runOnce();

        assertEquals(1, assignmentsFor(readingId), "delivered once, however often the sweep runs");
        assertNotNull(requiredReadingRepository.findById(readingId).orElseThrow().getAssignmentDeliveredAt());
        assertTrue(myReadingIds(operator).contains(readingId));
    }

    @Test
    void archivingSuspendsTheObligationAndPublishingAgainResumesIt() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article article = article(department);
        User admin = admin();
        long readingId = idOf(assign(admin, article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().isOk()));
        assertTrue(myReadingIds(operator).contains(readingId));
        mockMvc.perform(authed(get("/api/compliance/my-progress"), operator))
                .andExpect(jsonPath("$.total_mandatory").value(1));

        article.setStatus("archived");
        articleRepository.saveAndFlush(article);

        assertFalse(myReadingIds(operator).contains(readingId));
        mockMvc.perform(authed(get("/api/compliance/my-progress"), operator))
                .andExpect(jsonPath("$.total_mandatory").value(0));
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), operator))
                .andExpect(status().isConflict());
        // The editor may keep the assignment on its target while the article is away.
        mockMvc.perform(authed(put("/api/compliance/required-readings/" + readingId), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(readingJson(article.getId(), department, TbilisiTime.now().plusDays(4))))
                .andExpect(status().isOk());

        article.setStatus("published");
        articleRepository.saveAndFlush(article);

        assertTrue(myReadingIds(operator).contains(readingId), "back in force with the article");
    }

    @Test
    void anArticleForTwoDepartmentsBindsBothAndDroppingOneReleasesOnlyIt() throws Exception {
        String first = department();
        String second = department();
        User inFirst = user(Role.OPERATOR, first);
        User inSecond = user(Role.OPERATOR, second);
        User editor = user(Role.CONTENT_ADMIN, "All");
        Category category = category();

        long articleId = objectMapper.readTree(mockMvc.perform(authed(post("/api/articles/command"), editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(command(category, List.of(first, second)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();

        List<RequiredReading> readings = requiredReadingRepository.findByItemTypeAndItemId("article", articleId);
        assertEquals(java.util.Set.of(first, second), readings.stream()
                .map(RequiredReading::getTargetDepartment)
                .collect(java.util.stream.Collectors.toSet()), "one reading per department of the audience");
        assertEquals(1, myArticleReadings(inFirst, articleId));
        assertEquals(1, myArticleReadings(inSecond, articleId));

        mockMvc.perform(authed(put("/api/articles/" + articleId + "/command"), editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(command(category, List.of(first)))))
                .andExpect(status().isOk());

        assertEquals(1, myArticleReadings(inFirst, articleId));
        assertEquals(0, myArticleReadings(inSecond, articleId), "the dropped department no longer owes it");
        assertEquals(2, requiredReadingRepository.findByItemTypeAndItemId("article", articleId).size(),
                "its reading and any confirmations stay on record");
    }

    @Test
    void addresseesCountForEditorsAndNameOnlyForTheSystemAdmin() throws Exception {
        String department = department();
        User reader = user(Role.OPERATOR, department);
        user(Role.OPERATOR, department);
        Article article = article(department);
        long readingId = idOf(assign(admin(), article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().isOk()));
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), reader))
                .andExpect(status().isOk());
        String path = "/api/compliance/required-readings/by-item/article/" + article.getId() + "/addressees";

        mockMvc.perform(authed(get(path), admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.in_force_total").value(2))
                .andExpect(jsonPath("$.pending_total").value(0))
                .andExpect(jsonPath("$.departments[0].department").value(department))
                .andExpect(jsonPath("$.departments[0].in_force").value(2))
                .andExpect(jsonPath("$.departments[0].read").value(1))
                .andExpect(jsonPath("$.addressees.length()").value(2));

        mockMvc.perform(authed(get(path), user(Role.CONTENT_ADMIN, "All")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.in_force_total").value(2))
                .andExpect(jsonPath("$.departments[0].in_force").value(2))
                .andExpect(jsonPath("$.addressees.length()").value(0));
    }

    @Test
    void addresseesAreRefusedToOperators() throws Exception {
        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/1/addressees"),
                        user(Role.OPERATOR, department())))
                .andExpect(status().isForbidden());
    }

    @Test
    void addresseesAnswer400ForANonNumericId() throws Exception {
        mockMvc.perform(authed(get("/api/compliance/required-readings/by-item/article/notanumber/addressees"),
                        admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theOverviewCountsMandatoryMaterialInForceNotReadingRows() throws Exception {
        User admin = admin();
        long before = kpiMandatory(admin);
        User editor = user(Role.CONTENT_ADMIN, "All");
        // Departments somebody works in: an audience that reaches nobody is
        // refused since 2026-10-01 (DepartmentTargets).
        String first = department();
        String second = department();
        user(Role.OPERATOR, first);
        user(Role.OPERATOR, second);
        long articleId = objectMapper.readTree(mockMvc.perform(authed(post("/api/articles/command"), editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(command(category(), List.of(first, second)))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();

        assertEquals(before + 1, kpiMandatory(admin), "one article with two readings is one piece of material");

        Article article = articleRepository.findById(articleId).orElseThrow();
        article.setStatus("archived");
        articleRepository.saveAndFlush(article);
        assertEquals(before, kpiMandatory(admin), "archived material is not active mandatory material");
    }

    private long kpiMandatory(User admin) throws Exception {
        return objectMapper.readTree(mockMvc.perform(authed(get("/api/statistics/kpi"), admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("required_readings").asLong();
    }

    private Map<String, Object> command(Category category, List<String> departments) {
        return Map.of(
                "article", Map.ofEntries(
                        Map.entry("title", "PO-40 " + System.nanoTime()),
                        Map.entry("content", "<p>სავალდებულო ორ დეპარტამენტზე</p>"),
                        Map.entry("category_id", category.getId()),
                        Map.entry("target_departments", departments),
                        Map.entry("status", "published"),
                        Map.entry("is_draft", false),
                        Map.entry("quiz_enabled", false)),
                "mandatory", true,
                "due_date", OffsetDateTime.now().plusDays(3).toString());
    }

    private ResultActions assign(User admin, Long articleId, String target, OffsetDateTime due) throws Exception {
        return mockMvc.perform(authed(post("/api/compliance/required-readings"), admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(readingJson(articleId, target, due)));
    }

    private static String readingJson(Long articleId, String target, OffsetDateTime due) {
        return "{\"item_type\":\"article\",\"item_id\":" + articleId + ",\"target_department\":\"" + target
                + "\",\"due_date\":\"" + due + "\",\"priority\":\"high\"}";
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private List<Long> myReadingIds(User operator) throws Exception {
        JsonNode list = objectMapper.readTree(mockMvc.perform(authed(get("/api/compliance/my-readings"), operator))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .map(entry -> entry.get("reading").get("id").asLong())
                .toList();
    }

    private long myArticleReadings(User operator, long articleId) throws Exception {
        JsonNode list = objectMapper.readTree(mockMvc.perform(authed(get("/api/compliance/my-readings"), operator))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(entry -> entry.get("reading").get("item_id").asLong() == articleId)
                .count();
    }

    private long assignmentsFor(long readingId) {
        return CompleteResultGuard.enforce(reminderRepository.findByRequiredReadingIdAndTypeOrderByIdAsc(
                readingId, ReminderType.ASSIGNMENT, CompleteResultGuard.sentinelPage())).size();
    }

    private Article article(String... audience) {
        return OpenMaterial.article(articleRepository, articleTargetDepartmentRepository, "PO-40 სტატია", audience);
    }

    private Category category() {
        Category category = new Category();
        category.setName("po40-category-" + System.nanoTime());
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private static String department() {
        return "PO40-" + System.nanoTime();
    }

    private User admin() {
        return user(Role.SYSTEM_ADMIN, "All");
    }

    private User user(Role role, String department) {
        User user = new User();
        user.setEmail("po40-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("PO-40 " + role.value());
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, User user) {
        return builder.header("Authorization", "Bearer "
                + jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value())));
    }
}
