package ge.magti.portal.export;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.repository.ExportJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Short transactions serialize completion, worker failure and lease recovery. */
@Service
public class ExportJobLifecycle {

    private final ExportJobRepository jobs;
    private final MutationAuditService audit;
    private final ExportJobLeaseOwner owner;

    public ExportJobLifecycle(ExportJobRepository jobs, MutationAuditService audit, ExportJobLeaseOwner owner) {
        this.jobs = jobs;
        this.audit = audit;
        this.owner = owner;
    }

    @Transactional
    public boolean complete(String jobId, byte[] data, String filename, String format) {
        ExportJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (!ownsLiveLease(job, jobs.databaseNow())) {
            return false;
        }
        Map<String, Object> before = snapshot(job, format);
        job.setStatus("completed");
        job.setContent(data);
        job.setFilename(filename);
        job.setPath(null);
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        job.setWorkerInstanceId(null);
        job.setLeaseUntil(null);
        jobs.saveAndFlush(job);
        audit.recordSystemResult("EXPORT_WORKER", "EXPORT_JOB_COMPLETED", "export_job", 0L,
                "Export job", "SUCCESS", null, before, snapshot(job, format));
        return true;
    }

    @Transactional
    public boolean failBuild(String jobId, String format) {
        ExportJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (!ownsLiveLease(job, jobs.databaseNow())) {
            return false;
        }
        Map<String, Object> before = snapshot(job, format);
        job.setStatus("failed");
        job.setPath(null);
        job.setExpiresAt(jobs.databaseNow().toEpochSecond() + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        job.setWorkerInstanceId(null);
        job.setLeaseUntil(null);
        jobs.saveAndFlush(job);
        audit.recordSystemResult("EXPORT_WORKER", "EXPORT_JOB_FAILED", "export_job", 0L,
                "Export job", "FAILURE", "EXPORT_BUILD_FAILED", before, snapshot(job, format));
        return true;
    }

    @Transactional
    public boolean recoverExpired(String jobId) {
        ExportJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || !"processing".equals(job.getStatus())) {
            return false;
        }
        OffsetDateTime now = jobs.databaseNow();
        boolean expired = job.getLeaseUntil() == null
                ? job.getExpiresAt() <= now.toEpochSecond()
                : !job.getLeaseUntil().isAfter(now);
        if (!expired) {
            return false;
        }
        Map<String, Object> before = snapshot(job, "unknown");
        job.setStatus("failed");
        job.setPath(null);
        job.setExpiresAt(now.toEpochSecond() + ExportJobWorker.EXPORT_JOB_TTL_SECONDS);
        job.setWorkerInstanceId(null);
        job.setLeaseUntil(null);
        jobs.saveAndFlush(job);
        audit.recordSystemResult("EXPORT_RECOVERY", "EXPORT_JOB_INTERRUPTED", "export_job", 0L,
                "Export job", "FAILURE", "WORKER_LEASE_EXPIRED", before, snapshot(job, "unknown"));
        return true;
    }

    private boolean ownsLiveLease(ExportJob job, OffsetDateTime now) {
        return job != null && "processing".equals(job.getStatus())
                && owner.id().equals(job.getWorkerInstanceId())
                && job.getLeaseUntil() != null && job.getLeaseUntil().isAfter(now);
    }

    private static Map<String, Object> snapshot(ExportJob job, String format) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("job_id", job.getId());
        state.put("owner_user_id", job.getOwnerUserId());
        state.put("export_family", job.getExportFamily());
        state.put("export_format", format);
        state.put("status", job.getStatus());
        state.put("byte_size", job.getContent() == null ? 0 : job.getContent().length);
        return state;
    }
}
