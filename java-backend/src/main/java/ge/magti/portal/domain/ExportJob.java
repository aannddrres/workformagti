package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * The {@code export_jobs} table -- the registry row a background XLSX/PDF export
 * writes its outcome to, read back by {@code GET /api/export/status/{id}}
 * and {@code GET /api/export/download/{id}}. DB-backed on purpose so
 * status/download requests work regardless of which replica handles them.
 *
 * <p>{@link #expiresAt} is a raw Unix-epoch-seconds value (now plus the job
 * TTL) --
 * unlike every other {@code *At} timestamp in this codebase, it is
 * deliberately NOT modeled as {@link java.time.OffsetDateTime}/
 * {@link ge.magti.portal.util.TbilisiTime}, since the value itself
 * isn't a Tbilisi wall-clock time, it's a raw float epoch. A plain
 * {@code double} is the faithful shape.
 *
 * <p>The Java cleanup scheduler expires only completed/failed rows. Running
 * work uses {@code leaseUntil}; an interrupted worker is marked failed by
 * recovery and kept visible for another hour before cleanup.
 */
@Entity
@Table(name = "export_jobs")
public class ExportJob {

    // No @GeneratedValue: the ID is an application-assigned UUID4 string,
    // not DB-generated.
    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "processing";

    /**
     * <b>Legacy since V31 (audit PR-03/BL-09).</b> A pod-local absolute path,
     * which is exactly the problem: the row lived in shared Oracle while the
     * file lived on whichever container happened to build it. New jobs leave
     * this NULL and write {@link #content}/{@link #filename} instead; it is
     * still read as a fallback for rows created before the migration, and
     * {@code ExportJobCleanupScheduler} still deletes the files they point at.
     */
    @Column(name = "path", length = 1000)
    private String path;

    @Lob
    @Column(name = "content")
    private byte[] content;

    /** Download filename, e.g. {@code export_<uuid>.xlsx}. Carries the extension the response's media type is chosen from. */
    @Column(name = "filename", length = 200)
    private String filename;

    @Column(name = "expires_at", nullable = false)
    private double expiresAt;

    /**
     * Who asked for this export (V36, access contract D-3).
     *
     * <p>Nullable: rows built before that migration have no owner recorded.
     * {@code ExportController} reads an unknown owner as "not yours" for
     * everyone except SYSTEM_ADMIN, so those rows fail closed and age out on
     * the existing TTL instead of needing a backfill.
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /** Non-null for classified exports; ADMIN_* jobs are always owner-only. */
    @Column(name = "export_family", length = 50)
    private String exportFamily;

    @Column(name = "worker_instance_id", length = 36)
    private String workerInstanceId;

    /**
     * The one {@code OffsetDateTime} here that is not a Tbilisi wall-clock
     * value: V50 made the column {@code TIMESTAMP WITH TIME ZONE}, and the
     * lease is compared with {@code SYSTIMESTAMP} in SQL (startLease,
     * renewLease, findExpiredProcessingIds). The auto-applied
     * {@link ge.magti.portal.util.TbilisiTimestampConverter} writes a +04:00
     * wall-clock without its zone, which Oracle then labels with the session
     * zone. On any JVM not running at +04:00 -- CI, and the backend image,
     * which runs UTC -- a lease was written four hours late and read back
     * four hours early, so every export lost its own lease and stayed
     * "processing" (ExportJobRecoveryIntegrationTest). Stored natively, the
     * instant survives whatever zone the JVM and the session use.
     */
    @Convert(disableConversion = true)
    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getExportFamily() {
        return exportFamily;
    }

    public void setExportFamily(String exportFamily) {
        this.exportFamily = exportFamily;
    }

    public double getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(double expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getWorkerInstanceId() {
        return workerInstanceId;
    }

    public void setWorkerInstanceId(String workerInstanceId) {
        this.workerInstanceId = workerInstanceId;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(OffsetDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }
}
