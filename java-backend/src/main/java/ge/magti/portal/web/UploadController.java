package ge.magti.portal.web;

import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors routers/platform.py's {@code POST /api/upload} (routers/platform.py:200-266):
 * a single generic attachment endpoint reused by the Article/News/Video admin
 * forms (attachments, dropzone, drag-drop, pasted-image embeds).
 *
 * <p>Same two checks as Python, in the same order: MIME allowlist first (the
 * stored extension is derived from the server-detected {@code content_type},
 * never the client-supplied filename -- config.py's comment on {@code
 * ALLOWED_UPLOAD_TYPES} explains why: a spoofed filename could otherwise
 * smuggle .html/.svg/.php for stored-XSS or arbitrary execution), then the
 * size cap. Spring's {@code spring.servlet.multipart.max-file-size} (see
 * application.yml) already rejects anything over the cap before this method
 * runs, so the explicit {@link MultipartFile#getSize()} check here is a
 * belt-and-suspenders mirror of Python's manual streaming cap, not the only
 * guard.
 */
@RestController
public class UploadController {

    private static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    /** Mirrors config.py's Settings.ALLOWED_UPLOAD_TYPES exactly. */
    private static final Map<String, String> ALLOWED_UPLOAD_TYPES = Map.ofEntries(
            Map.entry("application/pdf", ".pdf"),
            Map.entry("image/png", ".png"),
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/gif", ".gif"),
            Map.entry("image/webp", ".webp"),
            Map.entry("text/plain", ".txt"),
            Map.entry("application/msword", ".doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
            Map.entry("application/vnd.ms-excel", ".xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"),
            Map.entry("video/mp4", ".mp4")
    );

    private final PortalProperties portalProperties;
    private final AuditLogRepository auditLogRepository;

    public UploadController(PortalProperties portalProperties, AuditLogRepository auditLogRepository) {
        this.portalProperties = portalProperties;
        this.auditLogRepository = auditLogRepository;
    }

    @PostMapping("/api/upload")
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType();
        int semicolon = contentType.indexOf(';');
        if (semicolon >= 0) {
            contentType = contentType.substring(0, semicolon);
        }
        contentType = contentType.trim().toLowerCase(Locale.ROOT);

        String ext = ALLOWED_UPLOAD_TYPES.get(contentType);
        if (ext == null) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                    "detail", "დაუშვებელი ფაილის ტიპი '" + file.getContentType() + "'. დაშვებულია: "
                            + String.join(", ", new java.util.TreeSet<>(ALLOWED_UPLOAD_TYPES.keySet()))));
        }

        if (file.getSize() > MAX_UPLOAD_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "detail", "File exceeds the maximum allowed size of " + MAX_UPLOAD_SIZE_BYTES + " bytes"));
        }

        String uniqueFilename = UUID.randomUUID() + ext;
        Path dir = Path.of(portalProperties.getUploadsDir());
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(uniqueFilename));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("detail", "ფაილის შენახვა ვერ მოხერხდა"));
        }

        AuditLog entry = new AuditLog();
        entry.setAdminId(user.getId());
        entry.setAction("UPLOAD");
        entry.setItemType("file");
        entry.setItemId(0L);
        entry.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(entry);

        return ResponseEntity.ok(new UploadResponse("/uploads/" + uniqueFilename, uniqueFilename));
    }

    private static ResponseEntity<Map<String, String>> requireContentAdmin(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        if (!user.getRole().isContentAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }
}
