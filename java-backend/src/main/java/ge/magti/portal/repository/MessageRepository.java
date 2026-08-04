package ge.magti.portal.repository;

import ge.magti.portal.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /** Recipient's inbox -- get_my_messages (routers/messaging.py:166-168) will reuse this; used now to verify the required-reading notification fan-out. */
    List<Message> findByUserId(Long userId);
}
