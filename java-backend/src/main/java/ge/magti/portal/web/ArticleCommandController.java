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

/** Atomic boundary for the content editor. */
@RestController
public class ArticleCommandController {
    private final ArticleController articles;
    private final ComplianceController compliance;
    private final QuizController quizzes;
    private final RequiredReadingRepository requiredReadings;

    public ArticleCommandController(
            ArticleController articles, ComplianceController compliance, QuizController quizzes,
            RequiredReadingRepository requiredReadings) {
        this.articles = articles;
        this.compliance = compliance;
        this.quizzes = quizzes;
        this.requiredReadings = requiredReadings;
    }

    @PostMapping("/api/articles/command")
    @Transactional
    public ResponseEntity<?> create(
            @Valid @RequestBody ArticleCommandRequest command, @AuthenticationPrincipal User user) {
        return finish(articles.createArticle(command.article(), user), command, user);
    }

    @PutMapping("/api/articles/{id}/command")
    @Transactional
    public ResponseEntity<?> update(
            @PathVariable Long id, @Valid @RequestBody ArticleCommandRequest command,
            @AuthenticationPrincipal User user) {
        return finish(articles.updateArticle(id, command.article(), user), command, user);
    }

    private ResponseEntity<?> finish(
            ResponseEntity<?> articleResult, ArticleCommandRequest command, User user) {
        if (!articleResult.getStatusCode().is2xxSuccessful()
                || !(articleResult.getBody() instanceof ArticleResponse article)) {
            return rollback(articleResult);
        }
        Optional<RequiredReading> existing = requiredReadings
                .findFirstByItemTypeAndItemIdOrderByIdAsc("article", article.id());
        if (command.mandatory()) {
            if (command.dueDate() == null) {
                return rollback(ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "სავალდებულო მასალას ვადა უნდა ჰქონდეს")));
            }
            String target = command.targetDepartment() == null || command.targetDepartment().isBlank()
                    ? command.article().legacyTargetDepartment() : command.targetDepartment();
            RequiredReadingRequest reading = new RequiredReadingRequest(
                    "article", article.id(), target, command.dueDate(), "high");
            ResponseEntity<?> readingResult = existing.isPresent()
                    ? compliance.updateRequiredReading(existing.get().getId(), reading, user)
                    : compliance.createRequiredReading(reading, user);
            if (!readingResult.getStatusCode().is2xxSuccessful()) return rollback(readingResult);
        } else if (existing.isPresent()) {
            ResponseEntity<?> readingResult = compliance.deleteRequiredReading(existing.get().getId(), user);
            if (!readingResult.getStatusCode().is2xxSuccessful()) return rollback(readingResult);
        }
        if (command.article().quizEnabledOrDefault()) {
            if (command.quiz() == null) {
                return rollback(ResponseEntity.unprocessableEntity()
                        .body(Map.of("detail", "ჩართული ქვიზისთვის კითხვები სავალდებულოა")));
            }
            ResponseEntity<?> quizResult = quizzes.updateArticleQuizAdmin(article.id(), command.quiz(), user);
            if (!quizResult.getStatusCode().is2xxSuccessful()) return rollback(quizResult);
        }
        return articleResult;
    }

    private ResponseEntity<?> rollback(ResponseEntity<?> response) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        return response;
    }
}
