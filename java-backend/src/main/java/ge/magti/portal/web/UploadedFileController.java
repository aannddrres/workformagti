package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.User;
import ge.magti.portal.storage.FileStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Serves {@code /uploads/<uuid>.<ext>} out of {@link FileStorageService}.
 *
 * <p>Replaces {@code WebConfig}'s {@code /uploads/**} static-resource handler,
 * which could only ever read the local filesystem -- the pod-local storage
 * PR-03 is about. The URL shape is unchanged, so every {@code
 * articles.attachment_url} and every inline {@code <img src>} already in the
 * database keeps resolving.
 *
 * <p>Attachments are private portal resources. A copied URL without an
 * authenticated portal session returns 401, successful access is audited,
 * and responses are not stored in shared or browser caches.
 */
@RestController
public class UploadedFileController {

    private final FileStorageService fileStorageService;
    private final MutationAuditService mutationAuditService;

    public UploadedFileController(FileStorageService fileStorageService, MutationAuditService mutationAuditService) {
        this.fileStorageService = fileStorageService;
        this.mutationAuditService = mutationAuditService;
    }

    @GetMapping("/uploads/{filename}")
    public ResponseEntity<?> serve(
            @PathVariable("filename") String filename,
            @AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        Optional<FileStorageService.StoredContent> found = fileStorageService.load(filename);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ფაილი ვერ მოიძებნა"));
        }
        FileStorageService.StoredContent content = found.get();
        recordAccess(user, filename, content);
        return ResponseEntity.ok()
                .contentType(parseOrOctetStream(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(content.content());
    }

    private void recordAccess(
            User user, String filename, FileStorageService.StoredContent content) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("stored_filename", filename);
        after.put("content_type", content.contentType());
        after.put("byte_size", content.content().length);
        mutationAuditService.recordSuccess(
                user, "FILE_ACCESS", "stored_file", 0L, filename,
                null, after);
    }

    private static MediaType parseOrOctetStream(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
