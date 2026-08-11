package ge.magti.portal.repository;

import ge.magti.portal.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Recipient's inbox, unordered -- used to verify the required-reading notification fan-out. */
    List<Message> findByUserId(Long userId);

    /** Mirrors get_my_messages (routers/messaging.py:166-168). */
    List<Message> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Mirrors get_sent_messages (routers/messaging.py:180-182). */
    List<Message> findBySenderIdOrderByCreatedAtDesc(Long senderId);

    /** Mirrors mark_message_read/delete_message's ownership-scoped lookup (routers/messaging.py:261-264, 295-298). */
    Optional<Message> findByIdAndUserId(Long id, Long userId);

    /** Mirrors notifications-summary's unread-envelope-badge count (routers/platform.py:188-191). */
    long countByUserIdAndReadFalse(Long userId);
}
