package ge.magti.portal.repository;

import ge.magti.portal.domain.QuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    List<QuizQuestion> findByArticleIdOrderByPosition(Long articleId);

    // A derived delete (unlike a bulk @Modifying @Query) fetches then
    // removes each row through the persistence context, so it stays
    // consistent with whatever the same transaction reads afterward --
    // no clearAutomatically/stale-cache trap to worry about here.
    void deleteByArticleId(Long articleId);
}
