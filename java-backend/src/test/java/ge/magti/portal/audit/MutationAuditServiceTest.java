package ge.magti.portal.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MutationAuditServiceTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MutationAuditService service =
            new MutationAuditService(auditLogRepository);

    @Test
    void recordSuccessCapturesActorTargetResultAndSnapshots() throws Exception {
        User actor = actor();

        service.recordSuccess(
                actor, "UPDATE", "category", 41L, "განახლებული კატეგორია",
                Map.of("name", "ძველი კატეგორია", "active", true),
                Map.of("name", "განახლებული კატეგორია", "active", true));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        AuditLog audit = captor.getValue();
        assertEquals(actor.getId(), audit.getAdminId());
        assertEquals(actor.getName(), audit.getAdminNameSnapshot());
        assertEquals(actor.getEmail(), audit.getAdminEmailSnapshot());
        assertEquals("UPDATE", audit.getAction());
        assertEquals("category", audit.getItemType());
        assertEquals(41L, audit.getItemId());
        assertEquals("განახლებული კატეგორია", audit.getItemNameSnapshot());

        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals(1, details.path("schema_version").asInt());
        assertEquals("SUCCESS", details.path("result").asText());
        assertEquals("ძველი კატეგორია", details.path("before").path("name").asText());
        assertEquals("განახლებული კატეგორია", details.path("after").path("name").asText());
    }

    @Test
    void auditPersistenceFailurePropagatesToTheCallingTransaction() {
        doThrow(new DataIntegrityViolationException("simulated audit constraint failure"))
                .when(auditLogRepository).saveAndFlush(any(AuditLog.class));

        assertThrows(DataIntegrityViolationException.class, () -> service.recordSuccess(
                actor(), "CREATE", "category", 42L, "კატეგორია", null,
                Map.of("name", "კატეგორია", "active", true)));
    }

    @Test
    void userSnapshotKeepsSecurityStateWithoutCredentialOrContactValues() {
        User user = actor();
        user.setPhone("555123456");
        user.setHashedPassword("never-copy-this-hash");
        user.setTokenVersion(17L);

        String snapshot = MutationAuditService.userSnapshot(user).toString();

        assertFalse(snapshot.contains(user.getEmail()));
        assertFalse(snapshot.contains(user.getPhone()));
        assertFalse(snapshot.contains(user.getHashedPassword()));
        assertFalse(snapshot.contains("token_version"));
        assertEquals(true, MutationAuditService.userSnapshot(user).get("phone_present"));
    }

    @Test
    void recordResultCapturesFailureReasonAndRequestContext() throws Exception {
        service.recordResult(
                actor(), "LOGIN_FAILED", "user", 7L, "აუდიტორი",
                "FAILURE", "AUTHENTICATION_REJECTED", null,
                Map.of("authenticated", false), "192.0.2.10", "audit-test-agent");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        AuditLog audit = captor.getValue();
        JsonNode details = objectMapper.readTree(audit.getDetails());
        assertEquals("FAILURE", details.path("result").asText());
        assertEquals("AUTHENTICATION_REJECTED", details.path("reason").asText());
        assertEquals("192.0.2.10", audit.getIpAddress());
        assertEquals("audit-test-agent", audit.getUserAgent());
    }

    private static User actor() {
        User actor = new User();
        actor.setId(7L);
        actor.setName("აუდიტორი");
        actor.setEmail("auditor@example.invalid");
        actor.setRole(Role.SYSTEM_ADMIN);
        return actor;
    }
}
