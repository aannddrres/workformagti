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

/** Atomic save boundary for news plus its mandatory assignment. */
@RestController
public class NewsCommandController {
    private final NewsController news;
    private final ComplianceController compliance;
    private final RequiredReadingRepository requiredReadings;

    public NewsCommandController(
            NewsController news, ComplianceController compliance, RequiredReadingRepository requiredReadings) {
        this.news = news;
        this.compliance = compliance;
        this.requiredReadings = requiredReadings;
    }

    @PostMapping("/api/news/command")
    @Transactional
    public ResponseEntity<?> create(
            @Valid @RequestBody NewsCommandRequest command, @AuthenticationPrincipal User user) {
        return finish(news.createNews(command.news(), user), command, user);
    }

    @PutMapping("/api/news/{id}/command")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id, @Valid @RequestBody NewsCommandRequest command,
            @AuthenticationPrincipal User user) {
        return finish(news.updateNews(id, command.news(), user), command, user);
    }

    private ResponseEntity<?> finish(ResponseEntity<?> result, NewsCommandRequest command, User user) {
        if (!result.getStatusCode().is2xxSuccessful() || !(result.getBody() instanceof NewsResponse saved)) {
            return rollback(result);
        }
        Optional<RequiredReading> existing = requiredReadings
                .findFirstByItemTypeAndItemIdOrderByIdAsc("news", saved.id());
        if (command.mandatory()) {
            if (command.dueDate() == null) {
                return rollback(ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "სავალდებულო მასალას ვადა უნდა ჰქონდეს")));
            }
            String target = command.targetDepartment() == null || command.targetDepartment().isBlank()
                    ? saved.targetDepartment() : command.targetDepartment();
            RequiredReadingRequest reading = new RequiredReadingRequest(
                    "news", saved.id(), target, command.dueDate(), "high");
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
