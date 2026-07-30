package ge.magti.portal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExportJobTest {

    @Test
    void newInstanceMatchesModelsPyColumnDefaults() {
        ExportJob job = new ExportJob();

        assertEquals("processing", job.getStatus());
        assertNull(job.getPath());
        assertEquals(0.0, job.getExpiresAt());
    }
}
