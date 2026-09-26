package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.User;
import ge.magti.portal.storage.FileStorageService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.storage.FileTypeVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Mirrors routers/platform.py's {@code POST /api/upload} (routers/platform.py:200-266):
 * a single generic attachment endpoint reused by the Article/News/Video admin
 * forms (attachments, dropzone, drag-drop, pasted-image embeds).
 *
 * <p>Same checks as Python, in the same order: MIME allowlist first, then
 * the size cap. The stored extension is derived from the content type, never
 * from the client-supplied filename -- config.py's comment on {@code
 * ALLOWED_UPLOAD_TYPES} explains why: a spoofed filename could otherwise
 * smuggle .html/.svg/.php for stored XSS or arbitrary execution. Spring's
 * {@code spring.servlet.multipart.max-file-size} (see application.yml)
 * already rejects anything over the cap before this method runs, so the
 * explicit {@link MultipartFile#getSize()} check here is a
 * belt-and-suspenders mirror of Python's manual streaming cap, not the only
 * guard.
 *
 * <p><b>SEC-09.</b> This javadoc used to say the content type was
 * "server-detected". It was not: {@code MultipartFile.getContentType()}
 * returns the {@code Content-Type} header the CLIENT wrote in the multipart
 * part, so the allowlist could be walked straight past by declaring
 * {@code image/png} over any bytes at all. The claim has been corrected and
 * the check it described now actually exists --
 * {@link ge.magti.portal.storage.FileTypeVerifier} compares the bytes
 * against the declared type's magic number. It is explicit about the formats
 * it cannot verify (plain text has no signature; the legacy OLE2 Word/Excel
 * formats share one), so "cannot tell" is never silently read as "fine".
 *
 * <p><b>Deliberate divergence from Python (audit PR-03):</b> the bytes go to
 * {@link FileStorageService}, not to {@code portal.uploads-dir}. Python wrote
 * to a local directory and mounted it with {@code StaticFiles}; carrying that
 * over faithfully is what made every restart delete every attachment and made
 * a second replica serve 404s. See that class's javadoc for the options
 * considered.
 */
@RestController
public class UploadController {

    private static final long MAX_UPLOAD_SIZE_BYTES = 10L * 1024 * 1024;

    /** Also what a body over Spring's 11MB transport limit is told (GlobalExceptionHandler). */
    static final String TOO_LARGE_DETAIL = "ფაილის ზომა აღემატება დასაშვებ 10 MiB-ს";

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

    private final FileStorageService fileStorageService;
    private final MutationAuditService mutationAuditService;
    private final PermissionChecker permissionChecker;

    public UploadController(
            FileStorageService fileStorageService, MutationAuditService mutationAuditService,
            PermissionChecker permissionChecker) {
        this.fileStorageService = fileStorageService;
        this.mutationAuditService = mutationAuditService;
        this.permissionChecker = permissionChecker;
    }

    @PostMapping("/api/upload")
    @Transactional
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
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
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("detail", TOO_LARGE_DETAIL));
        }

        String uniqueFilename = UUID.randomUUID() + ext;
        try {
            byte[] content = file.getBytes();
            // SEC-09: the allowlist above only knows what the client SAID
            // this is. Reject bytes that contradict it.
            if (FileTypeVerifier.verify(contentType, content) == FileTypeVerifier.Result.MISMATCH) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of(
                        "detail", "ფაილის შიგთავსი არ შეესაბამება მითითებულ ტიპს '" + contentType + "'"));
            }
            fileStorageService.store(uniqueFilename, contentType, content, user.getId());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("detail", "ფაილის შენახვა ვერ მოხერხდა"));
        }

        mutationAuditService.recordSuccess(
                user, "UPLOAD", "file", 0L, uniqueFilename, null,
                Map.of(
                        "stored_filename", uniqueFilename,
                        "content_type", contentType,
                        "byte_size", file.getSize()));

        return ResponseEntity.ok(new UploadResponse("/uploads/" + uniqueFilename, uniqueFilename));
    }

}
