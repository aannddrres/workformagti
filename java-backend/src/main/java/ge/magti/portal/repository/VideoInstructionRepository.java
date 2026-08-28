package ge.magti.portal.repository;

import ge.magti.portal.domain.VideoInstruction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoInstructionRepository extends JpaRepository<VideoInstruction, Long> {

    /** Mirrors routers/videos.py:78's is_archived == False leg of the visibility
     *  filter -- the department half is applied in Java via DepartmentMatcher. */
    List<VideoInstruction> findByArchivedFalse(Pageable pageable);
}
