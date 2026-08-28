package ge.magti.portal.repository;

import ge.magti.portal.domain.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    int countByArticleIdAndArticleVersionAndUserId(Long articleId, int articleVersion, Long userId);

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
