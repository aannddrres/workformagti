package ge.magti.portal.quiz;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.QuizAttemptRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The quiz gate. It is called from BOTH "mark as read" paths: the
 * article read-receipt ({@code createArticleReadReceipt}) and the mandatory-
 * reading compliance acknowledgement ({@code ComplianceController.markRead}).
 * A single definition here means the gate can never drift between those two
 * call sites.
 *
 * <p>Admins/content_admins bypass the gate entirely (they never see the ack
 * button on the frontend either); a quiz-disabled article is never gated.
 */
@Service
public class QuizGateChecker {

    private static final String QUIZ_REQUIRED_DETAIL =
            "საჭიროა ქვიზის წარმატებით ჩაბარება წაკითხვის დასადასტურებლად";

    private final QuizAttemptRepository quizAttemptRepository;

    public QuizGateChecker(QuizAttemptRepository quizAttemptRepository) {
        this.quizAttemptRepository = quizAttemptRepository;
    }

    /**
     * @return a 403 denial {@code ResponseEntity} when the article has a quiz
     * the user hasn't passed at the current version, or {@code null} when the
     * gate is satisfied (or doesn't apply).
     */
    public ResponseEntity<Map<String, String>> denialFor(Article article, User user) {
        if (user.getRole().isContentAdmin()) {
            return null;
        }
        if (!article.isQuizEnabled()) {
            return null;
        }
        boolean passed = quizAttemptRepository.existsByArticleIdAndArticleVersionAndUserIdAndPassedTrue(
                article.getId(), article.getVersion(), user.getId());
        if (!passed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", QUIZ_REQUIRED_DETAIL));
        }
        return null;
    }
}
