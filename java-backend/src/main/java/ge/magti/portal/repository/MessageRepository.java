package ge.magti.portal.repository;

import ge.magti.portal.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Interim durable reminder rows, until the dedicated R3 reminder model replaces Message. */
    List<Message> findByUserId(Long userId);

    /** Interim unread reminder badge count used by the notification summary. */
    long countByUserIdAndReadFalse(Long userId);
}
