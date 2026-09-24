package ge.magti.portal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.history.HistoryPayloadGuard;
import ge.magti.portal.repository.NewsHistoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Oracle, real HTTP, real Spring Security filter chain -- same
 * infrastructure as {@link CategoryControllerIntegrationTest}. Two tests
 * ({@link #creatingNewsWithoutIsDraftDefaultsToPublished} and
 * {@link #updatingNewsPreservesAuthorIdIsDraftAndExpiresAt}) are dedicated
 * regressions for the two confirmed live bugs fixed in this slice (see
 * {@link NewsRequest}'s javadoc).
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NewsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NewsRepository newsRepository;
    @Autowired
    private NewsHistoryRepository newsHistoryRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User createUser(String email, Role role, String department) {
        User user = new User();
        user.setEmail(email);
        user.setName("ტესტ მომხმარებელი");
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private String tokenFor(User user) {
        return jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value()));
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    /** Bypasses the create endpoint's own defaulting so tests can pin exact initial state. */
    private News createNewsDirect(String title, String targetDepartment, boolean isDraft, Long authorId) {
        News news = new News();
        news.setTitle(title);
        news.setContent("შინაარსი");
        news.setTargetDepartment(targetDepartment);
        news.setDraft(isDraft);
        news.setAuthorId(authorId);
        news.setCreatedAt(TbilisiTime.now());
        return newsRepository.saveAndFlush(news);
    }

    private String newsRequestJson(String title, String targetDepartment) {
        return "{\"title\":\"" + title + "\",\"content\":\"შინაარსი\",\"target_department\":\""
                + targetDepartment + "\",\"visible_to_tech_info\":true,\"visible_to_service_center\":false}";
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/news"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Could not validate credentials"));
    }

    @Test
    void listRejectsInvalidOrUnboundedCardinality() throws Exception {
        User operator = createUser("news-list-bounds@magti.ge", Role.OPERATOR, "All");
        String token = tokenFor(operator);

        for (String query : new String[] {"skip=-1&limit=20", "skip=0&limit=0", "skip=0&limit=1001"}) {
            mockMvc.perform(authed(get("/api/news?" + query), token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(ListQueryBounds.INVALID_DETAIL));
        }

        mockMvc.perform(authed(get("/api/news?skip=0&limit=1000"), token))
                .andExpect(status().isOk());
    }

    @Test
    void operatorCannotCreateNews() throws Exception {
        User operator = createUser("news-op1@magti.ge", Role.OPERATOR, "All");

        mockMvc.perform(authed(post("/api/news"), tokenFor(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("სათაური", "All")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("წვდომა უარყოფილია: არასაკმარისი უფლებები"));
    }

    /**
     * Also the BL-01 regression: the PUT below writes a {@code news_history}
     * row (every edit does, {@code archiveCurrentState}), and before V32 gave
     * {@code fk_news_history_news} an {@code ON DELETE CASCADE}, the DELETE
     * that follows it here would have thrown {@code ORA-02292} -> HTTP 500 --
     * only an item that had never been edited could be deleted at all. The
     * extra assertion after delete confirms the FK actually cascaded, not
     * merely that the request returned 204 for some other reason.
     */
    @Test
    void contentAdminCrudLifecycle() throws Exception {
        User admin = createUser("news-admin1@magti.ge", Role.CONTENT_ADMIN, "All");

        String createBody = mockMvc.perform(authed(post("/api/news"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("პირველი სიახლე", "All")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("პირველი სიახლე"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createBody).get("id").asLong();

        mockMvc.perform(authed(get("/api/news/" + id), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("პირველი სიახლე"));

        mockMvc.perform(authed(put("/api/news/" + id), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("განახლებული სიახლე", "All")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("განახლებული სიახლე"))
                .andExpect(jsonPath("$.version").value(2));
        assertFalse(newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                id, ge.magti.portal.query.CompleteResultGuard.sentinelPage()).isEmpty(),
                "the PUT above must have written a history row, or the delete below proves nothing about BL-01");

        AuditLog createAudit = auditLogRepository.findAll().stream()
                .filter(a -> "news".equals(a.getItemType()) && Long.valueOf(id).equals(a.getItemId())
                        && "CREATE".equals(a.getAction()))
                .findFirst().orElseThrow();
        AuditLog updateAudit = auditLogRepository.findAll().stream()
                .filter(a -> "news".equals(a.getItemType()) && Long.valueOf(id).equals(a.getItemId())
                        && "UPDATE".equals(a.getAction()))
                .findFirst().orElseThrow();
        var createDetails = objectMapper.readTree(createAudit.getDetails());
        var updateDetails = objectMapper.readTree(updateAudit.getDetails());
        assertEquals(admin.getId(), createAudit.getAdminId());
        assertEquals("პირველი სიახლე", createAudit.getItemNameSnapshot());
        assertTrue(createDetails.path("before").isNull());
        assertEquals(1, createDetails.path("after").path("version").asInt());
        assertEquals("პირველი სიახლე", updateDetails.path("before").path("title").asText());
        assertEquals("განახლებული სიახლე", updateDetails.path("after").path("title").asText());
        assertEquals(2, updateDetails.path("after").path("version").asInt());

        mockMvc.perform(authed(post("/api/news/" + id + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_archived").value(true));
        mockMvc.perform(authed(delete("/api/news/" + id), tokenFor(admin)))
                .andExpect(status().isNoContent());
        entityManager.clear();

        mockMvc.perform(authed(get("/api/news/" + id), tokenFor(admin)))
                .andExpect(status().isNotFound());
        assertFalse(newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                id, ge.magti.portal.query.CompleteResultGuard.sentinelPage()).isEmpty(),
                "news history is recoverable with the trashed payload");
    }

    @Test
    void creatingNewsWithoutIsDraftDefaultsToPublished() throws Exception {
        User admin = createUser("news-admin2@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("news-op2@magti.ge", Role.OPERATOR, "All");

        // Mirrors the real admin form's actual payload (static/js/app-core.js's
        // submitNewsForm) -- no is_draft key at all.
        String createBody = mockMvc.perform(authed(post("/api/news"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("გამოქვეყნებული სიახლე", "All")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_draft").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createBody).get("id").asLong();

        // The confirmed live bug: Python's schema default (is_draft=true) would
        // make this invisible to a plain operator forever. Fixed here.
        mockMvc.perform(authed(get("/api/news"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem((int) id)));
    }

    @Test
    void updatingNewsPreservesAuthorIdIsDraftAndExpiresAt() throws Exception {
        User author = createUser("news-author@magti.ge", Role.CONTENT_ADMIN, "All");
        User editor = createUser("news-editor@magti.ge", Role.CONTENT_ADMIN, "All");
        OffsetDateTime expiry = TbilisiTime.now().plusDays(30);

        News news = createNewsDirect("დრაფტი სიახლე", "All", true, author.getId());
        news.setExpiresAt(expiry);
        newsRepository.saveAndFlush(news);

        // A second admin edits it (payload has no author_id/is_draft/expires_at,
        // matching the real edit form exactly) -- none of the three should move.
        mockMvc.perform(authed(put("/api/news/" + news.getId()), tokenFor(editor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("გასწორებული სათაური", "All")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("გასწორებული სათაური"))
                .andExpect(jsonPath("$.author_id").value(author.getId()))
                .andExpect(jsonPath("$.is_draft").value(true));

        News reloaded = newsRepository.findById(news.getId()).orElseThrow();
        assertEquals(author.getId(), reloaded.getAuthorId());
        assertTrue(reloaded.isDraft());
        assertEquals(expiry.toEpochSecond(), reloaded.getExpiresAt().toEpochSecond());
    }

    @Test
    void nonAdminSeesOnlyPublishedNewsInOwnDepartmentGroupOrAll() throws Exception {
        User admin = createUser("news-seed-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User subGroupOperator = createUser("news-subgroup@magti.ge", Role.OPERATOR, "ტექნიკური — ჯგუფი 01");
        User otherDeptOperator = createUser("news-otherdept@magti.ge", Role.OPERATOR, "ოფისი");

        News parentTargeted = createNewsDirect("პარენტ დეპარტამენტისთვის", "ტექნიკური", false, admin.getId());
        News allTargeted = createNewsDirect("ყველასთვის", "All", false, admin.getId());
        News otherDeptTargeted = createNewsDirect("ოფისისთვის", "ოფისი", false, admin.getId());

        mockMvc.perform(authed(get("/api/news"), tokenFor(subGroupOperator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(parentTargeted.getId().intValue())))
                .andExpect(jsonPath("$[*].id").value(hasItem(allTargeted.getId().intValue())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(otherDeptTargeted.getId().intValue()))));

        mockMvc.perform(authed(get("/api/news/" + parentTargeted.getId()), tokenFor(otherDeptOperator)))
                .andExpect(status().isNotFound());
    }

    @Test
    void draftNewsIsInvisibleToNonAuthorsEvenAdmin() throws Exception {
        User author = createUser("news-draft-author@magti.ge", Role.CONTENT_ADMIN, "All");
        User otherAdmin = createUser("news-draft-other@magti.ge", Role.CONTENT_ADMIN, "All");

        News draft = createNewsDirect("სამუშაო ვერსია", "All", true, author.getId());

        mockMvc.perform(authed(get("/api/news"), tokenFor(otherAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(not(hasItem(draft.getId().intValue()))));
        mockMvc.perform(authed(get("/api/news/" + draft.getId()), tokenFor(otherAdmin)))
                .andExpect(status().isNotFound());

        mockMvc.perform(authed(get("/api/news"), tokenFor(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(hasItem(draft.getId().intValue())));
        mockMvc.perform(authed(get("/api/news/" + draft.getId()), tokenFor(author)))
                .andExpect(status().isOk());
    }

    @Test
    void expiredNewsIsHiddenFromOperatorsEvenByDirectLink() throws Exception {
        User admin = createUser("news-expiry-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        User operator = createUser("news-expiry-op@magti.ge", Role.OPERATOR, "All");

        News expired = createNewsDirect("ვადაგასული სიახლე", "All", false, admin.getId());
        expired.setExpiresAt(TbilisiTime.now().minusDays(1));
        newsRepository.saveAndFlush(expired);

        // Archived content is absent from both the list and direct reader access.
        mockMvc.perform(authed(get("/api/news"), tokenFor(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(not(hasItem(expired.getId().intValue()))));

        mockMvc.perform(authed(get("/api/news/" + expired.getId()), tokenFor(operator)))
                .andExpect(status().isNotFound());
        mockMvc.perform(authed(get("/api/news/" + expired.getId()), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ვადაგასული სიახლე"));
    }

    @Test
    void archiveAndUnarchiveWriteReconstructableAuditEvidence() throws Exception {
        User admin = createUser("news-lifecycle-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        News news = createNewsDirect("არქივის სიახლე", "All", false, admin.getId());

        mockMvc.perform(authed(post("/api/news/" + news.getId() + "/archive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_archived").value(true));

        AuditLog archiveAudit = auditLogRepository.findAll().stream()
                .filter(a -> "ARCHIVE".equals(a.getAction()) && "news".equals(a.getItemType())
                        && news.getId().equals(a.getItemId()))
                .findFirst().orElseThrow();
        var archiveDetails = objectMapper.readTree(archiveAudit.getDetails());
        assertEquals(admin.getId(), archiveAudit.getAdminId());
        assertEquals(admin.getName(), archiveAudit.getAdminNameSnapshot());
        assertEquals(news.getTitle(), archiveAudit.getItemNameSnapshot());
        assertTrue(archiveDetails.path("before").path("expires_at").isNull());
        assertFalse(archiveDetails.path("after").path("expires_at").isNull());

        mockMvc.perform(authed(post("/api/news/" + news.getId() + "/unarchive"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_archived").value(false));

        AuditLog unarchiveAudit = auditLogRepository.findAll().stream()
                .filter(a -> "UNARCHIVE".equals(a.getAction()) && "news".equals(a.getItemType())
                        && news.getId().equals(a.getItemId()))
                .findFirst().orElseThrow();
        var unarchiveDetails = objectMapper.readTree(unarchiveAudit.getDetails());
        assertFalse(unarchiveDetails.path("before").path("expires_at").isNull());
        assertTrue(unarchiveDetails.path("after").path("expires_at").isNull());
        assertEquals("SUCCESS", unarchiveDetails.path("result").asText());
    }

    @Test
    void newsHistoryAndRestoreLifecycle() throws Exception {
        User admin = createUser("news-history-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        News news = createNewsDirect("ორიგინალი სათაური", "All", false, admin.getId());

        mockMvc.perform(authed(put("/api/news/" + news.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("პირველი რედაქცია", "All")))
                .andExpect(status().isOk());

        String historyBody = mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("ორიგინალი სათაური"))
                .andExpect(jsonPath("$[0].author_name").value("ტესტ მომხმარებელი"))
                .andReturn().getResponse().getContentAsString();
        long historyId = objectMapper.readTree(historyBody).get(0).get("id").asLong();

        mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history-summary"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("ორიგინალი სათაური"))
                .andExpect(jsonPath("$[0].content").doesNotExist());
        mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history/" + historyId), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ორიგინალი სათაური"))
                .andExpect(jsonPath("$.content").value("შინაარსი"));

        mockMvc.perform(authed(post("/api/news/" + news.getId() + "/history/" + historyId + "/restore"),
                        tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ორიგინალი სათაური"))
                .andExpect(jsonPath("$.version").value(3));

        AuditLog restoreAudit = auditLogRepository.findAll().stream()
                .filter(a -> "RESTORE_VERSION".equals(a.getAction()) && "news".equals(a.getItemType())
                        && news.getId().equals(a.getItemId()))
                .findFirst().orElseThrow();
        var restoreDetails = objectMapper.readTree(restoreAudit.getDetails());
        assertEquals("პირველი რედაქცია", restoreDetails.path("before").path("title").asText());
        assertEquals(2, restoreDetails.path("before").path("version").asInt());
        assertEquals("ორიგინალი სათაური", restoreDetails.path("after").path("title").asText());
        assertEquals(3, restoreDetails.path("after").path("version").asInt());

        // The restore itself archived the pre-restore ("პირველი რედაქცია") state.
        mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void historyIdsCannotCrossNewsBoundariesAndRejectedRestoreDoesNotMutate() throws Exception {
        User admin = createUser("news-history-idor@magti.ge", Role.CONTENT_ADMIN, "All");
        News target = createNewsDirect("სამიზნე სიახლე", "All", false, admin.getId());
        News foreign = createNewsDirect("სხვა სიახლე", "All", false, admin.getId());

        mockMvc.perform(authed(put("/api/news/" + target.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("სამიზნე განახლება", "All")))
                .andExpect(status().isOk());
        mockMvc.perform(authed(put("/api/news/" + foreign.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("სხვა განახლება", "All")))
                .andExpect(status().isOk());

        long foreignHistoryId = newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                foreign.getId(), ge.magti.portal.query.CompleteResultGuard.sentinelPage()).getFirst().getId();
        News before = newsRepository.findById(target.getId()).orElseThrow();
        int beforeHistoryCount = newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                target.getId(), ge.magti.portal.query.CompleteResultGuard.sentinelPage()).size();

        mockMvc.perform(authed(
                        get("/api/news/" + target.getId() + "/history/" + foreignHistoryId), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ისტორიის ვერსია ვერ მოიძებნა"));
        mockMvc.perform(authed(
                        post("/api/news/" + target.getId() + "/history/" + foreignHistoryId + "/restore"),
                        tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ისტორიის ვერსია ვერ მოიძებნა"));

        entityManager.flush();
        entityManager.clear();
        News after = newsRepository.findById(target.getId()).orElseThrow();
        assertEquals(before.getTitle(), after.getTitle());
        assertEquals(before.getContent(), after.getContent());
        assertEquals(before.getVersion(), after.getVersion());
        assertEquals(beforeHistoryCount, newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                target.getId(), ge.magti.portal.query.CompleteResultGuard.sentinelPage()).size());
        assertTrue(auditLogRepository.findAll().stream().noneMatch(a ->
                "RESTORE_VERSION".equals(a.getAction()) && "news".equals(a.getItemType())
                        && target.getId().equals(a.getItemId())));
    }

    @Test
    void legacyFullNewsHistoryHasAnAggregateClobBudgetButSummaryDoesNotLoadContent() throws Exception {
        User admin = createUser("news-history-budget@magti.ge", Role.CONTENT_ADMIN, "All");
        News news = createNewsDirect("დიდი ისტორია", "All", false, admin.getId());
        mockMvc.perform(authed(put("/api/news/" + news.getId()), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("ახალი სათაური", "All")))
                .andExpect(status().isOk());
        var history = newsHistoryRepository.findByNewsIdOrderByUpdatedAtDesc(
                news.getId(), ge.magti.portal.query.CompleteResultGuard.sentinelPage()).getFirst();
        history.setContent("x".repeat((int) HistoryPayloadGuard.MAX_FULL_RESPONSE_CHARACTERS + 1));
        newsHistoryRepository.saveAndFlush(history);
        entityManager.clear();

        mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history-summary"), tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").doesNotExist());
        mockMvc.perform(authed(get("/api/news/" + news.getId() + "/history"), tokenFor(admin)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value(
                        "ისტორიის სრული ტექსტის მოცულობა უსაფრთხო დამუშავების ზღვარს აჭარბებს"));
    }

    @Test
    void autosavePartiallyUpdatesOnlyProvidedFields() throws Exception {
        User admin = createUser("news-autosave-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        News news = createNewsDirect("საწყისი სათაური", "All", true, admin.getId());
        news.setContent("ორიგინალი შინაარსი");
        newsRepository.saveAndFlush(news);

        mockMvc.perform(authed(patch("/api/news/" + news.getId() + "/autosave"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ავტოშენახული სათაური\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ავტოშენახული სათაური"))
                .andExpect(jsonPath("$.content").value("ორიგინალი შინაარსი"));

        News reloaded = newsRepository.findById(news.getId()).orElseThrow();
        assertEquals("ავტოშენახული სათაური", reloaded.getTitle());
        assertEquals("ორიგინალი შინაარსი", reloaded.getContent());

        var audit = auditLogRepository.findAll().stream()
                .filter(a -> "AUTOSAVE".equals(a.getAction()) && news.getId().equals(a.getItemId()))
                .findFirst().orElseThrow();
        var details = objectMapper.readTree(audit.getDetails());
        assertEquals("საწყისი სათაური", details.at("/before/title").asText());
        assertEquals("ავტოშენახული სათაური", details.at("/after/title").asText());
        assertEquals("SUCCESS", details.get("result").asText());
        assertFalse(audit.getDetails().contains("ორიგინალი შინაარსი"));
    }

    @Test
    void updatingAndDeletingMissingNewsIs404() throws Exception {
        User admin = createUser("news-missing-admin@magti.ge", Role.CONTENT_ADMIN, "All");

        mockMvc.perform(authed(put("/api/news/999999999"), tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newsRequestJson("x", "All")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სიახლე ვერ მოიძებნა"));

        mockMvc.perform(authed(delete("/api/news/999999999"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("სიახლე ვერ მოიძებნა"));
    }

    @Test
    void restoringMissingHistoryEntryIs404() throws Exception {
        User admin = createUser("news-missing-history-admin@magti.ge", Role.CONTENT_ADMIN, "All");
        News news = createNewsDirect("სათაური", "All", false, admin.getId());

        mockMvc.perform(authed(post("/api/news/" + news.getId() + "/history/999999999/restore"), tokenFor(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ისტორიის ვერსია ვერ მოიძებნა"));
    }
}
