package ge.magti.portal.export;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ExportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Atomically registers both a sensitive export job and its audit evidence. */
@Service
public class AdminExportJobService {

    private final ExportJobRepository jobRepository;
    private final MutationAuditService mutationAuditService;
    private final ExportJobLeaseOwner leaseOwner;

    public AdminExportJobService(
            ExportJobRepository jobRepository, MutationAuditService mutationAuditService,
            ExportJobLeaseOwner leaseOwner) {
        this.jobRepository = jobRepository;
        this.mutationAuditService = mutationAuditService;
        this.leaseOwner = leaseOwner;
    }

    @Transactional
    public String register(
            User actor, AdminExportFamily family, LocalDate from, LocalDate through, int rowCount) {
        String jobId = UUID.randomUUID().toString();

        ExportJob job = new ExportJob();
        job.setId(jobId);
        job.setOwnerUserId(actor.getId());
        job.setExportFamily(family.code());
        job.setStatus("processing");
        job.setPath(null);
        job.setExpiresAt(System.currentTimeMillis() / 1000.0 + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        jobRepository.saveAndFlush(job);
        if (jobRepository.startLease(jobId, leaseOwner.id()) != 1) {
            throw new IllegalStateException("Could not initialize export job lease");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("job_id", jobId);
        after.put("export_family", family.code());
        after.put("date_from", from == null ? null : from.toString());
        after.put("date_through", through == null ? null : through.toString());
        after.put("row_count", rowCount);
        after.put("status", job.getStatus());
        mutationAuditService.recordSuccess(
                actor,
                family.auditAction(),
                "admin_export",
                0L,
                family.title(),
                null,
                after);
        return jobId;
    }
}
