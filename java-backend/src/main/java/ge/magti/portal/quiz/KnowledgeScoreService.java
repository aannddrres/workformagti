package ge.magti.portal.quiz;

import ge.magti.portal.repository.QuizAttemptRepository;
import org.springframework.stereotype.Service;

/**
 * Port of _compute_knowledge_score (routers/articles.py:877-892), shared by
 * the personal score endpoint and the leaderboard exactly like the Python
 * source shares it. Tunable constants (+10 per distinct passed article
 * version, +5 bonus for passing on the very first attempt), not
 * architecturally load-bearing -- same as the Python docstring says.
 */
@Service
public class KnowledgeScoreService {

    private static final int POINTS_PER_ARTICLE_PASSED = 10;
    private static final int FIRST_TRY_BONUS = 5;

    private final QuizAttemptRepository quizAttemptRepository;

    public KnowledgeScoreService(QuizAttemptRepository quizAttemptRepository) {
        this.quizAttemptRepository = quizAttemptRepository;
    }

    public KnowledgeScoreResult compute(Long userId) {
        var summary = quizAttemptRepository.summarizePassedByArticleVersion(userId);
        int articlesPassed = Math.toIntExact(summary.getArticlesPassed().longValue());
        int firstTryPasses = Math.toIntExact(summary.getFirstTryPasses().longValue());
        int score = Math.addExact(
                Math.multiplyExact(POINTS_PER_ARTICLE_PASSED, articlesPassed),
                Math.multiplyExact(FIRST_TRY_BONUS, firstTryPasses));
        return new KnowledgeScoreResult(score, articlesPassed, firstTryPasses);
    }
}
