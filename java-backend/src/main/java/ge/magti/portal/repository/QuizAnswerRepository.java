package ge.magti.portal.repository;

import ge.magti.portal.domain.QuizAnswer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuizAnswerRepository extends JpaRepository<QuizAnswer, Long> {

    // QuizQuestion.answers is @Transient -- batch-loaded and grouped by
    // questionId by the caller, same reasoning as Article.targetDepartments.
    List<QuizAnswer> findByQuestionIdIn(Collection<Long> questionIds, Pageable pageable);
}
