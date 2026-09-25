package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportQueryService;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleViewLogRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.SearchLogRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.PermissionChecker;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        when(query.eligibleReadingRows(admin)).thenThrow(new IllegalStateException("private-query-marker"));
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
