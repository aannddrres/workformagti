package ge.magti.portal.export;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@RequiresOracle
@SpringBootTest
class ExportJobRecoveryAuditRollbackIntegrationTest {
    @Autowired private ExportJobRepository jobs;
    @Autowired private ExportJobLifecycle lifecycle;
    @MockitoBean private MutationAuditService audit;

    @Test
    void failedRecoveryAuditLeavesTheJobProcessingForTheNextReaperAttempt() {
        ExportJob job = new ExportJob();
        job.setId(UUID.randomUUID().toString());
        job.setStatus("processing");
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() - 60);
        jobs.saveAndFlush(job);

        doThrow(new IllegalStateException("synthetic audit failure"))
                .when(audit).recordSystemResult(eq("EXPORT_RECOVERY"), eq("EXPORT_JOB_INTERRUPTED"),
                        any(), any(), any(), any(), any(), any(), any());

        assertThrows(IllegalStateException.class, () -> lifecycle.recoverExpired(job.getId()));
        assertEquals("processing", jobs.findById(job.getId()).orElseThrow().getStatus());
    }
}
