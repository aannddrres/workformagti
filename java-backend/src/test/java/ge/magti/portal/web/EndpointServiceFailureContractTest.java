package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.announcement.BroadcastService;
import ge.magti.portal.announcement.BroadcastAuthorizationService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.content.LegalHoldAuthority;
import ge.magti.portal.content.ArticleHtmlSanitizer;
import ge.magti.portal.compliance.ReadingAcknowledgementService;
import ge.magti.portal.compliance.RequiredReadingMutationService;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.export.ExportTooLargeException;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleHistoryRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.DepartmentRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.NewsHistoryRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.QuizQuestionRepository;
import ge.magti.portal.repository.QuizAnswerRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.TagRepository;
import ge.magti.portal.repository.TeamRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.search.SearchQueryService;
import ge.magti.portal.search.GlobalSearchCache;
import ge.magti.portal.article.ArticleQueryService;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.news.NewsQueryService;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.org.OrgBackfillPlan;
import ge.magti.portal.org.OrgBackfillService;
import ge.magti.portal.org.LeadershipAssignmentService;
import ge.magti.portal.quiz.QuizGateChecker;
import ge.magti.portal.reminder.ReminderService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.security.AccessDiffService;
import ge.magti.portal.security.PolicyShadowRecorder;
import ge.magti.portal.security.PortalSessionService;
import ge.magti.portal.security.ClientIpResolver;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.compliance.ComplianceProgressQueryService;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.quiz.KnowledgeScoreService;
import org.springframework.security.crypto.password.PasswordEncoder;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.video.TagSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Injects a service failure through each HTTP route without needing Oracle. */
class EndpointServiceFailureContractTest {

    @Test
    void readingsCsvQueryFailureHasSafeResponseAndNoExportAudit() throws Exception {
        User admin = user(1L, Role.SYSTEM_ADMIN, "All");
        admin.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        ExportQueryService query = mock(ExportQueryService.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        when(query.eligibleReadingRows(admin, null, null)).thenThrow(new IllegalStateException("private-query-marker"));
        ExportController controller = new ExportController(query, mock(ExportJobRepository.class),
                mock(ExportJobWorker.class), audit, new PermissionChecker());

        String body = mvc(controller, admin).perform(get("/api/export/readings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-query-marker"));
        verify(audit, never()).saveAndFlush(any());
    }

    @Test
    void managerDashboardServiceFailureHasSafeResponseAndScopedQuery() throws Exception {
        User manager = user(1L, Role.MANAGER, "ტექნიკური — ჯგუფი 03");
        User own = user(2L, Role.OPERATOR, "ტექნიკური — ჯგუფი 03");
        User foreign = user(3L, Role.OPERATOR, "ოფისი — ჯგუფი 01");
        UserRepository users = mock(UserRepository.class);
        ComplianceQueryService compliance = mock(ComplianceQueryService.class);
        when(users.findByActiveTrue()).thenReturn(List.of(own, foreign));
        when(compliance.computeCompliance(eq(List.of(2L)), isNull()))
                .thenThrow(new IllegalStateException("private-service-marker"));
        StatsController controller = new StatsController(compliance, users,
                mock(SearchLogRepository.class), mock(RequiredReadingRepository.class),
                mock(ReadStatusRepository.class), mock(ArticleRepository.class),
                mock(VideoInstructionRepository.class), mock(AuditLogRepository.class),
                mock(ArticleViewLogRepository.class));

        String body = mvc(controller, manager).perform(get("/api/manager/department-stats"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-service-marker"));
        verify(compliance).computeCompliance(eq(List.of(2L)), isNull());
    }

    @Test
    void leadershipOptionsFailureDoesNotExposeDirectoryException() throws Exception {
        User manager = user(15L, Role.MANAGER, "ტექნიკური");
        ScopeResolver scope = mock(ScopeResolver.class);
        when(scope.leadershipOptions(manager))
                .thenThrow(new IllegalStateException("private-assignment-marker"));
        StatsController controller = new StatsController(mock(ComplianceQueryService.class),
                mock(UserRepository.class), mock(SearchLogRepository.class),
                mock(RequiredReadingRepository.class), mock(ReadStatusRepository.class),
                mock(ArticleRepository.class), mock(VideoInstructionRepository.class),
                mock(AuditLogRepository.class), mock(ArticleViewLogRepository.class),
                scope, new PermissionChecker(), null);

        String body = mvc(controller, manager).perform(get("/api/manager/leadership-options"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-assignment-marker"));
        verify(scope).leadershipOptions(manager);
    }

    @Test
    void statisticsQueryFailuresReturnCorrelationWithoutLeakingInternalDetails() throws Exception {
        User admin = user(16L, Role.SYSTEM_ADMIN, "All");
        SearchLogRepository searches = mock(SearchLogRepository.class);
        ComplianceQueryService compliance = mock(ComplianceQueryService.class);
        UserRepository users = mock(UserRepository.class);
        when(searches.popularSearchTerms(any())).thenThrow(new IllegalStateException("private-stats-marker"));
        when(searches.failedSearchTerms(any())).thenThrow(new IllegalStateException("private-stats-marker"));
        when(compliance.computeCompliance()).thenThrow(new IllegalStateException("private-stats-marker"));
        when(users.countByActiveTrue()).thenThrow(new IllegalStateException("private-stats-marker"));
        when(users.findByActiveTrue()).thenThrow(new IllegalStateException("private-stats-marker"));
        StatsController controller = new StatsController(compliance, users, searches,
                mock(RequiredReadingRepository.class), mock(ReadStatusRepository.class),
                mock(ArticleRepository.class), mock(VideoInstructionRepository.class),
                mock(AuditLogRepository.class), mock(ArticleViewLogRepository.class));
        MockMvc mvc = mvc(controller, admin);

        for (String route : List.of("/api/statistics/popular-searches",
                "/api/statistics/failed-searches", "/api/statistics/compliance",
                "/api/statistics/kpi", "/api/admin/stats/team/9",
                "/api/manager/team-stats", "/api/admin/critical-operators",
                "/api/admin/departments/office/groups/team/users")) {
            String body = mvc.perform(get(route))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-stats-marker"), route);
        }
    }

    @Test
    void accessDiffQueryFailureReturnsSafeResponse() throws Exception {
        AccessDiffService service = mock(AccessDiffService.class);
        when(service.report()).thenThrow(new IllegalStateException("private-access-diff-marker"));
        String body = mvc(new AccessDiffController(service), user(17L, Role.SYSTEM_ADMIN, "All"))
                .perform(get("/api/admin/access-diff"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-access-diff-marker"));
    }

    @Test
    void articleReadRouteFailuresDoNotExposeRepositoryDetails() throws Exception {
        ArticleRepository articles = mock(ArticleRepository.class);
        ArticleHistoryRepository history = mock(ArticleHistoryRepository.class);
        ArticleViewLogRepository views = mock(ArticleViewLogRepository.class);
        when(articles.findStaleReferences(eq("published"), any(), any(), any()))
                .thenThrow(new IllegalStateException("private-article-marker"));
        when(history.findSummaryByArticleIdOrderByUpdatedAtDesc(eq(99L), any()))
                .thenThrow(new IllegalStateException("private-article-marker"));
        when(articles.findById(99L)).thenThrow(new IllegalStateException("private-article-marker"));
        when(views.findTop30ByOperatorIdOrderByViewedAtDesc(20L))
                .thenThrow(new IllegalStateException("private-article-marker"));
        // The four routes live in three of the controllers ArticleController
        // was split into; they share one ArticleEndpointSupport, as in the app.
        ArticleTargetQueryService targets = mock(ArticleTargetQueryService.class);
        PermissionChecker permissions = new PermissionChecker();
        ArticleEndpointSupport support = new ArticleEndpointSupport(
                mock(ArticleTargetDepartmentRepository.class), targets, permissions);
        ArticleController reading = new ArticleController(articles, targets, views,
                mock(CategoryRepository.class), mock(ArticleQueryService.class), support);
        ArticleHistoryController historyRoutes = new ArticleHistoryController(articles, history,
                mock(UserRepository.class), permissions, mock(SearchReindexService.class),
                mock(ArticleHtmlSanitizer.class), mock(MutationAuditService.class),
                mock(FileReferenceIndex.class), support);
        ArticleVerificationController verification = new ArticleVerificationController(
                articles, permissions, mock(MutationAuditService.class), support);
        User admin = user(19L, Role.SYSTEM_ADMIN, "All");
        Map<String, MockMvc> adminMvcByRoute = Map.of(
                "/api/admin/articles/stale", mvc(verification, admin),
                "/api/articles/99/history-summary", mvc(historyRoutes, admin));
        MockMvc operatorMvc = mvc(reading, user(20L, Role.OPERATOR, "All"));

        for (String route : List.of("/api/admin/articles/stale", "/api/articles/99/history-summary")) {
            String body = adminMvcByRoute.get(route).perform(get(route))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-article-marker"), route);
        }
        for (String route : List.of("/api/articles/99/related", "/api/me/recently-viewed")) {
            String body = operatorMvc.perform(get(route))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-article-marker"), route);
        }
    }

    @Test
    void sessionListAndRevokeFailuresHaveSafeResponses() throws Exception {
        PortalSessionService sessions = mock(PortalSessionService.class);
        when(sessions.list(21L)).thenThrow(new IllegalStateException("private-session-marker"));
        when(sessions.revoke("owned-session", 21L))
                .thenThrow(new IllegalStateException("private-session-marker"));
        PortalSessionController controller = new PortalSessionController(sessions,
                mock(MutationAuditService.class), mock(ClientIpResolver.class));
        MockMvc mvc = mvc(controller, user(21L, Role.OPERATOR, "All"));

        for (String route : List.of("/api/auth/sessions", "/api/auth/sessions/owned-session")) {
            String body = (route.endsWith("owned-session")
                    ? mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(route))
                    : mvc.perform(get(route)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-session-marker"), route);
        }
    }

    @Test
    void personalSearchAndQuizScoreQueryFailuresAreSanitized() throws Exception {
        User caller = user(22L, Role.OPERATOR, "All");
        SearchLogRepository logs = mock(SearchLogRepository.class);
        when(logs.findByUserIdOrderByTimestampDescIdDesc(eq(22L), any()))
                .thenThrow(new IllegalStateException("private-search-marker"));
        SearchController search = new SearchController(mock(SearchQueryService.class),
                mock(GlobalSearchCache.class), logs, mock(ArticleTargetQueryService.class),
                mock(CategoryRepository.class));
        String history = mvc(search, caller).perform(get("/api/search/history"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(history.contains("private-search-marker"));

        KnowledgeScoreService scores = mock(KnowledgeScoreService.class);
        when(scores.compute(22L)).thenThrow(new IllegalStateException("private-score-marker"));
        QuizController quiz = new QuizController(mock(ArticleRepository.class),
                mock(ArticleTargetQueryService.class), mock(QuizQuestionRepository.class),
                mock(QuizAnswerRepository.class), mock(QuizAttemptRepository.class),
                mock(MutationAuditService.class), scores, new PermissionChecker(),
                new ArticleEndpointSupport(mock(ArticleTargetDepartmentRepository.class),
                        mock(ArticleTargetQueryService.class), new PermissionChecker()));
        String score = mvc(quiz, caller).perform(get("/api/users/me/knowledge-score"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(score.contains("private-score-marker"));
    }

    @Test
    void currentUserTeamAndLeaderQueryFailuresAreSanitized() throws Exception {
        User admin = user(23L, Role.SYSTEM_ADMIN, "All");
        PermissionChecker permissions = mock(PermissionChecker.class);
        when(permissions.effectivePermissions(admin))
                .thenThrow(new IllegalStateException("private-user-marker"));
        UserDirectoryQueryService users = mock(UserDirectoryQueryService.class);
        when(users.listUsersByRoleWithinLimit(Role.MANAGER))
                .thenThrow(new IllegalStateException("private-user-marker"));
        OrgDirectoryQueryService org = mock(OrgDirectoryQueryService.class);
        when(org.listTeamsWithinLimit()).thenThrow(new IllegalStateException("private-user-marker"));
        UserController controller = new UserController(mock(UserRepository.class),
                mock(ComplianceProgressQueryService.class), mock(PasswordEncoder.class),
                permissions, mock(ge.magti.portal.repository.UserPermissionOverrideRepository.class),
                mock(BroadcastAuthorizationService.class), mock(MutationAuditService.class),
                users, org, new PortalProperties());
        MockMvc mvc = mvc(controller, admin);

        for (String route : List.of("/api/users/me", "/api/me/effective-access",
                "/api/teams", "/api/admin/group-leaders")) {
            String body = mvc.perform(get(route))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("private-user-marker"), route);
        }
    }

    @Test
    void policyDiagnosticsRoutesPreserveGateAndBackfillCardinalityFailure() throws Exception {
        OrgBackfillService backfill = mock(OrgBackfillService.class);
        MutationAuditService audit = mock(MutationAuditService.class);
        OrgBackfillPlan empty = new OrgBackfillPlan(List.of(), List.of(), List.of(), List.of());
        when(backfill.plan()).thenReturn(empty);
        when(backfill.apply(18L)).thenReturn(empty);
        PolicyDiagnosticsController controller = new PolicyDiagnosticsController(
                new PolicyShadowRecorder(), backfill, audit);
        MockMvc adminMvc = mvc(controller, user(18L, Role.SYSTEM_ADMIN, "All"));
        MockMvc managerMvc = mvc(controller, user(19L, Role.MANAGER, "All"));

        adminMvc.perform(get("/api/admin/policy-shadow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions").isMap());
        adminMvc.perform(get("/api/admin/org-backfill/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks_cutover").value(false));
        adminMvc.perform(post("/api/admin/org-backfill/apply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks_cutover").value(false));
        for (String route : List.of("/api/admin/policy-shadow", "/api/admin/org-backfill/report")) {
            managerMvc.perform(get(route)).andExpect(status().isForbidden());
        }
        managerMvc.perform(post("/api/admin/org-backfill/apply"))
                .andExpect(status().isForbidden());
        verify(backfill, times(1)).apply(18L);

        when(backfill.plan()).thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());
        when(backfill.apply(18L)).thenThrow(new UserDirectoryQueryService.ActiveUserCardinalityExceededException());
        adminMvc.perform(get("/api/admin/org-backfill/report"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        adminMvc.perform(post("/api/admin/org-backfill/apply"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        verify(audit, times(1)).recordSuccess(any(), any(), any(), any(), any(), any(), any());

        PolicyShadowRecorder brokenRecorder = mock(PolicyShadowRecorder.class);
        when(brokenRecorder.snapshot()).thenThrow(new IllegalStateException("private-shadow-marker"));
        String shadowFailure = mvc(new PolicyDiagnosticsController(brokenRecorder, backfill, audit),
                user(18L, Role.SYSTEM_ADMIN, "All"))
                .perform(get("/api/admin/policy-shadow"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(shadowFailure.contains("private-shadow-marker"));
    }

    @Test
    void oversizedReadingsExportsDoNotCreatePdfOrXlsxJobs() throws Exception {
        User admin = user(4L, Role.SYSTEM_ADMIN, "All");
        admin.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        ExportQueryService query = mock(ExportQueryService.class);
        ExportJobRepository jobs = mock(ExportJobRepository.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        when(query.eligibleReadingRows(admin, null, null)).thenThrow(new ExportTooLargeException(100_001, 100_000));
        ExportController controller = new ExportController(query, jobs, worker, audit, new PermissionChecker());

        for (String path : List.of("/api/export/readings.pdf", "/api/export/readings.xlsx")) {
            mvc(controller, admin).perform(get(path))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.detail").value(
                            "ექსპორტი ძალიან დიდია (100001 ჩანაწერი, ზღვარი 100000). აირჩიეთ უფრო მოკლე პერიოდი."))
                    .andExpect(header().doesNotExist("Content-Disposition"));
        }
        verifyNoInteractions(jobs, worker, audit);
    }

    @Test
    void teamStatsQueryFailureDoesNotCreatePdfJob() throws Exception {
        User admin = user(5L, Role.SYSTEM_ADMIN, "All");
        admin.setPermissions(Set.of(Permission.REPORTS_EXPORT.value()));
        ExportQueryService query = mock(ExportQueryService.class);
        ExportJobRepository jobs = mock(ExportJobRepository.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);
        AuditLogRepository audit = mock(AuditLogRepository.class);
        when(query.departmentComplianceTotals(admin)).thenThrow(new IllegalStateException("private-totals-marker"));
        ExportController controller = new ExportController(query, jobs, worker, audit, new PermissionChecker());

        String body = mvc(controller, admin).perform(get("/api/export/team-stats.pdf"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-totals-marker"));
        verifyNoInteractions(jobs, worker, audit);
    }

    @Test
    void activeBroadcastQueryFailureHasSanitizedResponse() throws Exception {
        BroadcastService service = mock(BroadcastService.class);
        when(service.active()).thenThrow(new IllegalStateException("private-broadcast-marker"));
        BroadcastController controller = new BroadcastController(service, mock(BroadcastAuthorizationService.class));

        String body = mvc(controller, user(6L, Role.OPERATOR, "All"))
                .perform(get("/api/broadcasts"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-broadcast-marker"));
    }

    @Test
    void favoriteListQueryFailureHasSanitizedResponseAndDoesNotResolveTitles() throws Exception {
        FavoriteRepository favorites = mock(FavoriteRepository.class);
        ItemTitleResolver titles = mock(ItemTitleResolver.class);
        when(favorites.findByUserId(eq(7L), any()))
                .thenThrow(new IllegalStateException("private-favorite-marker"));

        String body = mvc(new FavoriteController(favorites, titles), user(7L, Role.OPERATOR, "All"))
                .perform(get("/api/favorites"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-favorite-marker"));
        verifyNoInteractions(titles);
    }

    @Test
    void categoryListQueryFailureHasSanitizedResponse() throws Exception {
        CategoryRepository categories = mock(CategoryRepository.class);
        when(categories.findByActiveTrue(any()))
                .thenThrow(new IllegalStateException("private-category-marker"));
        CategoryController controller = new CategoryController(categories, mock(ArticleRepository.class),
                new PermissionChecker(), mock(MutationAuditService.class));

        String body = mvc(controller, user(8L, Role.OPERATOR, "All"))
                .perform(get("/api/categories"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-category-marker"));
    }

    @Test
    void videoListQueryFailureHasSanitizedResponse() throws Exception {
        VideoInstructionRepository videos = mock(VideoInstructionRepository.class);
        when(videos.findByArchivedFalse(any()))
                .thenThrow(new IllegalStateException("private-video-marker"));
        VideoController controller = new VideoController(videos, new PermissionChecker(),
                mock(TagSyncService.class), mock(SearchReindexService.class),
                mock(ContentLifecycleService.class), mock(MutationAuditService.class),
                mock(FileReferenceIndex.class));

        String body = mvc(controller, user(9L, Role.OPERATOR, "All"))
                .perform(get("/api/videos"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-video-marker"));
    }

    @Test
    void trashListQueryFailureHasSanitizedResponse() throws Exception {
        ContentLifecycleService lifecycle = mock(ContentLifecycleService.class);
        when(lifecycle.listTrash(any())).thenThrow(new IllegalStateException("private-trash-marker"));
        User admin = user(10L, Role.CONTENT_ADMIN, "All");
        admin.setPermissions(Set.of(Permission.CONTENT_MANAGE.value()));
        ContentTrashController controller = new ContentTrashController(lifecycle,
                new PermissionChecker(), mock(LegalHoldAuthority.class));

        String body = mvc(controller, admin)
                .perform(get("/api/content-trash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-trash-marker"));
    }

    @Test
    void newsHistorySummaryQueryFailureHasSanitizedResponse() throws Exception {
        NewsHistoryRepository history = mock(NewsHistoryRepository.class);
        when(history.findSummaryByNewsIdOrderByUpdatedAtDesc(eq(99L), any()))
                .thenThrow(new IllegalStateException("private-news-history-marker"));
        User admin = user(11L, Role.CONTENT_ADMIN, "All");
        admin.setPermissions(Set.of(Permission.CONTENT_MANAGE.value()));
        NewsController controller = new NewsController(mock(NewsRepository.class), history,
                mock(UserRepository.class), mock(NewsQueryService.class),
                mock(SearchReindexService.class), mock(ContentLifecycleService.class),
                new PermissionChecker(), mock(ArticleHtmlSanitizer.class),
                mock(MutationAuditService.class), mock(FileReferenceIndex.class));

        String body = mvc(controller, admin).perform(get("/api/news/99/history-summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("მოხდა მოულოდნელი შეცდომა. გთხოვთ, სცადოთ ხელახლა."))
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-news-history-marker"));
    }

    @Test
    void personalComplianceQueriesHaveSanitizedFailures() throws Exception {
        ComplianceQueryService query = mock(ComplianceQueryService.class);
        RequiredReadingRepository readings = mock(RequiredReadingRepository.class);
        when(query.computeForUser(12L)).thenThrow(new IllegalStateException("private-progress-marker"));
        when(readings.findByTargetDepartmentIn(any(), any()))
                .thenThrow(new IllegalStateException("private-readings-marker"));
        ComplianceController controller = new ComplianceController(query, readings,
                mock(ReadStatusRepository.class), mock(ArticleRepository.class),
                mock(ge.magti.portal.repository.ArticleReadReceiptRepository.class),
                mock(QuizGateChecker.class), mock(ReminderService.class),
                mock(ItemTitleResolver.class), new PermissionChecker(),
                mock(MutationAuditService.class), mock(RequiredReadingMutationService.class),
                mock(ReadingAcknowledgementService.class),
                mock(ge.magti.portal.compliance.MandatoryReach.class),
                mock(ge.magti.portal.security.ScopeResolver.class));
        MockMvc mvc = mvc(controller, user(12L, Role.OPERATOR, "ტექნიკური"));

        String progress = mvc.perform(get("/api/compliance/my-progress"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String list = mvc.perform(get("/api/compliance/my-readings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(progress.contains("private-progress-marker"));
        assertFalse(list.contains("private-readings-marker"));
    }

    @Test
    void platformListFailuresAreSanitizedBeforeAnyEmployeeDataIsReturned() throws Exception {
        TagRepository tags = mock(TagRepository.class);
        RequiredReadingRepository readings = mock(RequiredReadingRepository.class);
        when(tags.findAllByOrderByName(any())).thenThrow(new IllegalStateException("private-tag-marker"));
        when(readings.findByTargetDepartmentIn(any(), any()))
                .thenThrow(new IllegalStateException("private-notification-marker"));
        PlatformController controller = new PlatformController(tags, readings,
                mock(ReadStatusRepository.class), mock(NewsRepository.class),
                mock(ReminderRepository.class), mock(ItemTitleResolver.class),
                mock(ge.magti.portal.compliance.MandatoryReach.class));
        MockMvc mvc = mvc(controller, user(13L, Role.OPERATOR, "ტექნიკური"));

        String tagBody = mvc.perform(get("/api/tags"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String notificationBody = mvc.perform(get("/api/notifications/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(tagBody.contains("private-tag-marker"));
        assertFalse(notificationBody.contains("private-notification-marker"));
    }

    @Test
    void orgAdminQueriesFailWithCorrelationAndNoEmployeeData() throws Exception {
        OrgDirectoryQueryService directory = mock(OrgDirectoryQueryService.class);
        when(directory.listActiveDepartmentsWithinLimit())
                .thenThrow(new IllegalStateException("private-org-marker"));
        when(directory.listAssignmentsWithinLimit())
                .thenThrow(new IllegalStateException("private-assignment-marker"));
        OrgAdminController controller = new OrgAdminController(mock(DepartmentRepository.class),
                mock(TeamRepository.class), mock(UserRepository.class),
                mock(LeadershipAssignmentService.class), directory);
        MockMvc mvc = mvc(controller, user(14L, Role.SYSTEM_ADMIN, "All"));

        String structure = mvc.perform(get("/api/admin/org/structure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String assignments = mvc.perform(get("/api/admin/org/assignments"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(structure.contains("private-org-marker"));
        assertFalse(assignments.contains("private-assignment-marker"));
    }

    private static User user(long id, Role role, String department) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setDepartment(department);
        user.setActive(true);
        return user;
    }

    private static MockMvc mvc(Object controller, User user) {
        HandlerMethodArgumentResolver principal = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return user;
            }
        };
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(principal)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
