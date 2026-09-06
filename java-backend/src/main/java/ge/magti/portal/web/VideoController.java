package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.content.ContentLifecycleService;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.search.SearchReindexService;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.storage.FileReferenceIndex;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.TagSyncService;
import ge.magti.portal.video.YoutubeUrlNormalizer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
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
 * <p>Create/update/archive/unarchive write reconstructable audit evidence in
 * the same transaction as the business mutation. Delete delegates the same
 * fail-closed rule to {@link ContentLifecycleService}.
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
    private final PermissionChecker permissionChecker;
    private final TagSyncService tagSyncService;
    private final SearchReindexService searchReindexService;
    private final ContentLifecycleService contentLifecycleService;
    private final MutationAuditService contentMutationAuditService;
    private final FileReferenceIndex fileReferenceIndex;

    public VideoController(
            VideoInstructionRepository videoRepository,
            PermissionChecker permissionChecker,
            TagSyncService tagSyncService,
            SearchReindexService searchReindexService,
            ContentLifecycleService contentLifecycleService,
            MutationAuditService contentMutationAuditService,
            FileReferenceIndex fileReferenceIndex) {
        this.videoRepository = videoRepository;
        this.permissionChecker = permissionChecker;
        this.tagSyncService = tagSyncService;
        this.searchReindexService = searchReindexService;
        this.contentLifecycleService = contentLifecycleService;
        this.contentMutationAuditService = contentMutationAuditService;
        this.fileReferenceIndex = fileReferenceIndex;
    }

    @GetMapping("/api/videos")
    public ResponseEntity<?> getVideos(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<VideoInstruction> videos = user.getRole().isContentAdmin()
                ? CompleteResultGuard.enforce(
                        videoRepository.findAll(CompleteResultGuard.sentinelPage()).getContent())
                : CompleteResultGuard.enforce(
                        videoRepository.findByArchivedFalse(CompleteResultGuard.sentinelPage())).stream()
                        .filter(v -> DepartmentMatcher.matches(user.getDepartment(), List.of(v.getTargetDepartment())))
                        .toList();

        return ResponseEntity.ok(videos.stream().map(VideoInstructionResponse::from).toList());
    }

    @PostMapping("/api/videos/{id}/view")
    public ResponseEntity<?> viewVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        if (!isVideoVisibleTo(user, video)) {
            return notFound();
        }
        video.setViewsCount(video.getViewsCount() + 1);
        videoRepository.save(video);
        return ResponseEntity.ok(VideoInstructionResponse.from(video));
    }

    @PostMapping("/api/videos")
    @Transactional
    public ResponseEntity<?> createVideo(
            @Valid @RequestBody VideoInstructionRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        VideoInstruction video = new VideoInstruction();
        applyRequest(video, request);
        video.setCreatedAt(TbilisiTime.now());
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        tagSyncService.sync("video", saved.getId(), saved.getTags());
        searchReindexService.reindexVideo(saved);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("video", saved.getId(), saved.getVideoUrl());
        contentMutationAuditService.recordSuccess(
                user, "CREATE", "video", saved.getId(), saved.getTitle(), null,
                MutationAuditService.videoSnapshot(saved));

        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @PutMapping("/api/videos/{id}")
    @Transactional
    public ResponseEntity<?> updateVideo(
            @PathVariable Long id, @Valid @RequestBody VideoInstructionRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        VideoInstruction video = found.get();
        Map<String, Object> before = MutationAuditService.videoSnapshot(video);
        applyRequest(video, request);
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        tagSyncService.sync("video", saved.getId(), saved.getTags());
        searchReindexService.reindexVideo(saved);
        // DEC-P01: keep stored_file_references in step with what this
        // content now points at, in the same transaction as the save.
        fileReferenceIndex.sync("video", saved.getId(), saved.getVideoUrl());
        contentMutationAuditService.recordSuccess(
                user, "UPDATE", "video", saved.getId(), saved.getTitle(), before,
                MutationAuditService.videoSnapshot(saved));

        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @DeleteMapping("/api/videos/{id}")
    @Transactional
    public ResponseEntity<?> deleteVideo(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }

        Optional<VideoInstruction> found = videoRepository.findById(id);
        if (found.isEmpty()) {
            return notFound();
        }
        ContentLifecycleService.Status status = contentLifecycleService.moveToTrash(
                ContentLifecycleService.ItemType.VIDEO, id, user);
        if (status == ContentLifecycleService.Status.OK) {
            searchReindexService.remove(SearchReindexService.VIDEO, id);
            return ResponseEntity.noContent().build();
        }
        return ContentTrashController.response(status, "ვიდეო სანაგვეში გადავიდა");
    }

    @PostMapping("/api/videos/{id}/archive")
    @Transactional
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

        Map<String, Object> before = MutationAuditService.videoSnapshot(video);
        video.setArchived(true);
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        contentMutationAuditService.recordSuccess(
                user, "ARCHIVE", "video", saved.getId(), saved.getTitle(), before,
                MutationAuditService.videoSnapshot(saved));
        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    @PostMapping("/api/videos/{id}/unarchive")
    @Transactional
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

        Map<String, Object> before = MutationAuditService.videoSnapshot(video);
        video.setArchived(false);
        VideoInstruction saved = videoRepository.saveAndFlush(video);
        contentMutationAuditService.recordSuccess(
                user, "UNARCHIVE", "video", saved.getId(), saved.getTitle(), before,
                MutationAuditService.videoSnapshot(saved));
        return ResponseEntity.ok(VideoInstructionResponse.from(saved));
    }

    private void applyRequest(VideoInstruction video, VideoInstructionRequest request) {
        video.setTitle(request.title());
        video.setVideoUrl(YoutubeUrlNormalizer.normalize(request.videoUrl()));
        video.setCategory(request.category());
        video.setTargetDepartment(request.targetDepartmentOrDefault());
        video.setTags(request.tags());
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", NOT_FOUND_DETAIL));
    }

    private static boolean isVideoVisibleTo(User user, VideoInstruction video) {
        return user.getRole().isContentAdmin()
                || (!video.isArchived()
                && DepartmentMatcher.matches(user.getDepartment(), List.of(video.getTargetDepartment())));
    }

    private ResponseEntity<Map<String, String>> requireVideosArchivePermission(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
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
