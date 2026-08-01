package ge.magti.portal.repository;

import ge.magti.portal.domain.UserNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserNoteRepository extends JpaRepository<UserNote, Long> {

    Optional<UserNote> findByUserIdAndArticleId(Long userId, Long articleId);
}
