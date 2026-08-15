package ge.magti.portal.web;

import ge.magti.portal.storage.FileStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * <p>Access is unchanged too: this path was public before (SecurityConfig
 * permits every request and the resource handler ran outside the filter
 * chain's authorization anyway) and stays public, so that an attachment in an
 * article renders the same way it does today. Whether attachments <i>should</i>
 * require a token is a separate question from where the bytes live, and
 * quietly answering it here would have been an undeclared behaviour change.
 */
@RestController
public class UploadedFileController {

    private final FileStorageService fileStorageService;

    public UploadedFileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/uploads/{filename}")
    public ResponseEntity<?> serve(@PathVariable("filename") String filename) {
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

    private static MediaType parseOrOctetStream(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
