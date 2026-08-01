package ge.magti.portal.repository;

import ge.magti.portal.domain.UserNote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserNoteRepository extends JpaRepository<UserNote, Long> {
}
