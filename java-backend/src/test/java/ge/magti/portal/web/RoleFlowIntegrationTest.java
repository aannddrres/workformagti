package ge.magti.portal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.AdminExportDataset;
import ge.magti.portal.export.AdminExportFamily;
import ge.magti.portal.export.AdminExportQueryService;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.export.ReadingExportRow;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portal as its people use it, one role handing over to the next: a
 * content administrator publishes, operators find it, read it and confirm it,
 * and the administrators and managers watching see the same numbers on every
 * screen they have. Each test asks every screen of every role on the very next
 * request -- nothing here waits, because nothing in the portal should have to:
 * visibility is decided per request (ArticleVisibility, MandatoryReach) and
 * the numbers are computed per request, not by a job.
 *
 * <p>Asked for by the owner on 2026-10-01: "how long until operators see what
 * a content admin publishes, does each role see the right content, and do the
 * operators' reads reach the administrator". Departments are nonce groups of
 * the real buckets, so the department dashboard counts them and nobody else's
 * fixtures are counted with them.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RoleFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ExportQueryService exportQueryService;
    @Autowired
    private AdminExportQueryService adminExportQueryService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * One small company per test: two departments, their operators and
     * managers, the editors. As in the directory, one operator sits in the
     * department itself and one in a group of it ("X — ჯგუფი 01"); content
     * addressed to the department reaches both.
     */
    private final class Org {
        final String nonce = "RF" + System.nanoTime();
        final String tech = "ტექნიკური" + nonce;
        final String techGroup = tech + " — ჯგუფი 01";
        final String info = "საინფორმაციო" + nonce;
        final Team techTeam = team(tech);
        final Team infoTeam = team(info);
        final User techOne = user(Role.OPERATOR, tech, techTeam);
        final User techTwo = user(Role.OPERATOR, techGroup, techTeam);
        final User infoOne = user(Role.OPERATOR, info, infoTeam);
        final User techManager = user(Role.MANAGER, tech, techTeam);
        final User infoManager = user(Role.MANAGER, info, infoTeam);
        final User editor = user(Role.CONTENT_ADMIN, "All", null);
        final User admin = user(Role.SYSTEM_ADMIN, "All", null);
        final Category category = category();
    }

    // ── 1. publishing ────────────────────────────────────────────────

    @Test
    void aPublishedArticleReachesItsDepartmentOnTheNextRequestAndNoOtherDepartment() throws Exception {
        Org org = new Org();
        String title = "გამოქვეყნება " + org.nonce;

        long published = System.nanoTime();
        long id = createArticle(org.editor, org.category, title, List.of(org.tech), "published", false, null);
        long publishMillis = (System.nanoTime() - published) / 1_000_000;

        for (User reader : List.of(org.techOne, org.techTwo, org.techManager, org.editor, org.admin)) {
            assertSeesArticle(reader, id, org.nonce, true);
        }
        for (User outsider : List.of(org.infoOne, org.infoManager)) {
            assertSeesArticle(outsider, id, org.nonce, false);
        }
        assertTrue(publishMillis < 10_000, "publishing itself took " + publishMillis + " ms");
    }

    @Test
    void theHeaderSearchAnswersFromTodaysContentEvenRightAfterTheSameSearch() throws Exception {
        Org org = new Org();
        assertTrue(headerSearchIds(org.techOne, org.nonce).isEmpty(), "nothing yet");

        long id = createArticle(org.editor, org.category, "ახალი ძებნაში " + org.nonce, List.of(org.tech),
                "published", false, null);
        assertTrue(headerSearchIds(org.techOne, org.nonce).contains(id),
                "it held the empty answer for 60 s (measured live: 59.9 s)");

        mockMvc.perform(authed(post("/api/articles/" + id + "/archive"), org.editor)).andExpect(status().isOk());
        assertFalse(headerSearchIds(org.techOne, org.nonce).contains(id),
                "and listed the archived article for a minute, opening to 'not found'");
    }

    @Test
    void anArticleForEveryoneReachesEveryDepartment() throws Exception {
        Org org = new Org();
        long id = createArticle(org.editor, org.category, "ყველასთვის " + org.nonce, List.of("All"),
                "published", false, null);

        for (User reader : List.of(org.techOne, org.infoOne, org.techManager, org.infoManager)) {
            assertSeesArticle(reader, id, org.nonce, true);
        }
    }

    @Test
    void draftsAndScheduledArticlesStayHiddenUntilTheirMomentAndNoJobIsNeeded() throws Exception {
        Org org = new Org();
        long draft = createArticle(org.editor, org.category, "მონახაზი " + org.nonce, List.of(org.tech),
                "draft", false, null);
        long scheduled = createArticle(org.editor, org.category, "დაგეგმილი " + org.nonce, List.of(org.tech),
                "scheduled", false, TbilisiTime.now().plusHours(2));

        for (long id : List.of(draft, scheduled)) {
            assertSeesArticle(org.techOne, id, org.nonce, false);
            assertSeesArticle(org.techManager, id, org.nonce, false);
            assertTrue(listedIds(org.editor, org.nonce).contains(id), "the editor keeps seeing their work");
        }

        // The publication moment passes. Nothing flips the status column: the
        // date alone decides, on the very next request.
        Article article = articleRepository.findById(scheduled).orElseThrow();
        article.setPublishedAt(TbilisiTime.now().minusSeconds(1));
        articleRepository.saveAndFlush(article);

        assertSeesArticle(org.techOne, scheduled, org.nonce, true);
        assertSeesArticle(org.infoOne, scheduled, org.nonce, false);
        assertSeesArticle(org.techOne, draft, org.nonce, false);
    }

    @Test
    void aPersonalDraftIsTheAuthorsAloneWhateverTheRole() throws Exception {
        Org org = new Org();
        long id = createArticle(org.editor, org.category, "პირადი მონახაზი " + org.nonce, List.of("All"),
                "draft", true, null);

        assertTrue(listedIds(org.editor, org.nonce).contains(id));
        mockMvc.perform(authed(get("/api/articles/" + id), org.editor)).andExpect(status().isOk());
        User otherEditor = user(Role.CONTENT_ADMIN, "All", null);
        for (User other : List.of(otherEditor, org.admin, org.techOne, org.techManager)) {
            assertSeesArticle(other, id, org.nonce, false);
        }
    }

    // ── 2. what operators read reaches the people who watch ──────────

    @Test
    void aMandatoryReadingIsOwedAtOnceAndEveryConfirmationReachesEveryAdminAndManagerScreen() throws Exception {
        Org org = new Org();
        long articleId = createMandatory(org, "სავალდებულო " + org.nonce, TbilisiTime.now().plusDays(3));
        long readingId = readingOf(articleId).getId();

        // Owed by the department's operators only, on the next request.
        for (User operator : List.of(org.techOne, org.techTwo)) {
            assertEquals("unread", myReading(operator, readingId).get("status").asText());
            assertTrue(bellReadingIds(operator).contains(readingId));
            assertProgress(operator, 1, 0);
        }
        assertTrue(myReadingIds(org.infoOne).isEmpty());
        assertFalse(bellReadingIds(org.infoOne).contains(readingId));
        assertProgress(org.infoOne, 0, 0);

        // Before anybody confirms: every screen says 0 of 1.
        assertWatchersSee(org, articleId, Map.of(org.techOne, 0, org.techTwo, 0));

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isOk());

        // The very next request, on every screen.
        assertEquals("read", myReading(org.techOne, readingId).get("status").asText());
        assertFalse(bellReadingIds(org.techOne).contains(readingId));
        assertProgress(org.techOne, 1, 1);
        assertTrue(bellReadingIds(org.techTwo).contains(readingId), "one confirmation is not another's");
        assertWatchersSee(org, articleId, Map.of(org.techOne, 1, org.techTwo, 0));

        // The other department's manager sees neither operator anywhere.
        assertTrue(teamStatsCounts(org.infoManager).keySet().stream()
                .noneMatch(id -> id.equals(org.techOne.getId()) || id.equals(org.techTwo.getId())));
        assertTrue(criticalOverdue(org.infoManager).isEmpty());
    }

    @Test
    void aPassedDeadlineMakesTheMissingConfirmationOverdueOnEveryScreenAndOnlyThatOne() throws Exception {
        Org org = new Org();
        long articleId = createMandatory(org, "ვადიანი " + org.nonce, TbilisiTime.now().plusDays(3));
        RequiredReading reading = readingOf(articleId);
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + reading.getId()), org.techOne))
                .andExpect(status().isOk());

        assertEquals(0, criticalOverdue(org.admin).getOrDefault(org.techTwo.getId(), 0),
                "nothing is overdue before the deadline");

        reading.setDueDate(TbilisiTime.now().minusMinutes(5));
        requiredReadingRepository.saveAndFlush(reading);

        JsonNode late = myReading(org.techTwo, reading.getId());
        assertEquals("overdue", late.get("status").asText());
        assertTrue(late.get("is_overdue").asBoolean());
        assertTrue(bellOverdue(org.techTwo, reading.getId()));
        assertEquals("read", myReading(org.techOne, reading.getId()).get("status").asText());

        for (User watcher : List.of(org.admin, org.techManager)) {
            Map<Long, Integer> overdue = criticalOverdue(watcher);
            assertEquals(1, overdue.get(org.techTwo.getId()), watcher.getRole() + " sees the overdue operator");
            assertFalse(overdue.containsKey(org.techOne.getId()), watcher.getRole() + ": the reader is not critical");
        }
        Map<Long, String> exported = exportStatuses(org.admin, articleId);
        assertEquals("read", exported.get(org.techOne.getId()));
        assertEquals("overdue", exported.get(org.techTwo.getId()));
    }

    @Test
    void anOperatorWithSomethingOverdueCountsAsCriticalOnTheDashboardTileAsInItsList() throws Exception {
        Org org = new Org();
        long first = createMandatory(org, "პირველი " + org.nonce, TbilisiTime.now().plusDays(3));
        long second = createMandatory(org, "მეორე " + org.nonce, TbilisiTime.now().plusDays(3));
        for (User operator : List.of(org.techOne, org.techTwo)) {
            mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingOf(first).getId()), operator))
                    .andExpect(status().isOk());
        }
        RequiredReading overdue = readingOf(second);
        overdue.setDueDate(TbilisiTime.now().minusMinutes(5));
        requiredReadingRepository.saveAndFlush(overdue);
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + overdue.getId()), org.techTwo))
                .andExpect(status().isOk());

        // techOne: 1 of 2 read (50 %), the other one overdue.
        Map<Long, Integer> list = criticalOverdue(org.techManager);
        assertEquals(Set.of(org.techOne.getId()), list.keySet(), "the drill-down list");
        JsonNode dashboard = json(get("/api/manager/department-stats"), org.techManager);
        assertEquals(list.size(), dashboard.get("insights").get("critical_operators").asInt(),
                "the tile that opens that list counts the same people");
    }

    @Test
    void aChangedTextAsksForANewConfirmationAndKeepsTheFirstOnRecord() throws Exception {
        Org org = new Org();
        long articleId = createMandatory(org, "ცვლილება " + org.nonce, TbilisiTime.now().plusDays(3));
        long readingId = readingOf(articleId).getId();
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isOk());
        assertFalse(myReading(org.techOne, readingId).get("changed_since_read").asBoolean());

        updateArticle(org.editor, articleId, Map.of("content", "<p>ახალი ტექსტი " + org.nonce + "</p>"));

        JsonNode changed = myReading(org.techOne, readingId);
        assertTrue(changed.get("changed_since_read").asBoolean(), "the reader is told the text moved on");
        assertEquals("read", changed.get("status").asText(), "the first confirmation stays a real event");
        JsonNode current = json(get("/api/articles/" + articleId + "/read-receipts"), org.editor);
        assertEquals(2, current.get("current_version").asInt());
        assertEquals(0, current.get("read_count").asInt(), "nobody has confirmed the new version yet");
        JsonNode first = json(get("/api/articles/" + articleId + "/read-receipts?version=1"), org.editor);
        assertEquals(1, first.get("read_count").asInt(), "the first version's confirmation is kept");

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isOk());

        assertFalse(myReading(org.techOne, readingId).get("changed_since_read").asBoolean());
        assertEquals(1, json(get("/api/articles/" + articleId + "/read-receipts"), org.editor)
                .get("read_count").asInt());
    }

    @Test
    void openingAnArticleReachesTheSystemAdminsViewLog() throws Exception {
        Org org = new Org();
        long id = createArticle(org.editor, org.category, "ნახვები " + org.nonce, List.of(org.tech),
                "published", false, null);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(authed(post("/api/articles/" + id + "/view"), org.techOne)).andExpect(status().isOk());
        }
        mockMvc.perform(authed(post("/api/articles/" + id + "/view"), org.techTwo)).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/articles/" + id + "/view"), org.infoOne)).andExpect(status().isNotFound());

        JsonNode views = json(get("/api/articles/" + id + "/views"), org.admin);
        assertEquals(3, views.get("total_views").asLong());
        assertEquals(2, views.get("unique_viewers").asLong());
        assertTrue(ids(json(get("/api/me/recently-viewed"), org.techOne), "article_id").contains(id));
        mockMvc.perform(authed(get("/api/articles/" + id + "/views"), org.editor)).andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/articles/" + id + "/views"), org.techManager)).andExpect(status().isForbidden());
    }

    @Test
    void aQuizGuardsTheConfirmationAndEveryAttemptReachesTheAdminsExport() throws Exception {
        Org org = new Org();
        long articleId = createMandatory(org, "ქვიზი " + org.nonce, TbilisiTime.now().plusDays(3));
        Article article = articleRepository.findById(articleId).orElseThrow();
        article.setQuizEnabled(true);
        articleRepository.saveAndFlush(article);
        JsonNode quiz = objectMapper.readTree(mockMvc.perform(authed(put("/api/articles/" + articleId + "/quiz/admin"), org.editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[{\"question_text\":\"რამდენია 2+2?\",\"position\":0,\"answers\":["
                                + "{\"answer_text\":\"3\",\"is_correct\":false,\"position\":0},"
                                + "{\"answer_text\":\"4\",\"is_correct\":true,\"position\":1}]}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("questions").get(0);
        long question = quiz.get("id").asLong();
        long wrong = quiz.get("answers").get(0).get("id").asLong();
        long right = quiz.get("answers").get(1).get("id").asLong();
        long readingId = readingOf(articleId).getId();

        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isForbidden());
        attempt(org.techOne, articleId, question, wrong, false);
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isForbidden());
        attempt(org.techOne, articleId, question, right, true);
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techOne))
                .andExpect(status().isOk());

        AdminExportDataset attempts = adminExportQueryService.load(
                AdminExportFamily.QUIZ_ATTEMPTS, TbilisiTime.now().toLocalDate(), TbilisiTime.now().toLocalDate());
        List<List<Object>> mine = attempts.rows().stream()
                .filter(row -> ((Number) row.get(2)).longValue() == org.techOne.getId())
                .filter(row -> ((Number) row.get(5)).longValue() == articleId)
                .toList();
        assertEquals(List.of("კი", "არა"), mine.stream().map(row -> row.get(11)).toList(),
                "both attempts, newest first, with their verdicts");
        assertWatchersSee(org, articleId, Map.of(org.techOne, 1, org.techTwo, 0));
        mockMvc.perform(authed(get("/api/users/me/knowledge-score"), org.techOne))
                .andExpect(jsonValue("articles_passed", 1));
    }

    // ── 3. taking content away ───────────────────────────────────────

    @Test
    void archivingTakesTheArticleOffEveryOperatorScreenAndSuspendsWhatItWasOwed() throws Exception {
        Org org = new Org();
        long articleId = createMandatory(org, "დაარქივება " + org.nonce, TbilisiTime.now().plusDays(3));
        long readingId = readingOf(articleId).getId();
        mockMvc.perform(authed(post("/api/articles/" + articleId + "/view"), org.techOne)).andExpect(status().isOk());
        mockMvc.perform(authed(post("/api/compliance/mark-read/" + readingId), org.techTwo))
                .andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/archive"), org.editor))
                .andExpect(status().isOk());

        assertSeesArticle(org.techOne, articleId, org.nonce, false);
        assertFalse(ids(json(get("/api/me/recently-viewed"), org.techOne), "article_id").contains(articleId));
        assertFalse(myReadingIds(org.techOne).contains(readingId));
        assertFalse(bellReadingIds(org.techOne).contains(readingId));
        assertProgress(org.techOne, 0, 0);
        assertFalse(criticalOverdue(org.techManager).containsKey(org.techOne.getId()),
                "an obligation not in force makes nobody critical");
        // Evidence is never erased by archiving: the confirmation stays in the
        // export and on the article's receipts, though nobody is owed it now.
        assertEquals("read", exportStatuses(org.admin, articleId).get(org.techTwo.getId()));
        assertEquals(Boolean.TRUE, receiptRows(org.admin, articleId).get(org.techTwo.getId()));

        mockMvc.perform(authed(post("/api/articles/" + articleId + "/unarchive"), org.editor))
                .andExpect(status().isOk());
        assertTrue(myReadingIds(org.techOne).contains(readingId), "restoring the article restores the obligation");
        assertSeesArticle(org.techOne, articleId, org.nonce, true);
    }

    @Test
    void retargetingMovesTheArticleBetweenDepartmentsOnTheNextRequest() throws Exception {
        Org org = new Org();
        long id = createArticle(org.editor, org.category, "გადატანა " + org.nonce, List.of(org.tech),
                "published", false, null);
        mockMvc.perform(authed(post("/api/articles/" + id + "/view"), org.techOne)).andExpect(status().isOk());

        mockMvc.perform(authed(post("/api/articles/bulk-retarget"), org.editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("ids", List.of(id),
                                "target_departments", List.of(org.info)))))
                .andExpect(status().isOk());

        assertSeesArticle(org.techOne, id, org.nonce, false);
        assertSeesArticle(org.techManager, id, org.nonce, false);
        assertFalse(ids(json(get("/api/me/recently-viewed"), org.techOne), "article_id").contains(id));
        assertSeesArticle(org.infoOne, id, org.nonce, true);
        assertSeesArticle(org.infoManager, id, org.nonce, true);
    }

    // ── 4. news ──────────────────────────────────────────────────────

    @Test
    void newsReachesItsDepartmentInTheListAndTheBellAndLeavesBothWhenArchived() throws Exception {
        Org org = new Org();
        String title = "სიახლე " + org.nonce;
        long id = objectMapper.readTree(mockMvc.perform(authed(post("/api/news"), org.editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("title", title, "content", "<p>ტექსტი</p>",
                                "target_department", org.tech, "is_draft", false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();

        for (User reader : List.of(org.techOne, org.techManager)) {
            assertTrue(ids(json(get("/api/news?limit=100"), reader), "id").contains(id), reader.getRole() + " list");
            mockMvc.perform(authed(get("/api/news/" + id), reader)).andExpect(status().isOk());
        }
        assertTrue(ids(json(get("/api/notifications/summary"), org.techOne).get("recent_news"), "id").contains(id));
        assertFalse(ids(json(get("/api/news?limit=100"), org.infoOne), "id").contains(id));
        assertFalse(ids(json(get("/api/notifications/summary"), org.infoOne).get("recent_news"), "id").contains(id));
        mockMvc.perform(authed(get("/api/news/" + id), org.infoOne)).andExpect(status().isNotFound());

        OffsetDateTime archivedAt = OffsetDateTime.parse(json(post("/api/news/" + id + "/archive"), org.editor)
                .get("expires_at").asText());
        // Archiving news is "expires now". Under load Windows can hand the next
        // request the same clock reading, and Oracle rounds the stored value to
        // the microsecond -- up, as often as not -- so for that one reading the
        // news has not expired yet (seen once in the full suite, 2026-10-01).
        // Nobody can request inside that microsecond; the test could.
        while (!TbilisiTime.now().isAfter(archivedAt.plusNanos(1_000))) {
            Thread.onSpinWait();
        }

        assertFalse(ids(json(get("/api/news?limit=100"), org.techOne), "id").contains(id));
        assertFalse(ids(json(get("/api/notifications/summary"), org.techOne).get("recent_news"), "id").contains(id));
    }

    // ── what one screen says, every screen must say ──────────────────

    /** Admin users table, admin progress, manager team, read receipts and both exports agree. */
    private void assertWatchersSee(Org org, long articleId, Map<User, Integer> readByOperator) throws Exception {
        Map<Long, Integer> usersTable = new LinkedHashMap<>();
        for (JsonNode row : json(get("/api/users?limit=1000"), org.admin)) {
            usersTable.put(row.get("id").asLong(), row.get("read_count").isNull() ? null : row.get("read_count").asInt());
        }
        Map<Long, int[]> progress = new LinkedHashMap<>();
        for (JsonNode row : json(get("/api/statistics/user-progress?limit=1000"), org.admin)) {
            progress.put(row.get("user_id").asLong(),
                    new int[] {row.get("read_count").asInt(), row.get("required_count").asInt()});
        }
        Map<Long, Integer> team = teamStatsCounts(org.techManager);
        // The editor gets the article's numbers; names go to the system admin
        // and to the leader of the operators' team (PO-40).
        JsonNode receipts = json(get("/api/articles/" + articleId + "/read-receipts"), org.editor);
        assertEquals(0, receipts.get("receipts").size(), "an editor reads numbers, not names");
        Map<Long, Boolean> adminRows = receiptRows(org.admin, articleId);
        Map<Long, Boolean> managerRows = receiptRows(org.techManager, articleId);
        Map<Long, String> adminExport = exportStatuses(org.admin, articleId);
        Map<Long, String> managerExport = exportStatuses(org.techManager, articleId);

        int totalRead = 0;
        for (Map.Entry<User, Integer> expected : readByOperator.entrySet()) {
            long id = expected.getKey().getId();
            int read = expected.getValue();
            totalRead += read;
            String who = expected.getKey().getEmail();
            assertEquals(read, usersTable.get(id), "admin users table, " + who);
            assertEquals(read, progress.get(id)[0], "admin progress, " + who);
            assertEquals(1, progress.get(id)[1], "admin progress owed, " + who);
            assertEquals(read, team.get(id), "manager team screen, " + who);
            assertEquals(read == 1, adminRows.get(id), "article read receipts for the admin, " + who);
            assertEquals(read == 1, managerRows.get(id), "article read receipts for the manager, " + who);
            String status = read == 1 ? "read" : "unread";
            assertEquals(status, adminExport.get(id), "admin export, " + who);
            assertEquals(status, managerExport.get(id), "manager export, " + who);
        }
        assertEquals(readByOperator.size(), receipts.get("eligible_count").asInt(), "addressed operators");
        assertEquals(totalRead, receipts.get("read_count").asInt(), "confirmed operators");
        assertFalse(adminRows.containsKey(org.infoOne.getId()), "the other department is not addressed");
        assertFalse(adminExport.containsKey(org.infoOne.getId()), "the other department owes nothing");
        assertFalse(managerExport.containsKey(org.infoOne.getId()), "and is not the manager's to export");
    }

    private void assertSeesArticle(User user, long id, String nonce, boolean expected) throws Exception {
        String who = user.getRole() + " in " + user.getDepartment();
        assertEquals(expected, listedIds(user, nonce).contains(id), who + ": the article list");
        mockMvc.perform(authed(get("/api/articles/" + id), user))
                .andExpect(expected ? status().isOk() : status().isNotFound());
        assertEquals(expected, ids(json(get("/api/search?q=" + nonce), user), "id").contains(id),
                who + ": search");
        assertEquals(expected, headerSearchIds(user, nonce).contains(id), who + ": the header search");
    }

    private void assertProgress(User operator, int owed, int done) throws Exception {
        JsonNode progress = json(get("/api/compliance/my-progress"), operator);
        assertEquals(owed, progress.get("total_mandatory").asInt(), "owed, " + operator.getEmail());
        assertEquals(done, progress.get("read_completed").asInt(), "done, " + operator.getEmail());
    }

    // ── reading the screens ──────────────────────────────────────────

    private Set<Long> listedIds(User user, String nonce) throws Exception {
        return ids(json(get("/api/articles?limit=100&q=" + nonce), user), "id");
    }

    private Set<Long> headerSearchIds(User user, String words) throws Exception {
        return ids(json(get("/api/search/global?q=" + words), user).get("articles"), "id");
    }

    private JsonNode myReading(User operator, long readingId) throws Exception {
        for (JsonNode entry : json(get("/api/compliance/my-readings"), operator)) {
            if (entry.get("reading").get("id").asLong() == readingId) {
                return entry;
            }
        }
        throw new AssertionError("reading " + readingId + " is not in " + operator.getEmail() + "'s list");
    }

    private Set<Long> myReadingIds(User operator) throws Exception {
        return StreamSupport.stream(json(get("/api/compliance/my-readings"), operator).spliterator(), false)
                .map(entry -> entry.get("reading").get("id").asLong())
                .collect(Collectors.toSet());
    }

    private Set<Long> bellReadingIds(User user) throws Exception {
        return ids(json(get("/api/notifications/summary"), user).get("unread_readings"), "id");
    }

    private boolean bellOverdue(User user, long readingId) throws Exception {
        for (JsonNode item : json(get("/api/notifications/summary"), user).get("unread_readings")) {
            if (item.get("id").asLong() == readingId) {
                return item.get("is_overdue").asBoolean();
            }
        }
        throw new AssertionError("reading " + readingId + " is not in the bell");
    }

    private Map<Long, Integer> teamStatsCounts(User manager) throws Exception {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (JsonNode member : json(get("/api/manager/team-stats"), manager).get("members")) {
            counts.put(member.get("user_id").asLong(), member.get("read_count").asInt());
        }
        return counts;
    }

    private Map<Long, Integer> criticalOverdue(User watcher) throws Exception {
        Map<Long, Integer> overdue = new LinkedHashMap<>();
        for (JsonNode operator : json(get("/api/admin/critical-operators"), watcher).get("operators")) {
            overdue.put(operator.get("user_id").asLong(), operator.get("overdue_count").asInt());
        }
        return overdue;
    }

    private Map<Long, Boolean> receiptRows(User watcher, long articleId) throws Exception {
        Map<Long, Boolean> rows = new LinkedHashMap<>();
        for (JsonNode row : json(get("/api/articles/" + articleId + "/read-receipts"), watcher).get("receipts")) {
            rows.put(row.get("operator_id").asLong(), row.get("has_read").asBoolean());
        }
        return rows;
    }

    /** The readings export, as the caller may download it, for one article. */
    private Map<Long, String> exportStatuses(User caller, long articleId) {
        Map<Long, String> statuses = new LinkedHashMap<>();
        for (ReadingExportRow row : exportQueryService.eligibleReadingRows(caller)) {
            if ("article".equals(row.itemType()) && row.itemId() != null && row.itemId() == articleId) {
                statuses.put(row.userId(), row.status());
            }
        }
        return statuses;
    }

    private JsonNode json(MockHttpServletRequestBuilder request, User user) throws Exception {
        return objectMapper.readTree(mockMvc.perform(authed(request, user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private static Set<Long> ids(JsonNode list, String field) {
        Set<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : list) {
            ids.add(item.get(field).asLong());
        }
        return ids;
    }

    private static ResultMatcher jsonValue(String field, int expected) {
        return result -> assertEquals(expected,
                new ObjectMapper().readTree(result.getResponse().getContentAsByteArray()).get(field).asInt());
    }

    // ── what the editors do ──────────────────────────────────────────

    private long createArticle(User editor, Category category, String title, List<String> targets, String status,
                               boolean personalDraft, OffsetDateTime publishedAt) throws Exception {
        Map<String, Object> article = articleBody(category, title, "<p>" + title + "</p>", targets, status,
                personalDraft, publishedAt);
        return objectMapper.readTree(mockMvc.perform(authed(post("/api/articles/command"), editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("article", article, "mandatory", false))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private long createMandatory(Org org, String title, OffsetDateTime due) throws Exception {
        Map<String, Object> article = articleBody(org.category, title, "<p>" + title + "</p>", List.of(org.tech),
                "published", false, null);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("article", article);
        command.put("mandatory", true);
        command.put("due_date", due.toString());
        return objectMapper.readTree(mockMvc.perform(authed(post("/api/articles/command"), org.editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(command)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private void updateArticle(User editor, long id, Map<String, Object> changes) throws Exception {
        JsonNode current = json(get("/api/articles/" + id), editor);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", current.get("title").asText());
        body.put("content", current.get("content").asText());
        body.put("category_id", current.get("category_id").asLong());
        List<String> targets = new ArrayList<>();
        current.get("target_departments").forEach(node -> targets.add(node.asText()));
        body.put("target_departments", targets);
        body.put("status", current.get("status").asText());
        body.put("is_draft", false);
        body.put("quiz_enabled", current.get("quiz_enabled").asBoolean());
        body.put("lock_version", current.get("lock_version").asInt());
        body.putAll(changes);
        mockMvc.perform(authed(put("/api/articles/" + id), editor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().isOk());
    }

    private void attempt(User operator, long articleId, long question, long answer, boolean passes) throws Exception {
        JsonNode result = objectMapper.readTree(mockMvc.perform(authed(post("/api/articles/" + articleId + "/quiz/attempt"), operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":{\"" + question + "\":" + answer + "}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(passes, result.get("passed").asBoolean());
    }

    private static Map<String, Object> articleBody(Category category, String title, String content,
                                                   List<String> targets, String status, boolean personalDraft,
                                                   OffsetDateTime publishedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        body.put("category_id", category.getId());
        body.put("target_departments", targets);
        body.put("status", status);
        body.put("is_draft", personalDraft);
        body.put("quiz_enabled", false);
        if (publishedAt != null) {
            body.put("published_at", publishedAt.toString());
        }
        return body;
    }

    private RequiredReading readingOf(long articleId) {
        List<RequiredReading> readings = requiredReadingRepository.findByItemTypeAndItemId("article", articleId);
        assertEquals(1, readings.size(), "one reading for the one department");
        assertNotNull(readings.getFirst().getDueDate());
        return readings.getFirst();
    }

    // ── the people ───────────────────────────────────────────────────

    private Team team(String name) {
        Team team = new Team();
        team.setName(name);
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private Category category() {
        Category category = new Category();
        category.setName("role-flow-" + System.nanoTime());
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private User user(Role role, String department, Team team) {
        User user = new User();
        user.setEmail("flow-" + role.value() + "-" + System.nanoTime() + "@magti.ge");
        user.setName("ნაკადი " + role.value() + " " + System.nanoTime());
        user.setRole(role);
        user.setDepartment(department);
        user.setTeamId(team == null ? null : team.getId());
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, User user) {
        return builder.header("Authorization", "Bearer " + jwtService.createAccessTokenFor(user));
    }
}
