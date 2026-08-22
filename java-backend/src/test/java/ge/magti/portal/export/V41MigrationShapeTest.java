package ge.magti.portal.export;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class V41MigrationShapeTest {
    @Test
    void classifiesSensitiveJobsAndIndexesOwnerChecks() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V41__classify_export_jobs.sql"));
        assertTrue(sql.contains("export_family VARCHAR2(50 CHAR)"));
        assertTrue(sql.contains("export_family, owner_user_id, expires_at"));
    }
}
