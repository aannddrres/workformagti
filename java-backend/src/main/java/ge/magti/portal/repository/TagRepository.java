package ge.magti.portal.repository;

import ge.magti.portal.domain.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    /** Mirrors get_tags' order_by(models.Tag.name) (routers/platform.py:281). */
    List<Tag> findAllByOrderByName();
}
