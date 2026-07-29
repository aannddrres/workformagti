package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequiredReadingTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        RequiredReading reading = new RequiredReading();

        assertEquals("All", reading.getTargetDepartment());
        assertEquals("normal", reading.getPriority());
    }
}
