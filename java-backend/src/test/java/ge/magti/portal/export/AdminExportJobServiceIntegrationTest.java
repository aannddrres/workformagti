package ge.magti.portal.export;

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

    @Autowired private AdminExportJobService service;
    @Autowired private ExportJobRepository jobs;
    @Autowired private AuditLogRepository audits;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void jobClassificationAndAuditEvidenceCommitAsOneUnit() {
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
        assertTrue(audit.getDetails().contains("\"row_count\":7"));
        assertTrue(audit.getDetails().contains(jobId));
    }
}
