package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.config.PortalProperties;
import ge.magti.portal.domain.User;
import ge.magti.portal.storage.FileAccessPolicy;
import ge.magti.portal.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(UploadedFileController.class);

    private final FileStorageService fileStorageService;
    private final MutationAuditService mutationAuditService;
    private final FileAccessPolicy fileAccessPolicy;
    private final PortalProperties properties;

    public UploadedFileController(
            FileStorageService fileStorageService,
            MutationAuditService mutationAuditService,
            FileAccessPolicy fileAccessPolicy,
            PortalProperties properties) {
        this.fileStorageService = fileStorageService;
        this.mutationAuditService = mutationAuditService;
        this.fileAccessPolicy = fileAccessPolicy;
        this.properties = properties;
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

        // DEC-P01. Authentication alone used to be the whole check, which is
        // how an ოფისი operator could download a ტექნიკური article's picture
        // (UAT F-1). The rule is now "something you may read references it".
        FileAccessPolicy.Decision decision = fileAccessPolicy.decide(filename, user);
        if (!decision.allowed()) {
            if (properties.getRollout().isFileEntitlementEnabled()) {
                // Same 404 the article itself answers. A 403 would confirm the
                // file exists, which is the one thing a caller guessing names
                // is trying to learn.
                recordDecision(user, filename, decision, "FILE_ACCESS_DENIED", "DENIED");
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "ფაილი ვერ მოიძებნა"));
            }
            // Shadow: say what would have happened, serve the file anyway.
            //
            // Recorded in the audit log as well as the application log, under
            // an action of its own so a query can never mistake "would have
            // refused" for "did refuse". The log line alone is not enough to
            // run this on production: the app runs as several replicas, the
            // line is INFO among everything else, and whoever decides to
            // promote the flag needs a countable answer to "did shadow deny
            // anyone legitimate", not a grep across pods.
            recordDecision(user, filename, decision, "FILE_ACCESS_SHADOW_DENY", "SHADOW");
            logger.info("File entitlement (shadow) would deny [{}] {} for user {} ({})",
                    decision, filename, user.getId(), user.getDepartment());
        }

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

    /**
     * A refusal is audited as deliberately as a success: a run of these for
     * one person is the shape a scraped-URL attempt would take, and there is
     * nowhere else it would be visible.
     *
     * <p>Shadow uses the same row with a different action and result, so the
     * two are one query apart and never one filter apart. The department is
     * written into the payload because the review question is about groups of
     * people ("is one department losing pictures?"), and a user's department
     * can have changed by the time anyone reads the row.
     */
    private void recordDecision(
            User user,
            String filename,
            FileAccessPolicy.Decision decision,
            String action,
            String result) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("stored_filename", filename);
        after.put("decision", decision.name());
        after.put("department", user.getDepartment());
        mutationAuditService.recordResult(
                user, action, "stored_file", 0L, filename,
                result, decision.name(), null, after, null, null);
    }

    private static MediaType parseOrOctetStream(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
