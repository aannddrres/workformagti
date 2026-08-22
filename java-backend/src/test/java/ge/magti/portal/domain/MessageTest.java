package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        Message message = new Message();

        assertFalse(message.isRead());
        assertNull(message.getSenderId());
    }
}
