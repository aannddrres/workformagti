package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * Mirrors models.py's ExportJob (models.py:675-685, table
 * {@code export_jobs}) -- the registry row a background XLSX/PDF export
 * writes its outcome to, read back by {@code GET /api/export/status/{id}}
 * and {@code GET /api/export/download/{id}}. DB-backed on purpose (per the
 * Python docstring) so status/download requests work regardless of which
 * gunicorn worker handles them.
 *
 * <p>{@link #expiresAt} is a raw Unix-epoch-seconds value (Python's
 * {@code time.time() + _EXPORT_JOB_TTL}, routers/exports.py:368,376,395) --
 * unlike every other {@code *At} timestamp in this codebase, it is
 * deliberately NOT modeled as {@link java.time.OffsetDateTime}/
 * {@link ge.magti.portal.util.TbilisiTime}, since the Python value itself
 * isn't a Tbilisi wall-clock time, it's a raw float epoch. A plain
 * {@code double} is the faithful shape.
 *
 * <p><b>Known bug #9, carried over unchanged, nothing to fix here yet:</b>
 * {@code expires_at} is written in three places (routers/exports.py:368,
 * 376, 395) but read in zero -- neither {@code get_export_status} nor
 * {@code download_export} (routers/exports.py:422-447) ever compares it
 * against the current time. A finished export file sits on disk and stays
 * downloadable forever (or until the next {@code _cleanup_export} call,
 * which only runs after an actual successful download, not on a timer).
 * This field is therefore currently a dead value on the Python side. Carried
 * over as-is since there is no service/repository logic ported yet for this
 * class to attach a real expiry check to -- flag this again when that layer
 * is written, the same way {@link VideoInstruction#getTargetDepartment()}
 * flags bug #10 for its own later repository step.
 */
@Entity
@Table(name = "export_jobs")
public class ExportJob {

    // No @GeneratedValue: the ID is an application-assigned UUID4 string
    // (routers/exports.py), not DB-generated.
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

    public double getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(double expiresAt) {
        this.expiresAt = expiresAt;
    }
}
