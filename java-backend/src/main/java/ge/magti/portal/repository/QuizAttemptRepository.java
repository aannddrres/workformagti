package ge.magti.portal.repository;

import ge.magti.portal.domain.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    int countByArticleIdAndArticleVersionAndUserId(Long articleId, int articleVersion, Long userId);

    /**
     * Port of _compute_knowledge_score's grouped query
     * (routers/articles.py:879-888): one row per distinct (article,
     * version) this user has ever passed, with the earliest attempt
     * number that passed it. Object[] = {articleId (Long), articleVersion
     * (Integer), minAttemptNumber (Integer)}.
     */
    @Query("SELECT qa.articleId, qa.articleVersion, MIN(qa.attemptNumber) FROM QuizAttempt qa "
            + "WHERE qa.userId = :userId AND qa.passed = true "
            + "GROUP BY qa.articleId, qa.articleVersion")
    List<Object[]> findPassedGroupedByArticleVersion(@Param("userId") Long userId);
}
