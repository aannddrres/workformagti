package ge.magti.portal.web;

import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.export.AdminExportDataset;
import ge.magti.portal.export.AdminExportFamily;
import ge.magti.portal.export.AdminExportJobService;
import ge.magti.portal.export.AdminExportQueryService;
import ge.magti.portal.export.ExportJobWorker;
import ge.magti.portal.export.ExportTooLargeException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level success, authorization, and validation/failure contract for each PO-14 data family. */
class AdminExportRouteContractTest {

    @ParameterizedTest(name = "{0} route rejects a manager before loading data")
    @EnumSource(AdminExportFamily.class)
    void managerCannotQueueAnyFamily(AdminExportFamily family) throws Exception {
        AdminExportQueryService query = mock(AdminExportQueryService.class);
        AdminExportJobService jobs = mock(AdminExportJobService.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);

        mvc(new AdminExportController(query, jobs, worker), user(Role.MANAGER))
                .perform(post(path(family)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        verifyNoInteractions(query, jobs, worker);
    }

    @ParameterizedTest(name = "{0} route queues the matching data family")
    @EnumSource(AdminExportFamily.class)
    void adminQueuesOnlyTheRequestedFamily(AdminExportFamily family) throws Exception {
        AdminExportQueryService query = mock(AdminExportQueryService.class);
        AdminExportJobService jobs = mock(AdminExportJobService.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);
        User admin = user(Role.SYSTEM_ADMIN);
        List<String> headers = List.of("სვეტი");
        List<List<Object>> rows = List.of(List.of("მონაცემი"));
        when(query.load(family, null, null))
                .thenReturn(new AdminExportDataset(family, headers, rows));
        when(jobs.register(admin, family, null, null, 1)).thenReturn("job-test");

        mvc(new AdminExportController(query, jobs, worker), admin)
                .perform(post(path(family)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job_id").value("job-test"));
        verify(query).load(family, null, null);
        verify(jobs).register(admin, family, null, null, 1);
        verify(worker).track("job-test");
        verify(worker).buildAdminAndStore("job-test", family.title(), headers, rows, family.filenamePrefix());
    }

    @ParameterizedTest(name = "{0} route rejects a reversed date range")
    @EnumSource(AdminExportFamily.class)
    void reversedDatesDoNotQueueAJob(AdminExportFamily family) throws Exception {
        AdminExportQueryService query = mock(AdminExportQueryService.class);
        AdminExportJobService jobs = mock(AdminExportJobService.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);

        mvc(new AdminExportController(query, jobs, worker), user(Role.SYSTEM_ADMIN))
                .perform(post(path(family)).param("from", "2026-09-25").param("through", "2026-09-24"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        verifyNoInteractions(query, jobs, worker);
    }

    @ParameterizedTest(name = "{0} route refuses an oversized result without creating a job")
    @EnumSource(AdminExportFamily.class)
    void oversizedDatasetDoesNotQueueAJob(AdminExportFamily family) throws Exception {
        AdminExportQueryService query = mock(AdminExportQueryService.class);
        AdminExportJobService jobs = mock(AdminExportJobService.class);
        ExportJobWorker worker = mock(ExportJobWorker.class);
        when(query.load(family, null, null)).thenThrow(new ExportTooLargeException(100_001, 100_000));

        mvc(new AdminExportController(query, jobs, worker), user(Role.SYSTEM_ADMIN))
                .perform(post(path(family)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").isNotEmpty());
        verifyNoInteractions(jobs, worker);
    }

    private static String path(AdminExportFamily family) {
        return "/api/admin/exports/" + family.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static User user(Role role) {
        User user = new User();
        user.setId(101L);
        user.setRole(role);
        user.setActive(true);
        return user;
    }

    private static MockMvc mvc(AdminExportController controller, User user) {
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
