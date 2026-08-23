package ge.magti.portal.web;

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

import java.time.Duration;
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
 * <p><b>Requires a logged-in employee (D-4, 2026-08-22).</b> This path used
 * to be public, which meant a copied {@code /uploads/<uuid>} URL opened an
 * internal attachment for anyone who had it -- forwarded, pasted into a
 * chat, or left in a browser history. UUID filenames made that unguessable,
 * not protected, and the product owner decided the difference matters.
 *
 * <p>The guard is written the same way every other controller writes it,
 * rather than left to {@code SecurityConfig}'s deny-by-default floor alone.
 * Two reasons: the access contract records a gate per endpoint and an
 * endpoint whose only gate is the filter chain has an empty cell, and a
 * caller reaching a 401 from the handler gets the same {@code detail} body
 * as every other denial in the API.
 *
 * <p><b>Inline images keep working, and that is not luck.</b> An
 * {@code <img src="/uploads/...">} inside an article body is a browser-native
 * request: no Angular interceptor runs, so no bearer header is attached. It
 * authenticates by the httpOnly {@code access_token} cookie login sets on
 * path {@code /}, which the browser sends on a same-origin subresource
 * request. That holds while Angular and this API share an origin. A
 * split-origin deployment would need the cookie's SameSite policy revisited
 * or token-aware loading in the frontend -- which is why the origin question
 * is the last bullet of question 9 in docs/QUESTIONS_FOR_IT.md.
 */
@RestController
public class UploadedFileController {

    private final FileStorageService fileStorageService;

    public UploadedFileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/uploads/{filename}")
    public ResponseEntity<?> serve(
            @AuthenticationPrincipal User user, @PathVariable("filename") String filename) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<FileStorageService.StoredContent> found = fileStorageService.load(filename);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ფაილი ვერ მოიძებნა"));
        }
        FileStorageService.StoredContent content = found.get();
        return ResponseEntity.ok()
                .contentType(parseOrOctetStream(content.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                // Filenames are UUIDs, so the bytes behind one never change --
                // the same caching the static handler gave us, stated instead
                // of inherited.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(content.content());
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static MediaType parseOrOctetStream(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
