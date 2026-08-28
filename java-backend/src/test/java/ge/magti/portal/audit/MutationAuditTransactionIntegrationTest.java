package ge.magti.portal.audit;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
class MutationAuditTransactionIntegrationTest {

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MutationAuditService auditService;

    @Test
    void databaseAuditFailureRollsBackTheBusinessMutation() {
        String categoryName = "audit-rollback-" + System.nanoTime();

        assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            User actor = new User();
            actor.setEmail(categoryName + "@example.invalid");
            actor.setName("Audit rollback test");
            actor.setRole(Role.CONTENT_ADMIN);
            actor.setDepartment("All");
            actor.setActive(true);
            actor.setHashedPassword(passwordEncoder.encode("unused"));
            actor.setPermissions(Permission.defaultsFor(Role.CONTENT_ADMIN).stream()
                    .map(Permission::value)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
            User savedActor = userRepository.saveAndFlush(actor);

            Category category = new Category();
            category.setName(categoryName);
            category.setSlug(categoryName);
            category.setActive(true);
            Category saved = categoryRepository.saveAndFlush(category);

            auditService.recordSuccess(
                    savedActor,
                    "X".repeat(51), // audit_logs.action is VARCHAR2(50): force a real Oracle write failure
                    "category",
                    saved.getId(),
                    saved.getName(),
                    null,
                    Map.of("name", saved.getName(), "active", true));
        }));

        assertTrue(categoryRepository.findFirstByNameIgnoreCaseAndActiveTrue(categoryName).isEmpty(),
                "the category must not survive a failed audit write");
    }
}
