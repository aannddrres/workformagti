package ge.magti.portal.repository;

import ge.magti.portal.domain.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    int countByArticleIdAndArticleVersionAndUserId(Long articleId, int articleVersion, Long userId);

    /**
     * The number the next attempt is recorded under, counted while the
     * submitter's users row is locked.
     *
     * <p>{@code COUNT(*) + 1} alone let two submissions in flight both count
     * the committed attempts and both record "attempt 1": quiz_attempts has
     * no unique key on the number (V26), and before a person's first attempt
     * there is no quiz_attempts row to lock. The submitter's users row is the
     * per-person mutex instead -- the row UserController and
     * ReminderService.sendManual already lock -- held until the submission
     * commits, so the second one counts after the first is visible
     * (ConcurrentQuizAttemptIntegrationTest). Only that one person's
     * submissions queue.
     */
    default int nextAttemptNumber(Long articleId, int articleVersion, Long userId) {
        lockSubmitter(userId);
        return countByArticleIdAndArticleVersionAndUserId(articleId, articleVersion, userId) + 1;
    }

    /** Taken only by {@link #nextAttemptNumber}; released when the submission's transaction ends. */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Number lockSubmitter(@Param("userId") Long userId);

    /**
     * The submitter's newest attempts on one article version, newest first:
     * what QuizCooldown reads (PO-55). Taken after {@link #nextAttemptNumber}
     * has locked the submitter, so two submissions cannot both see a free slot.
     */
    List<QuizAttempt> findTop3ByArticleIdAndArticleVersionAndUserIdOrderByCreatedAtDescIdDesc(
            Long articleId, int articleVersion, Long userId);

    /** Port of _check_quiz_gate's pass-check (routers/articles.py:1022-1027). */
    boolean existsByArticleIdAndArticleVersionAndUserIdAndPassedTrue(Long articleId, int articleVersion, Long userId);

    /**
     * Port of _compute_knowledge_score (routers/articles.py:879-888),
     * reduced to the two scalar values required by the response. Oracle
     * performs the per-(article, version) grouping so retained attempt
     * history never becomes an unbounded Java collection.
     */
    @Query(value = """
            SELECT COUNT(*) AS "articlesPassed",
                   COALESCE(SUM(CASE WHEN grouped.min_attempt_number = 1 THEN 1 ELSE 0 END), 0)
                       AS "firstTryPasses"
            FROM (
                SELECT qa.article_id,
                       qa.article_version,
                       MIN(qa.attempt_number) AS min_attempt_number
                FROM quiz_attempts qa
                WHERE qa.user_id = :userId
                  AND qa.passed = 1
                GROUP BY qa.article_id, qa.article_version
            ) grouped
            """, nativeQuery = true)
    PassedQuizSummary summarizePassedByArticleVersion(@Param("userId") Long userId);
}
