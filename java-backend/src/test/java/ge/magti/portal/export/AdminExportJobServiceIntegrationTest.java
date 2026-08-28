package ge.magti.portal.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.ExportJobRepository;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
@Transactional
class AdminExportJobServiceIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private AdminExportJobService service;
    @Autowired private ExportJobRepository jobs;
    @Autowired private AuditLogRepository audits;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void jobClassificationAndAuditEvidenceCommitAsOneUnit() throws Exception {
        User admin = new User();
        admin.setEmail("admin-export-job@magti.ge");
        admin.setName("ექსპორტის ადმინი");
        admin.setRole(Role.SYSTEM_ADMIN);
        admin.setActive(true);
        admin.setHashedPassword(passwordEncoder.encode("unused"));
        admin = users.saveAndFlush(admin);

        String jobId = service.register(admin, AdminExportFamily.SEARCH_HISTORY, null, null, 7);

        ExportJob job = jobs.findById(jobId).orElseThrow();
        assertEquals(admin.getId(), job.getOwnerUserId());
        assertEquals("ADMIN_SEARCH_HISTORY", job.getExportFamily());
        AuditLog audit = audits.findAll().stream().max(Comparator.comparing(AuditLog::getId)).orElseThrow();
        assertEquals("EXPORT_ADMIN_SEARCH_HISTORY", audit.getAction());
        assertEquals(admin.getId(), audit.getAdminId());
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals(1, details.path("schema_version").asInt());
        assertEquals("SUCCESS", details.path("result").asText());
        assertTrue(details.path("before").isNull());
        assertEquals(7, details.path("after").path("row_count").asInt());
        assertEquals("processing", details.path("after").path("status").asText());
        assertTrue(audit.getDetails().contains(jobId));
    }
}
