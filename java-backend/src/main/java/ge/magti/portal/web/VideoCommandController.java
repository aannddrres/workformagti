package ge.magti.portal.web;

import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.RequiredReadingRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/** Atomic save boundary for video plus its mandatory assignment. */
@RestController
public class VideoCommandController {
    private final VideoController videos;
    private final ComplianceController compliance;
    private final RequiredReadingRepository requiredReadings;

    public VideoCommandController(
            VideoController videos, ComplianceController compliance, RequiredReadingRepository requiredReadings) {
        this.videos = videos;
        this.compliance = compliance;
        this.requiredReadings = requiredReadings;
    }

    @PostMapping("/api/videos/command")
    @Transactional
    public ResponseEntity<?> create(
            @Valid @RequestBody VideoCommandRequest command, @AuthenticationPrincipal User user) {
        return finish(videos.createVideo(command.video(), user), command, user);
    }

    @PutMapping("/api/videos/{id}/command")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id, @Valid @RequestBody VideoCommandRequest command,
            @AuthenticationPrincipal User user) {
        return finish(videos.updateVideo(id, command.video(), user), command, user);
    }

    private ResponseEntity<?> finish(ResponseEntity<?> result, VideoCommandRequest command, User user) {
        if (!result.getStatusCode().is2xxSuccessful()
                || !(result.getBody() instanceof VideoInstructionResponse saved)) {
            return rollback(result);
        }
        Optional<RequiredReading> existing = requiredReadings
                .findFirstByItemTypeAndItemIdOrderByIdAsc("video", saved.id());
        if (command.mandatory()) {
            if (command.dueDate() == null) {
                return rollback(ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "სავალდებულო მასალას ვადა უნდა ჰქონდეს")));
            }
            String target = command.targetDepartment() == null || command.targetDepartment().isBlank()
                    ? saved.targetDepartment() : command.targetDepartment();
            RequiredReadingRequest reading = new RequiredReadingRequest(
                    "video", saved.id(), target, command.dueDate(), "high");
            ResponseEntity<?> readingResult = existing.isPresent()
                    ? compliance.updateRequiredReading(existing.get().getId(), reading, user)
                    : compliance.createRequiredReading(reading, user);
            if (!readingResult.getStatusCode().is2xxSuccessful()) return rollback(readingResult);
        } else if (existing.isPresent()) {
            ResponseEntity<?> readingResult = compliance.deleteRequiredReading(existing.get().getId(), user);
            if (!readingResult.getStatusCode().is2xxSuccessful()) return rollback(readingResult);
        }
        return result;
    }

    private ResponseEntity<?> rollback(ResponseEntity<?> response) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return response;
    }
}
