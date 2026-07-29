package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReadStatusTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        ReadStatus status = new ReadStatus();

        assertEquals("unread", status.getStatus());
        assertNull(status.getReadAt());
    }
}
