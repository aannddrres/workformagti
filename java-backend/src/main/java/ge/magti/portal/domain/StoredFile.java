package ge.magti.portal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * An uploaded attachment, stored as bytes in Oracle rather than on the
 * container's filesystem (audit PR-03).
 *
 * <p>There is no Python counterpart: the Python app wrote to {@code
 * settings.UPLOAD_DIR} and mounted it with {@code StaticFiles}, which is
 * exactly the arrangement PR-03 flags. This table is the Java side's
 * replacement for that directory, not a port of a model.
 *
 * <p>The primary key is the generated filename ({@code <uuid>.<ext>}) rather
 * than a surrogate id, because that is what {@code articles.attachment_url}
 * and every inline {@code <img src="/uploads/...">} already embed. Keeping it
 * as the key means existing rows keep resolving with no data migration and no
 * second lookup.
 */
@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    @Column(name = "filename", length = 100)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 150)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    /** Nullable, and ON DELETE SET NULL in V31: deleting a user must not delete an article's attachment. */
    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /** Nullable by design -- see the comment on the column in V31. */
    @Lob
    @Column(name = "content")
    private byte[] content;

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }
}
