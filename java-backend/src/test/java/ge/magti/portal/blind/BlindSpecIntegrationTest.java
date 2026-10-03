package ge.magti.portal.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.AssignmentType;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Department;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.LeadershipAssignmentRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box tests written from the specification only --
 * {@code docs/PRODUCT_OWNER_DECISIONS_KA.md} (PO-xx),
 * {@code docs/ACCESS_CONTRACT_MATRIX_KA.md} (D-x and its endpoint rows) and
 * {@code docs/api-contract/article-visibility-cases.json} -- without reading
 * the implementation or the author's own tests. Each test encodes one stated
 * rule and cites it.
 *
 * <p>Every test works in departments of its own (a nonce), so other fixtures
 * in the shared database can neither be counted nor named here. Where a
 * number could be influenced by company-wide ("All") material left by other
 * fixtures, the test compares before and after rather than an absolute value.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BlindSpecIntegrationTest {

    private static final String GROUP = " — ჯგუფი ";

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
    private NewsRepository newsRepository;
    @Autowired
    private VideoInstructionRepository videoRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private LeadershipAssignmentRepository leadershipAssignmentRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // ------------------------------------------------------------------
    // Content audience: departments and their groups
    // ------------------------------------------------------------------

    /**
     * Matrix, Video row ("target department, ჯგუფის prefix ან All") and the
     * group naming "<department> — ჯგუფი NN": an article addressed to a
     * department reaches the operators of every group of that department.
     */
    @Test
    void anArticleForADepartmentReachesTheOperatorsOfItsGroups() throws Exception {
        String department = department();
        User inGroup = user(Role.OPERATOR, department + GROUP + "03");
        Article article = published(department);

        perform(get("/api/articles/" + article.getId()), inGroup).andExpect(status().isOk());
    }

    /**
     * Matrix CONTENT scope (target-department visibility) and the PO-40
     * "სხვა აუდიტორია" case: an article addressed to one group is not
     * delivered to a sibling group of the same department; and a department
     * whose name merely starts with the target's characters is not one of its
     * groups.
     */
    @Test
    void groupTargetingDoesNotLeakToSiblingGroupsOrToLookAlikeDepartmentNames() throws Exception {
        String department = department();
        User groupOne = user(Role.OPERATOR, department + GROUP + "01");
        User groupTwo = user(Role.OPERATOR, department + GROUP + "02");
        User lookAlike = user(Role.OPERATOR, department + "7");
        Article forGroupOne = published(department + GROUP + "01");
        Article forDepartment = published(department);

        perform(get("/api/articles/" + forGroupOne.getId()), groupOne).andExpect(status().isOk());
        perform(get("/api/articles/" + forGroupOne.getId()), groupTwo).andExpect(status().isNotFound());
        perform(get("/api/articles/" + forDepartment.getId()), lookAlike).andExpect(status().isNotFound());
    }

    /**
     * PO-43 / PO-23: an operator who has no department yet sees only the
     * "All" articles. AGENTS.md notes the schema allows a null department, so
     * the list must not fail for such a caller either.
     */
    @Test
    void anOperatorWithoutADepartmentSeesOnlyCompanyWideArticles() throws Exception {
        User homeless = user(Role.OPERATOR, null);
        Article forAll = published("All");
        Article forDepartment = published(department());

        perform(get("/api/articles/" + forAll.getId()), homeless).andExpect(status().isOk());
        perform(get("/api/articles/" + forDepartment.getId()), homeless).andExpect(status().isNotFound());

        String list = body(perform(get("/api/articles").param("limit", "100"), homeless)
                .andExpect(status().isOk()));
        assertFalse(list.contains(forDepartment.getTitle()), "another department's article is not listed");
    }

    // ------------------------------------------------------------------
    // Lifecycle: scheduled, archived, unknown status, private drafts
    // ------------------------------------------------------------------

    /**
     * article-visibility-cases.json: "scheduled, moment has passed" is
     * visible; "moment has not arrived" and "scheduled, with no date" are not.
     */
    @Test
    void aScheduledArticleOpensOnlyOnceItsMomentHasPassed() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article due = article("scheduled", TbilisiTime.now().minusDays(1), false, null, department);
        Article future = article("scheduled", TbilisiTime.now().plusDays(1), false, null, department);
        Article undated = article("scheduled", null, false, null, department);

        perform(get("/api/articles/" + due.getId()), operator).andExpect(status().isOk());
        perform(get("/api/articles/" + future.getId()), operator).andExpect(status().isNotFound());
        perform(get("/api/articles/" + undated.getId()), operator).andExpect(status().isNotFound());
    }

    /**
     * article-visibility-cases.json ("archived", "an unrecognised status") and
     * its note that content administrators are let through for non-drafts.
     */
    @Test
    void archivedAndUnknownStatusesAreHiddenFromOperatorsButNotFromContentAdmins() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article archived = article("archived", TbilisiTime.now().minusDays(2), false, null, department);
        Article inReview = article("in_review", TbilisiTime.now().minusDays(2), false, null, department);

        perform(get("/api/articles/" + archived.getId()), operator).andExpect(status().isNotFound());
        perform(get("/api/articles/" + inReview.getId()), operator).andExpect(status().isNotFound());
        perform(get("/api/articles/" + archived.getId()), contentAdmin).andExpect(status().isOk());
    }

    /**
     * PO-34 (+ D2 addendum) and the visibility case "somebody else's autosave
     * draft that says published": an is_draft article opens only for its
     * author -- not for operators in its audience, another CONTENT_ADMIN or a
     * SYSTEM_ADMIN -- and another editor cannot overwrite it (matrix, PUT row)
     * or open its quiz (matrix, Quiz row).
     */
    @Test
    void aPrivateDraftBelongsToItsAuthorAloneOnEveryPath() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User author = user(Role.CONTENT_ADMIN, department());
        User colleague = user(Role.CONTENT_ADMIN, department());
        User admin = admin();
        Article draft = article("published", TbilisiTime.now().minusDays(1), true, author.getId(), department);
        String originalTitle = draft.getTitle();

        perform(get("/api/articles/" + draft.getId()), author).andExpect(status().isOk());
        perform(get("/api/articles/" + draft.getId()), operator).andExpect(status().isNotFound());
        perform(get("/api/articles/" + draft.getId()), colleague).andExpect(status().isNotFound());
        perform(get("/api/articles/" + draft.getId()), admin).andExpect(status().isNotFound());
        perform(get("/api/articles/" + draft.getId() + "/quiz"), operator).andExpect(status().isNotFound());

        send(put("/api/articles/" + draft.getId()), colleague,
                articleJson("overwritten " + nonce(), "<p>x</p>", draft.getCategoryId(),
                        List.of(department), "draft", false))
                .andExpect(status().isNotFound());
        assertEquals(originalTitle, articleRepository.findById(draft.getId()).orElseThrow().getTitle(),
                "another editor's write leaves the private draft unchanged");
    }

    /**
     * Matrix, Search row (PO-34/D2): another administrator no longer finds a
     * colleague's private draft, while an ordinary editorial draft (status
     * draft, is_draft false) with the same word stays findable to them.
     */
    @Test
    void searchHidesAColleaguesPrivateDraftButNotAnEditorialDraft() throws Exception {
        String department = department();
        // Content can only be addressed to a department somebody works in (422
        // otherwise, since 2026-10-01) -- not in the docs the test was written from.
        user(Role.OPERATOR, department);
        User author = user(Role.CONTENT_ADMIN, department());
        User colleague = user(Role.CONTENT_ADMIN, department());
        String word = "zq" + System.nanoTime();
        Category category = category();
        String privateTitle = word + " private";
        String editorialTitle = word + " editorial";

        send(post("/api/articles"), author, articleJson(privateTitle, "<p>" + word + "</p>", category.getId(),
                List.of(department), "draft", true))
                .andExpect(status().is2xxSuccessful());
        send(post("/api/articles"), author, articleJson(editorialTitle, "<p>" + word + "</p>", category.getId(),
                List.of(department), "draft", false))
                .andExpect(status().is2xxSuccessful());

        String search = body(perform(get("/api/search").param("q", word), colleague).andExpect(status().isOk()));
        assertFalse(search.contains(privateTitle), "a colleague's private draft is not found");
        assertTrue(search.contains(editorialTitle), "an editorial draft is found by a content administrator");

        String global = body(perform(get("/api/search/global").param("q", word), colleague)
                .andExpect(status().isOk()));
        assertFalse(global.contains(privateTitle), "nor through the global search");
    }

    // ------------------------------------------------------------------
    // News and video visibility
    // ------------------------------------------------------------------

    /**
     * PO-35: expired/archived news is 404 to an operator even by direct link,
     * but a content administrator may open it. Matrix News row: a private news
     * draft opens only for its author -- not for another CONTENT_ADMIN or a
     * SYSTEM_ADMIN.
     */
    @Test
    void expiredNewsAndPrivateNewsDraftsFollowTheirOwnAudience() throws Exception {
        User operator = user(Role.OPERATOR, department());
        User author = user(Role.CONTENT_ADMIN, department());
        User colleague = user(Role.CONTENT_ADMIN, department());
        News live = news("All", null, false, author.getId());
        News expired = news("All", TbilisiTime.now().minusDays(1), false, author.getId());
        News privateDraft = news("All", null, true, author.getId());

        perform(get("/api/news/" + live.getId()), operator).andExpect(status().isOk());
        perform(get("/api/news/" + expired.getId()), operator).andExpect(status().isNotFound());
        perform(get("/api/news/" + expired.getId()), colleague).andExpect(status().isOk());

        perform(get("/api/news/" + privateDraft.getId()), author).andExpect(status().isOk());
        perform(get("/api/news/" + privateDraft.getId()), colleague).andExpect(status().isNotFound());
        perform(get("/api/news/" + privateDraft.getId()), admin()).andExpect(status().isNotFound());
        perform(get("/api/news/" + privateDraft.getId()), operator).andExpect(status().isNotFound());
    }

    /**
     * Matrix, Video rows: a video's audience is its department, a group of it
     * (prefix) or All; a non-admin's view of a video outside it, or of an
     * archived one, is an opaque 404.
     */
    @Test
    void aVideoViewFollowsTheVideosAudience() throws Exception {
        String department = department();
        User groupMember = user(Role.OPERATOR, department + GROUP + "02");
        User outsider = user(Role.OPERATOR, department());
        VideoInstruction video = video(department, false);
        VideoInstruction archived = video(department, true);

        perform(post("/api/videos/" + video.getId() + "/view"), groupMember).andExpect(status().is2xxSuccessful());
        perform(post("/api/videos/" + video.getId() + "/view"), outsider).andExpect(status().isNotFound());
        perform(post("/api/videos/" + archived.getId() + "/view"), groupMember).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Mandatory reading (PO-40)
    // ------------------------------------------------------------------

    /**
     * PO-40 §3: a scheduled article may be made mandatory in advance; its due
     * date must fall after the publication moment (otherwise 422); and the
     * obligation only switches on when the article is published -- not before.
     */
    @Test
    void aScheduledArticleCanBeMadeMandatoryAheadButBindsNobodyUntilPublished() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        OffsetDateTime publishAt = TbilisiTime.now().plusDays(2);
        Article scheduled = article("scheduled", publishAt, false, contentAdmin.getId(), department);
        int before = totalMandatory(operator);

        assign(contentAdmin, scheduled.getId(), department, publishAt.minusDays(1)).andExpect(status().is(422));
        assign(contentAdmin, scheduled.getId(), department, publishAt.plusDays(3))
                .andExpect(status().is2xxSuccessful());

        assertNull(myReading(operator, scheduled.getId()), "not listed before publication");
        assertEquals(before, totalMandatory(operator), "not counted before publication");
    }

    /** PO-40 §1: archived material cannot be assigned; the refusal says why (reason "archived"). */
    @Test
    void anArchivedArticleCannotBeMadeMandatory() throws Exception {
        String department = department();
        user(Role.OPERATOR, department);
        Article archived = article("archived", TbilisiTime.now().minusDays(3), false, null, department);

        JsonNode refusal = json(assign(admin(), archived.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().isConflict()));
        assertEquals("archived", refusal.path("reason").asText());
        assertTrue(requiredReadingRepository.findByItemTypeAndItemId("article", archived.getId()).isEmpty());
    }

    /**
     * Matrix, POST required-readings (PO-34/D2): another author's private
     * draft is 404 to the assigner -- its title snapshot would otherwise leak.
     * PO-40 §1: the author's own private draft is refused with 409 because no
     * operator can open it.
     */
    @Test
    void aPrivateDraftIsNotFoundForAColleagueAndRefusedForItsAuthor() throws Exception {
        String department = department();
        user(Role.OPERATOR, department);
        User author = user(Role.CONTENT_ADMIN, department());
        User colleague = user(Role.CONTENT_ADMIN, department());
        Article draft = article("draft", null, true, author.getId(), department);
        OffsetDateTime due = TbilisiTime.now().plusDays(3);

        assign(colleague, draft.getId(), department, due).andExpect(status().isNotFound());
        assign(author, draft.getId(), department, due).andExpect(status().isConflict());
        assertTrue(requiredReadingRepository.findByItemTypeAndItemId("article", draft.getId()).isEmpty());
    }

    /**
     * PO-40 §2: archiving suspends the obligation (not listed, not counted)
     * and re-publishing restores it with the same due date and the earlier
     * confirmation intact.
     */
    @Test
    void archivingSuspendsAndRepublishingRestoresTheSameObligation() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        OffsetDateTime due = TbilisiTime.now().plusDays(4);
        long readingId = id(assign(contentAdmin, article.getId(), department, due)
                .andExpect(status().is2xxSuccessful()));
        perform(post("/api/compliance/mark-read/" + readingId), operator).andExpect(status().is2xxSuccessful());
        JsonNode confirmed = myReading(operator, article.getId());
        assertNotNull(confirmed);
        int countedBefore = totalMandatory(operator);

        bulkStatus(contentAdmin, article.getId(), "archived");
        assertNull(myReading(operator, article.getId()), "suspended: no longer listed");
        assertEquals(countedBefore - 1, totalMandatory(operator), "suspended: no longer counted");

        bulkStatus(contentAdmin, article.getId(), "published");
        JsonNode restored = myReading(operator, article.getId());
        assertNotNull(restored, "restored on re-publication");
        assertEquals(readingId, restored.path("reading").path("id").asLong());
        assertTrue(OffsetDateTime.parse(confirmed.path("reading").path("due_date").asText())
                .isEqual(OffsetDateTime.parse(restored.path("reading").path("due_date").asText())), "same due date");
        assertEquals(confirmed.path("status").asText(), restored.path("status").asText(), "confirmation kept");
        assertTrue(OffsetDateTime.parse(confirmed.path("read_at").asText())
                .isEqual(OffsetDateTime.parse(restored.path("read_at").asText())), "first read time kept");
    }

    /**
     * PO-40 §2 ("დეპარტამენტის მოხსნა") and §4 ("დეპარტამენტის მასობრივი
     * შეცვლაც ახალ დეპარტამენტს დავალებას უმატებს"): retargeting an article
     * from one department to another stops the obligation for the department
     * that lost the article and adds it for the department that gained it.
     */
    @Test
    void bulkRetargetMovesTheObligationWithTheAudience() throws Exception {
        String from = department();
        String to = department();
        User leaving = user(Role.OPERATOR, from);
        User arriving = user(Role.OPERATOR, to);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(from);
        assign(contentAdmin, article.getId(), from, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful());
        assertNotNull(myReading(leaving, article.getId()));

        send(post("/api/articles/bulk-retarget"), contentAdmin, objectMapper.writeValueAsString(Map.of(
                "ids", List.of(article.getId()), "target_departments", List.of(to))))
                .andExpect(status().is2xxSuccessful());

        assertNull(myReading(leaving, article.getId()), "the department that lost the article is released");
        assertNotNull(myReading(arriving, article.getId()), "the department that gained it is now bound");
    }

    /**
     * PO-40 §4: an article for several departments made mandatory in one save
     * binds everyone who can see it -- one assignment per department -- not
     * just the first department picked.
     */
    @Test
    void aMandatoryArticleForSeveralDepartmentsBindsEveryOne() throws Exception {
        String first = department();
        String second = department();
        User inFirst = user(Role.OPERATOR, first);
        User inSecond = user(Role.OPERATOR, second);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        String title = "BLIND multi " + nonce();
        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", title);
        article.put("content", "<p>ორ დეპარტამენტზე</p>");
        article.put("category_id", category().getId());
        article.put("target_departments", List.of(first, second));
        article.put("status", "published");
        article.put("is_draft", false);
        article.put("quiz_enabled", false);
        Map<String, Object> command = Map.of(
                "article", article,
                "mandatory", true,
                "due_date", TbilisiTime.now().plusDays(3).toString());

        mockMvc.perform(authed(post("/api/articles/command"), contentAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(command)))
                .andExpect(status().is2xxSuccessful());

        assertEquals(1, readingsTitled(inFirst, title), "the first department is bound");
        assertEquals(1, readingsTitled(inSecond, title), "and so is the second");
    }

    /**
     * Matrix, PUT required-readings (PO-40): a new target outside the
     * article's audience is refused with 409, while keeping the unchanged
     * target is allowed even while the material is temporarily hidden
     * (archived).
     */
    @Test
    void retargetingAReadingIsCheckedButKeepingItsTargetOverAnArchiveIsNot() throws Exception {
        String audience = department();
        String elsewhere = department();
        user(Role.OPERATOR, audience);
        user(Role.OPERATOR, elsewhere);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(audience);
        long readingId = id(assign(contentAdmin, article.getId(), audience, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful()));

        send(put("/api/compliance/required-readings/" + readingId), contentAdmin,
                readingJson(article.getId(), elsewhere, TbilisiTime.now().plusDays(5)))
                .andExpect(status().isConflict());

        bulkStatus(contentAdmin, article.getId(), "archived");
        send(put("/api/compliance/required-readings/" + readingId), contentAdmin,
                readingJson(article.getId(), audience, TbilisiTime.now().plusDays(6)))
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * PO-40, "რჩება": removing a mandatory assignment after somebody has
     * already confirmed it is still impossible -- the confirmation is evidence.
     */
    @Test
    void aConfirmedMandatoryReadingCannotBeRemoved() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        long readingId = id(assign(contentAdmin, article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful()));
        perform(post("/api/compliance/mark-read/" + readingId), operator).andExpect(status().is2xxSuccessful());

        // 409 is the refusal. Whether the row survives cannot be read back inside
        // this test's own transaction (the failed delete leaves the entity marked
        // removed in it); ComplianceControllerIntegrationTest checks that outside one.
        mockMvc.perform(authed(delete("/api/compliance/required-readings/" + readingId), contentAdmin))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Confirmations (PO-30) and quiz gating
    // ------------------------------------------------------------------

    /** PO-30 / matrix mark-read row: repeating "წავიკითხე" never moves the first read_at. */
    @Test
    void markingAReadingReadTwiceKeepsTheFirstTime() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article article = published(department);
        long readingId = id(assign(admin(), article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful()));

        perform(post("/api/compliance/mark-read/" + readingId), operator).andExpect(status().is2xxSuccessful());
        String first = myReading(operator, article.getId()).path("read_at").asText();
        Thread.sleep(1100);
        perform(post("/api/compliance/mark-read/" + readingId), operator).andExpect(status().is2xxSuccessful());
        String second = myReading(operator, article.getId()).path("read_at").asText();

        assertFalse(first.isBlank());
        assertTrue(OffsetDateTime.parse(first).isEqual(OffsetDateTime.parse(second)));
    }

    /**
     * PO-30: a repeated read-receipt on the same version keeps its first time;
     * a new version needs a new receipt; and an already completed mandatory
     * assignment keeps its first completion time when the article is edited.
     */
    @Test
    void aReceiptIsPerVersionWhileACompletedObligationKeepsItsFirstTime() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        assign(contentAdmin, article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful());

        JsonNode first = json(perform(post("/api/articles/" + article.getId() + "/read-receipt"), operator)
                .andExpect(status().is2xxSuccessful()));
        Thread.sleep(1100);
        JsonNode again = json(perform(post("/api/articles/" + article.getId() + "/read-receipt"), operator)
                .andExpect(status().is2xxSuccessful()));
        assertTrue(OffsetDateTime.parse(first.path("read_at").asText())
                .isEqual(OffsetDateTime.parse(again.path("read_at").asText())), "same version, same first time");
        assertEquals(first.path("article_version").asInt(), again.path("article_version").asInt());
        JsonNode completed = myReading(operator, article.getId());
        assertEquals("read", completed.path("status").asText(), "the receipt completes the covering obligation");

        mockMvc.perform(authed(put("/api/articles/" + article.getId()), contentAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleJson(article.getTitle(), "<p>ახალი ტექსტი " + nonce() + "</p>",
                                article.getCategoryId(), List.of(department), "published", false)))
                .andExpect(status().is2xxSuccessful());

        JsonNode mine = json(perform(get("/api/articles/" + article.getId() + "/read-receipt/me"), operator)
                .andExpect(status().isOk()));
        int currentVersion = mine.path("current_version").asInt();
        assertTrue(currentVersion > first.path("article_version").asInt(), "an edit makes a new version");
        assertFalse(mine.path("has_read").asBoolean() && mine.path("article_version").asInt() == currentVersion,
                "the old receipt does not stand for the new version");

        JsonNode renewed = json(perform(post("/api/articles/" + article.getId() + "/read-receipt"), operator)
                .andExpect(status().is2xxSuccessful()));
        assertEquals(currentVersion, renewed.path("article_version").asInt(), "the new version gets a new receipt");

        JsonNode stillCompleted = myReading(operator, article.getId());
        assertEquals("read", stillCompleted.path("status").asText());
        assertTrue(OffsetDateTime.parse(completed.path("read_at").asText())
                .isEqual(OffsetDateTime.parse(stillCompleted.path("read_at").asText())),
                "a completed obligation keeps its first time");
    }

    /**
     * Matrix, read-receipt row (requireQuizPassed): on a quiz article an
     * operator's confirmation is refused until the quiz is passed; a failed
     * attempt does not open it.
     */
    @Test
    void aQuizArticleCannotBeConfirmedBeforeTheQuizIsPassed() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        article.setQuizEnabled(true);
        articleRepository.saveAndFlush(article);
        String rightText = "სწორი";
        String quiz = "{\"questions\":[{\"id\":null,\"question_text\":\"რა არის სწორი?\",\"position\":0,"
                + "\"answers\":[{\"id\":null,\"answer_text\":\"" + rightText
                + "\",\"is_correct\":true,\"position\":0},{\"id\":null,\"answer_text\":\"არასწორი"
                + "\",\"is_correct\":false,\"position\":1}]}]}";
        send(put("/api/articles/" + article.getId() + "/quiz/admin"), contentAdmin, quiz)
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/read-receipt"), operator))
                .andExpect(status().is4xxClientError());

        JsonNode publicQuiz = json(perform(get("/api/articles/" + article.getId() + "/quiz"), operator)
                .andExpect(status().isOk()));
        JsonNode question = publicQuiz.path("questions").get(0);
        long questionId = question.path("id").asLong();
        long rightId = 0;
        long wrongId = 0;
        for (JsonNode answer : question.path("answers")) {
            if (answer.path("answer_text").asText().equals(rightText)) {
                rightId = answer.path("id").asLong();
            } else {
                wrongId = answer.path("id").asLong();
            }
        }
        assertFalse(question.path("answers").get(0).has("is_correct"), "the public quiz does not reveal answers");

        JsonNode failed = json(send(post("/api/articles/" + article.getId() + "/quiz/attempt"), operator,
                "{\"answers\":{\"" + questionId + "\":" + wrongId + "}}")
                .andExpect(status().is2xxSuccessful()));
        assertFalse(failed.path("passed").asBoolean());
        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/read-receipt"), operator))
                .andExpect(status().is4xxClientError());
        assertFalse(json(perform(get("/api/articles/" + article.getId() + "/read-receipt/me"), operator)
                .andExpect(status().isOk())).path("has_read").asBoolean(), "a refused confirmation records nothing");

        JsonNode passed = json(send(post("/api/articles/" + article.getId() + "/quiz/attempt"), operator,
                "{\"answers\":{\"" + questionId + "\":" + rightId + "}}")
                .andExpect(status().is2xxSuccessful()));
        assertTrue(passed.path("passed").asBoolean());
        mockMvc.perform(authed(post("/api/articles/" + article.getId() + "/read-receipt"), operator))
                .andExpect(status().is2xxSuccessful());
    }

    // ------------------------------------------------------------------
    // Who sees names, who sees counts (PO-01 / D-2 / PO-17)
    // ------------------------------------------------------------------

    /**
     * PO-01 / D-2: content.manage alone gives the read counts but no names;
     * SYSTEM_ADMIN sees named rows org-wide; email never appears in either.
     */
    @Test
    void readEvidenceIsCountsForContentAdminsAndNamesWithoutEmailForSystemAdmins() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        perform(post("/api/articles/" + article.getId() + "/read-receipt"), operator)
                .andExpect(status().is2xxSuccessful());

        String counts = body(perform(get("/api/articles/" + article.getId() + "/read-receipts"), contentAdmin)
                .andExpect(status().isOk()));
        JsonNode aggregate = objectMapper.readTree(counts);
        assertEquals(1, aggregate.path("read_count").asInt(), "the author sees how many read it");
        assertFalse(counts.contains(operator.getName()), "but not who");
        assertTrue(aggregate.path("receipts").isMissingNode() || aggregate.path("receipts").isNull()
                || aggregate.path("receipts").isEmpty(), "no named rows for content.manage alone");

        String named = body(perform(get("/api/articles/" + article.getId() + "/read-receipts"), admin())
                .andExpect(status().isOk()));
        assertTrue(named.contains(operator.getName()), "SYSTEM_ADMIN sees the name");
        assertFalse(named.contains(operator.getEmail()), "never the email");
        assertFalse(named.contains("operator_email"));
    }

    /**
     * PO-01 / PO-17 / D-2: a group leader (active PRIMARY assignment) sees
     * named read evidence only for their own group, not for a sibling group of
     * the same department.
     */
    @Test
    void aGroupLeaderSeesNamesOnlyInTheirOwnGroup() throws Exception {
        LeaderFixture org = leaderFixture();
        Article article = published(org.department);
        perform(post("/api/articles/" + article.getId() + "/read-receipt"), org.ownMember)
                .andExpect(status().is2xxSuccessful());
        perform(post("/api/articles/" + article.getId() + "/read-receipt"), org.foreignMember)
                .andExpect(status().is2xxSuccessful());

        String evidence = body(perform(get("/api/articles/" + article.getId() + "/read-receipts"), org.leader)
                .andExpect(status().isOk()));
        assertTrue(evidence.contains(org.ownMember.getName()), "own group member is named");
        assertFalse(evidence.contains(org.foreignMember.getName()), "the sibling group's member is not");
        assertFalse(evidence.contains(org.ownMember.getEmail()), "and no email");
    }

    /**
     * PO-16 / matrix Reminders row: a group leader may send the fixed manual
     * reminder only to an active member of their own group.
     */
    @Test
    void aGroupLeaderRemindsOnlyTheirOwnGroup() throws Exception {
        LeaderFixture org = leaderFixture();

        // A reminder is about something owed: the portal refuses one to a person
        // who owes nothing (409), which the docs did not say -- found on the first run.
        Article article = published(org.department);
        assign(admin(), article.getId(), org.department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(authed(post("/api/reminders/users/" + org.foreignMember.getId() + "/send"), org.leader))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(authed(post("/api/reminders/users/" + org.ownMember.getId() + "/send"), org.leader))
                .andExpect(status().is2xxSuccessful());
    }

    /**
     * PO-40 §2 and the matrix "addressees" row: before saving, the editor is
     * told who is affected -- counts by department for compliance.assign, names
     * only for SYSTEM_ADMIN (or a leader within their group).
     */
    @Test
    void addresseesAreCountsForEditorsAndNamesForSystemAdmins() throws Exception {
        String department = department();
        User first = user(Role.OPERATOR, department);
        User second = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article article = published(department);
        assign(contentAdmin, article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful());
        String path = "/api/compliance/required-readings/by-item/article/" + article.getId() + "/addressees";

        String counts = body(perform(get(path), contentAdmin).andExpect(status().isOk()));
        JsonNode aggregate = objectMapper.readTree(counts);
        assertEquals(2, aggregate.path("in_force_total").asInt());
        assertFalse(counts.contains(first.getName()) || counts.contains(second.getName()), "no names for an editor");

        String named = body(perform(get(path), admin()).andExpect(status().isOk()));
        assertTrue(named.contains(first.getName()) && named.contains(second.getName()), "names for SYSTEM_ADMIN");
        assertFalse(named.contains(first.getEmail()));
    }

    // ------------------------------------------------------------------
    // Capabilities: stats.view, SYSTEM_ADMIN-only surfaces, delegation
    // ------------------------------------------------------------------

    /**
     * PO-06 / D-8: company statistics need stats.view, which content.manage
     * does not imply and which SYSTEM_ADMIN grants independently.
     */
    @Test
    void companyStatisticsNeedStatsViewNotContentManage() throws Exception {
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        User manager = user(Role.MANAGER, department());

        perform(get("/api/statistics/kpi"), contentAdmin).andExpect(status().isForbidden());
        perform(get("/api/statistics/compliance"), contentAdmin).andExpect(status().isForbidden());
        perform(get("/api/statistics/kpi"), manager).andExpect(status().isForbidden());
        perform(get("/api/statistics/kpi"), admin()).andExpect(status().isOk());

        setOverride(contentAdmin, "stats.view", "ALLOW");
        perform(get("/api/statistics/kpi"), contentAdmin).andExpect(status().isOk());
    }

    /**
     * PO-14 / PO-15 / matrix AuditLog, Export and Stats rows: the view log,
     * per-person progress, raw audit and the full data exports are
     * SYSTEM_ADMIN-only -- reports.export (a manager's default) and
     * content.manage do not open them.
     */
    @Test
    void viewLogsAuditAndFullExportsAreSystemAdminOnly() throws Exception {
        String department = department();
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        User manager = user(Role.MANAGER, department());
        Article article = published(department);

        for (User caller : List.of(contentAdmin, manager)) {
            perform(get("/api/articles/" + article.getId() + "/views"), caller).andExpect(status().isForbidden());
            perform(get("/api/statistics/user-progress"), caller).andExpect(status().isForbidden());
            perform(get("/api/audit-logs"), caller).andExpect(status().isForbidden());
            mockMvc.perform(authed(post("/api/admin/exports/read-evidence"), caller)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        perform(get("/api/articles/" + article.getId() + "/views"), admin()).andExpect(status().isOk());
        perform(get("/api/audit-logs"), admin()).andExpect(status().isOk());
    }

    /**
     * PO-33 / D-3 (2026-09-24): an ADMIN_* export job is its creator's only
     * while they still hold SYSTEM_ADMIN -- after losing the role the status is
     * 404 and the download 410, even with reports.export.
     */
    @Test
    void anAdminExportIsLostWithTheSystemAdminRole() throws Exception {
        User admin = admin();
        JsonNode job = json(mockMvc.perform(authed(post("/api/admin/exports/read-evidence"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is2xxSuccessful()));
        String jobId = job.path("job_id").asText();
        assertFalse(jobId.isBlank());
        perform(get("/api/export/status/" + jobId), admin).andExpect(status().isOk());

        admin.setRole(Role.MANAGER);
        admin.setPermissions(permissionsOf(Role.MANAGER));
        userRepository.saveAndFlush(admin);

        // PO-33 names no status code; the portal answers 403 for both.
        perform(get("/api/export/status/" + jobId), admin).andExpect(status().is4xxClientError());
        perform(get("/api/export/download/" + jobId), admin).andExpect(status().is4xxClientError());
    }

    /**
     * D-5 (2026-10-01 addendum): reading follows the effective content.manage,
     * not the role -- a manager granted it reads an unpublished article of
     * another department; a content administrator denied it reads like any
     * other member of their own department.
     */
    @Test
    void contentReachFollowsTheEffectiveContentManage() throws Exception {
        String home = department();
        String elsewhere = department();
        User manager = user(Role.MANAGER, home);
        User contentAdmin = user(Role.CONTENT_ADMIN, home);
        Article unpublished = article("draft", null, false, null, elsewhere);
        Article otherDepartment = published(elsewhere);
        Article ownDepartment = published(home);

        perform(get("/api/articles/" + unpublished.getId()), manager).andExpect(status().isNotFound());
        setOverride(manager, "content.manage", "ALLOW");
        perform(get("/api/articles/" + unpublished.getId()), manager).andExpect(status().isOk());

        perform(get("/api/articles/" + unpublished.getId()), contentAdmin).andExpect(status().isOk());
        setOverride(contentAdmin, "content.manage", "DENY");
        perform(get("/api/articles/" + unpublished.getId()), contentAdmin).andExpect(status().isNotFound());
        perform(get("/api/articles/" + otherDepartment.getId()), contentAdmin).andExpect(status().isNotFound());
        perform(get("/api/articles/" + ownDepartment.getId()), contentAdmin).andExpect(status().isOk());
    }

    /**
     * D-9: version history is a reader feature (versions list open to an
     * operator who sees the article), while the editor history surfaces stay
     * behind content.manage.
     */
    @Test
    void versionHistoryIsForReadersAndEditorHistoryIsNot() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article article = published(department);

        perform(get("/api/articles/" + article.getId() + "/versions"), operator).andExpect(status().isOk());
        perform(get("/api/articles/" + article.getId() + "/history"), operator).andExpect(status().isForbidden());
        perform(get("/api/articles/" + article.getId() + "/history-summary"), operator)
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Deactivation (PO-24, AGENTS: authorization re-read on every request)
    // ------------------------------------------------------------------

    /**
     * PO-24 / PO-08 and the JwtAuthenticationFilter note in AGENTS.md: a
     * deactivated account's existing token stops working at once, and a
     * deactivated person no longer counts among the people a reading binds.
     */
    @Test
    void deactivationEndsTheSessionAndTheObligationCount() throws Exception {
        String department = department();
        User staying = user(Role.OPERATOR, department);
        User leaving = user(Role.OPERATOR, department);
        User admin = admin();
        Article article = published(department);
        assign(admin, article.getId(), department, TbilisiTime.now().plusDays(3))
                .andExpect(status().is2xxSuccessful());
        String path = "/api/compliance/required-readings/by-item/article/" + article.getId() + "/addressees";
        assertEquals(2, json(perform(get(path), admin).andExpect(status().isOk())).path("in_force_total").asInt());
        perform(get("/api/users/me"), leaving).andExpect(status().isOk());

        mockMvc.perform(authed(put("/api/users/" + leaving.getId() + "/status"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"is_active\":false}"))
                .andExpect(status().is2xxSuccessful());

        perform(get("/api/users/me"), leaving).andExpect(status().isUnauthorized());
        perform(get("/api/users/me"), staying).andExpect(status().isOk());
        assertEquals(1, json(perform(get(path), admin).andExpect(status().isOk())).path("in_force_total").asInt(),
                "only the active person is still bound");
    }

    /**
     * PO-24 (implemented 2026-09-19) / matrix bulk-deactivate row: the
     * caller's own account is taken out of the set, so one active admin
     * always remains.
     */
    @Test
    void bulkDeactivationNeverDeactivatesTheCaller() throws Exception {
        User admin = admin();
        User operator = user(Role.OPERATOR, department());

        JsonNode result = json(mockMvc.perform(authed(post("/api/admin/users/bulk-deactivate"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_ids\":[" + admin.getId() + "," + operator.getId() + "]}"))
                .andExpect(status().is2xxSuccessful()));

        assertEquals(1, result.path("deactivated").asInt());
        perform(get("/api/users/me"), admin).andExpect(status().isOk());
        perform(get("/api/users/me"), operator).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Broadcasts, favorites, notifications, deletion, uploads
    // ------------------------------------------------------------------

    /**
     * PO-05 / matrix Broadcast rows: publishers are content.manage, an active
     * group leader or SYSTEM_ADMIN -- not an operator or a manager without a
     * group; the audience is always the whole company (no targeting), and
     * every authenticated employee sees an active one.
     */
    @Test
    void broadcastsArePublishedByTheRightPeopleToTheWholeCompany() throws Exception {
        User operator = user(Role.OPERATOR, department());
        User managerWithoutGroup = user(Role.MANAGER, department());
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        String message = "BLIND broadcast " + nonce();
        String endsAt = TbilisiTime.now().plusHours(4).toString();
        String valid = "{\"message\":\"" + message + "\",\"priority\":\"NORMAL\",\"ends_at\":\"" + endsAt + "\"}";
        String targeted = "{\"message\":\"" + message + "\",\"priority\":\"NORMAL\",\"ends_at\":\"" + endsAt
                + "\",\"target_department\":\"" + department() + "\"}";

        mockMvc.perform(authed(post("/api/broadcasts"), operator)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/broadcasts"), managerWithoutGroup)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/broadcasts"), contentAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(targeted))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/broadcasts"), contentAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().is2xxSuccessful());

        assertTrue(body(perform(get("/api/broadcasts"), operator).andExpect(status().isOk())).contains(message),
                "an operator in any department sees it");
    }

    /**
     * PO-38 / matrix Favorite rows: a bookmark names only what the caller may
     * open; another department's article shows as "მასალა #ID".
     */
    @Test
    void aBookmarkNamesOnlyWhatTheCallerMayOpen() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        Article own = published(department);
        Article foreign = published(department());

        favorite(operator, own.getId());
        favorite(operator, foreign.getId());
        JsonNode favorites = json(perform(get("/api/favorites"), operator).andExpect(status().isOk()));

        Map<Long, String> titles = new LinkedHashMap<>();
        for (JsonNode favorite : favorites) {
            if ("article".equals(favorite.path("item_type").asText())) {
                titles.put(favorite.path("item_id").asLong(), favorite.path("item_title").asText());
            }
        }
        assertEquals(own.getTitle(), titles.get(own.getId()));
        assertEquals("მასალა #" + foreign.getId(), titles.get(foreign.getId()));
    }

    /**
     * Matrix, notifications summary row (2026-09-29): recent_news follows
     * news visibility -- an operator never sees the title of a draft or an
     * expired news item there.
     */
    @Test
    void theBellNamesOnlyNewsTheOperatorMayOpen() throws Exception {
        User operator = user(Role.OPERATOR, department());
        User author = user(Role.CONTENT_ADMIN, department());
        News live = news("All", null, false, author.getId());
        News draft = news("All", null, true, author.getId());
        News expired = news("All", TbilisiTime.now().minusHours(1), false, author.getId());

        String summary = body(perform(get("/api/notifications/summary"), operator).andExpect(status().isOk()));
        assertTrue(summary.contains(live.getTitle()), "control: the live item is there");
        assertFalse(summary.contains(draft.getTitle()), "no draft title");
        assertFalse(summary.contains(expired.getTitle()), "no expired title");
    }

    /**
     * PO-18 / matrix Category and Article DELETE rows (R5): a category in use
     * cannot be deleted (409), and only an already archived article can go to
     * the trash -- deleting a live one is refused and it stays readable.
     */
    @Test
    void deletionNeverSkipsTheArchiveStepOrOrphansContent() throws Exception {
        String department = department();
        User operator = user(Role.OPERATOR, department);
        User contentAdmin = user(Role.CONTENT_ADMIN, department());
        Article live = published(department);

        mockMvc.perform(authed(delete("/api/categories/" + live.getCategoryId()), contentAdmin))
                .andExpect(status().isConflict());
        mockMvc.perform(authed(delete("/api/articles/" + live.getId()), contentAdmin))
                .andExpect(status().is4xxClientError());
        perform(get("/api/articles/" + live.getId()), operator).andExpect(status().isOk());
    }

    /** PO-02 / D-4: an attachment URL does not work without login. */
    @Test
    void anAttachmentLinkNeedsALogin() throws Exception {
        mockMvc.perform(get("/uploads/" + "a".repeat(32) + ".png")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Fixture mechanics
    // ------------------------------------------------------------------

    /** One department with two groups; the leader leads group 01 only. */
    private LeaderFixture leaderFixture() {
        String department = department();
        Department org = new Department();
        org.setStableKey("blind-" + System.nanoTime());
        org.setName(department);
        org.setActive(true);
        org.setSortOrder(900);
        org = departmentRepository.saveAndFlush(org);
        Team own = team(org, "ჯგუფი 01");
        Team foreign = team(org, "ჯგუფი 02");

        User leader = user(Role.MANAGER, department + GROUP + "01");
        User ownMember = user(Role.OPERATOR, department + GROUP + "01");
        User foreignMember = user(Role.OPERATOR, department + GROUP + "02");
        for (User member : List.of(leader, ownMember)) {
            member.setTeamId(own.getId());
            userRepository.saveAndFlush(member);
        }
        foreignMember.setTeamId(foreign.getId());
        userRepository.saveAndFlush(foreignMember);

        LeadershipAssignment assignment = new LeadershipAssignment();
        assignment.setUserId(leader.getId());
        assignment.setTeamId(own.getId());
        assignment.setAssignmentType(AssignmentType.PRIMARY);
        assignment.setActive(true);
        assignment.setStartedAt(TbilisiTime.now().minusDays(1));
        assignment.setSource(LeadershipAssignment.Source.MANUAL);
        leadershipAssignmentRepository.saveAndFlush(assignment);
        return new LeaderFixture(department, leader, ownMember, foreignMember);
    }

    private record LeaderFixture(String department, User leader, User ownMember, User foreignMember) {
    }

    private Team team(Department department, String name) {
        Team team = new Team();
        team.setName(name);
        team.setDepartmentId(department.getId());
        team.setStableKey("blind-t-" + System.nanoTime());
        team.setActive(true);
        team.setCreatedAt(TbilisiTime.now());
        return teamRepository.saveAndFlush(team);
    }

    private Article published(String... audience) {
        return article("published", TbilisiTime.now().minusDays(1), false, null, audience);
    }

    private Article article(String status, OffsetDateTime publishedAt, boolean privateDraft, Long authorId,
                            String... audience) {
        Article article = new Article();
        article.setTitle("BLIND article " + nonce());
        article.setContent("<p>ბლაინდ ტესტის შინაარსი</p>");
        article.setCategoryId(category().getId());
        article.setVersion(1);
        article.setStatus(status);
        article.setPublishedAt(publishedAt);
        article.setDraft(privateDraft);
        article.setAuthorId(authorId);
        article.setQuizEnabled(false);
        article.setTargetDepartment(audience[0]);
        Article saved = articleRepository.saveAndFlush(article);
        for (String department : audience) {
            ArticleTargetDepartment row = new ArticleTargetDepartment();
            row.setArticleId(saved.getId());
            row.setDepartment(department);
            articleTargetDepartmentRepository.saveAndFlush(row);
        }
        return saved;
    }

    private News news(String audience, OffsetDateTime expiresAt, boolean privateDraft, Long authorId) {
        News news = new News();
        news.setTitle("BLIND news " + nonce());
        news.setContent("<p>სიახლე</p>");
        news.setTargetDepartment(audience);
        news.setCreatedAt(TbilisiTime.now());
        news.setExpiresAt(expiresAt);
        news.setDraft(privateDraft);
        news.setAuthorId(authorId);
        news.setVersion(1);
        return newsRepository.saveAndFlush(news);
    }

    private VideoInstruction video(String audience, boolean archived) {
        VideoInstruction video = new VideoInstruction();
        video.setTitle("BLIND video " + nonce());
        video.setVideoUrl("https://example.com/blind.mp4");
        video.setTargetDepartment(audience);
        video.setCreatedAt(TbilisiTime.now());
        video.setArchived(archived);
        return videoRepository.saveAndFlush(video);
    }

    private Category category() {
        Category category = new Category();
        category.setName("blind-category-" + System.nanoTime());
        category.setActive(true);
        return categoryRepository.saveAndFlush(category);
    }

    private String articleJson(String title, String content, Long categoryId, List<String> departments,
                               String status, boolean privateDraft) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("content", content);
        body.put("category_id", categoryId);
        body.put("target_departments", departments);
        body.put("status", status);
        if ("published".equals(status)) {
            body.put("published_at", TbilisiTime.now().minusDays(1).toString());
        }
        body.put("is_draft", privateDraft);
        body.put("quiz_enabled", false);
        return objectMapper.writeValueAsString(body);
    }

    private ResultActions assign(User assigner, Long articleId, String target, OffsetDateTime due) throws Exception {
        return mockMvc.perform(authed(post("/api/compliance/required-readings"), assigner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(readingJson(articleId, target, due)));
    }

    private static String readingJson(Long articleId, String target, OffsetDateTime due) {
        return "{\"item_type\":\"article\",\"item_id\":" + articleId + ",\"target_department\":\"" + target
                + "\",\"due_date\":\"" + due + "\",\"priority\":\"high\"}";
    }

    private void bulkStatus(User caller, Long articleId, String status) throws Exception {
        mockMvc.perform(authed(post("/api/articles/bulk-status"), caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + articleId + "],\"status\":\"" + status + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    private void favorite(User caller, Long articleId) throws Exception {
        mockMvc.perform(authed(post("/api/favorites"), caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item_type\":\"article\",\"item_id\":" + articleId + "}"))
                .andExpect(status().is2xxSuccessful());
    }

    /** Sets one permission override through the admin API (PUT /api/users/{id}/permissions). */
    private void setOverride(User target, String permission, String state) throws Exception {
        long lockVersion = userRepository.findById(target.getId()).orElseThrow().getLockVersion();
        mockMvc.perform(authed(put("/api/users/" + target.getId() + "/permissions"), admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lock_version\":" + lockVersion + ",\"overrides\":[{\"permission\":\""
                                + permission + "\",\"state\":\"" + state + "\"}]}"))
                .andExpect(status().is2xxSuccessful());
    }

    private JsonNode myReading(User operator, long articleId) throws Exception {
        JsonNode list = json(perform(get("/api/compliance/my-readings"), operator).andExpect(status().isOk()));
        for (JsonNode entry : list) {
            JsonNode reading = entry.path("reading");
            if ("article".equals(reading.path("item_type").asText()) && reading.path("item_id").asLong() == articleId) {
                return entry;
            }
        }
        return null;
    }

    private long readingsTitled(User operator, String title) throws Exception {
        JsonNode list = json(perform(get("/api/compliance/my-readings"), operator).andExpect(status().isOk()));
        return StreamSupport.stream(list.spliterator(), false)
                .filter(entry -> title.equals(entry.path("item_title").asText()))
                .count();
    }

    private int totalMandatory(User operator) throws Exception {
        return json(perform(get("/api/compliance/my-progress"), operator).andExpect(status().isOk()))
                .path("total_mandatory").asInt();
    }

    private long id(ResultActions result) throws Exception {
        return json(result).path("id").asLong();
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private ResultActions perform(MockHttpServletRequestBuilder builder, User user) throws Exception {
        return mockMvc.perform(authed(builder, user));
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, User user, String json) throws Exception {
        return mockMvc.perform(authed(builder, user).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String department() {
        return "BLIND-" + System.nanoTime();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime());
    }

    private User admin() {
        return user(Role.SYSTEM_ADMIN, "All");
    }

    private User user(Role role, String department) {
        String nonce = nonce();
        User user = new User();
        user.setEmail("blind-" + role.value() + "-" + nonce + "@magti.ge");
        user.setName("Blind " + role.value() + " " + nonce);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(permissionsOf(role));
        return userRepository.saveAndFlush(user);
    }

    private static LinkedHashSet<String> permissionsOf(Role role) {
        return Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, User user) {
        return builder.header("Authorization", "Bearer "
                + jwtService.createAccessToken(Map.of("sub", user.getEmail(), "role", user.getRole().value())));
    }
}
