package ge.magti.portal.export;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportJobWorkerTest {

    @Test
    void buildFailureStoresSanitizedFailureEvidenceWithTheStatusChange() {
        ExportJobRepository repository = mock(ExportJobRepository.class);
        MutationAuditService auditService = mock(MutationAuditService.class);
        ExportJob job = new ExportJob();
        job.setId("job-1");
        job.setOwnerUserId(42L);
        job.setStatus("processing");
        when(repository.findById("job-1")).thenReturn(Optional.of(job));

        ExportJobWorker worker = new ExportJobWorker(repository, auditService);
        worker.buildAndStore("job-1", "title", List.of("header"), null, "pdf");

        assertEquals("failed", job.getStatus());
        verify(repository).saveAndFlush(job);
        verify(auditService).recordSystemResult(
                eq("EXPORT_WORKER"), eq("EXPORT_JOB_FAILED"), eq("export_job"), eq(0L),
                eq("Export job"), eq("FAILURE"), eq("EXPORT_BUILD_FAILED"), any(), any());
    }
}
