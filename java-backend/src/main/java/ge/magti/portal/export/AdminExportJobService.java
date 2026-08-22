package ge.magti.portal.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.util.TbilisiTime;
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
    private final AuditLogRepository auditRepository;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AdminExportJobService(ExportJobRepository jobRepository, AuditLogRepository auditRepository) {
        this.jobRepository = jobRepository;
        this.auditRepository = auditRepository;
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
        jobRepository.save(job);

        AuditLog audit = new AuditLog();
        audit.setAdminId(actor.getId());
        audit.setAdminNameSnapshot(actor.getName());
        audit.setAdminEmailSnapshot(actor.getEmail());
        audit.setAction(family.auditAction());
        audit.setItemType("admin_export");
        audit.setItemId(0L);
        audit.setItemNameSnapshot(family.title());
        audit.setTimestamp(TbilisiTime.now());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("job_id", jobId);
        details.put("export_family", family.code());
        details.put("date_from", from);
        details.put("date_through", through);
        details.put("row_count", rowCount);
        try {
            audit.setDetails(objectMapper.writeValueAsString(details));
        } catch (Exception e) {
            throw new IllegalStateException("Admin export audit serialization failed", e);
        }
        auditRepository.save(audit);
        return jobId;
    }
}
