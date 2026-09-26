package ge.magti.portal.export;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ExportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Atomically registers a scoped export job and the evidence that authorized it. */
@Service
public class LegacyExportJobService {

    private final ExportJobRepository jobRepository;
    private final MutationAuditService mutationAuditService;
    private final ExportJobLeaseOwner leaseOwner;

    public LegacyExportJobService(
            ExportJobRepository jobRepository, MutationAuditService mutationAuditService,
            ExportJobLeaseOwner leaseOwner) {
        this.jobRepository = jobRepository;
        this.mutationAuditService = mutationAuditService;
        this.leaseOwner = leaseOwner;
    }

    @Transactional
    public String register(
            User actor,
            String action,
            String itemType,
            String title,
            String exportFormat,
            int rowCount,
            String scopeDepartment) {
        String jobId = UUID.randomUUID().toString();
        ExportJob job = new ExportJob();
        job.setId(jobId);
        job.setOwnerUserId(actor.getId());
        job.setStatus("processing");
        job.setPath(null);
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        jobRepository.saveAndFlush(job);
        if (jobRepository.startLease(jobId, leaseOwner.id()) != 1) {
            throw new IllegalStateException("Could not initialize export job lease");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("job_id", jobId);
        after.put("export_format", exportFormat);
        after.put("scope_department", scopeDepartment);
        after.put("row_count", rowCount);
        after.put("status", job.getStatus());
        mutationAuditService.recordSuccess(actor, action, itemType, 0L, title, null, after);
        return jobId;
    }
}
