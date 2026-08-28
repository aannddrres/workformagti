package ge.magti.portal.repository;

import ge.magti.portal.domain.Team;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TeamRepository extends JpaRepository<Team, Long> {

    /** Mirrors create_team's duplicate-name check (routers/users.py:356). */
    Optional<Team> findByName(String name);

    /** Mirrors get_teams' ordering (routers/users.py:346). */
    List<Team> findAllByOrderByName(Pageable pageable);

    /** Group names repeat between departments since V36 dropped uq_teams_name. */
    Optional<Team> findByDepartmentIdAndName(Long departmentId, String name);

    /**
     * Security-sensitive legacy path resolution asks for two rows so the
     * V36-to-V37 duplicate window fails closed instead of picking one target.
     */
    List<Team> findByDepartmentIdAndNameAndActiveTrue(Long departmentId, String name, Pageable pageable);

    List<Team> findByDepartmentId(Long departmentId);

    List<Team> findByDepartmentIdIn(Collection<Long> departmentIds, Pageable pageable);
}
