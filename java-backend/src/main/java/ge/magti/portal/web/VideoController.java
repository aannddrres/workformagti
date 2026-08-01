package ge.magti.portal.web;

import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.TagSyncService;
import ge.magti.portal.video.YoutubeUrlNormalizer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/videos.py: listing (department/role-visibility filtered),
 * view-count increment, and admin CRUD (create/update/delete/archive/
 * unarchive). Three distinct access gates, each with Python's exact wire
 * text so client-side error handling keyed on that text keeps working:
 * no/invalid token (401, English), wrong role for create/update/delete
 * (403, English -- security.py's require_roles), missing the granular
 * videos.archive permission (403, Georgian -- security.py's
 * require_permission).
 *
 * <p><b>Known, deliberate gap:</b> Python's create/update/delete get an
 * automatic audit row from audit_trail.py's SQLAlchemy ORM listener --
 * there is no Java equivalent of that cross-cutting mechanism yet (it
 * would need its own increment, covering Article/News/Category too, not
 * just Video). Only archive/unarchive get an explicit audit write here,
 * exactly matching what routers/videos.py's own code explicitly does
 * (log_audit calls, not the automatic listener) -- create/update/delete
 * are not silently audited today on the Java side.
 *
 * <p>Also deliberately not ported: state.py's search_cache.clear() and
 * SSE _notify() calls -- neither TTL caching nor a real-time broadcast
 * mechanism exists in the Java port yet, and nothing depends on them
 * (no cache to go stale, no SSE clients to notify).
 */
@RestController
public class VideoController {

    private static final String NOT_FOUND_DETAIL = "ვიდეო ვერ მოიძებნა";

    private final VideoInstructionRepository videoRepository;
    private final AuditLogRepository auditLogRepository;
    private final PermissionChecker permissionChecker;
    private final TagSyncService tagSyncService;

    public VideoController(
            VideoInstructionRepository videoRepository,
            AuditLogRepository auditLogRepository,
            PermissionChecker permissionChecker,
            TagSyncService tagSyncService) {
        this.videoRepository = videoRepository;
        this.auditLogRepository = auditLogRepository;
        this.permissionChecker = permissionChecker;
        this.tagSyncService = tagSyncService;
    }

    @GetMapping("/api/videos")
    public ResponseEntity<?> getVideos(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<VideoInstruction> videos = user.getRole().isContentAdmin()
                ? videoRepository.findAll()
                : videoRepository.findByArchivedFalse().stream()
                        .filter(v -> DepartmentMatcher.matches(user.getDepartment(), List.of(v.getTargetDepartment())))
                        .toList();

        return ResponseEntity.ok(videos.stream().map(VideoInstructionResponse::from).toList());
    }

    @PostMapping("/api/videos/{id}/view")
    public ResponseEntity<?> viewVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        video.setViewsCount(video.getViewsCount() + 1);
        videoRepository.save(video);
        return ResponseEntity.ok(VideoInstructionResponse.from(video));
    }

    @PostMapping("/api/videos")
    public ResponseEntity<?> createVideo(
            @Valid @RequestBody VideoInstructionRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        VideoInstruction video = new VideoInstruction();
        applyRequest(video, request);
        video.setCreatedAt(TbilisiTime.now());
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        tagSyncService.sync("video", saved.getId(), saved.getTags());

        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @PutMapping("/api/videos/{id}")
    public ResponseEntity<?> updateVideo(
            @PathVariable Long id, @Valid @RequestBody VideoInstructionRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        applyRequest(video, request);
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        tagSyncService.sync("video", saved.getId(), saved.getTags());

        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @DeleteMapping("/api/videos/{id}")
    public ResponseEntity<?> deleteVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        videoRepository.delete(found.get());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/videos/{id}/archive")
    public ResponseEntity<?> archiveVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireVideosArchivePermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        if (video.isArchived()) {
            return ResponseEntity.ok(VideoInstructionResponse.from(video));
        }

        video.setArchived(true);
        writeAuditLog(user.getId(), "ARCHIVE", id);
        VideoInstruction saved = videoRepository.save(video);
        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @PostMapping("/api/videos/{id}/unarchive")
    public ResponseEntity<?> unarchiveVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireVideosArchivePermission(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        if (!video.isArchived()) {
            return ResponseEntity.badRequest().body(Map.of("detail", "ვიდეო არ არის არქივში"));
        }

        video.setArchived(false);
        writeAuditLog(user.getId(), "UNARCHIVE", id);
        VideoInstruction saved = videoRepository.save(video);
        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    private void applyRequest(VideoInstruction video, VideoInstructionRequest request) {
        video.setTitle(request.title());
        video.setVideoUrl(YoutubeUrlNormalizer.normalize(request.videoUrl()));
        video.setCategory(request.category());
        video.setTargetDepartment(request.targetDepartmentOrDefault());
        video.setTags(request.tags());
    }

    private void writeAuditLog(Long adminId, String action, Long videoId) {
        AuditLog log = new AuditLog();
        log.setAdminId(adminId);
        log.setAction(action);
        log.setItemType("video");
        log.setItemId(videoId);
        log.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(log);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireContentAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!user.getRole().isContentAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> requireVideosArchivePermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.VIDEOS_ARCHIVE)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }
}
