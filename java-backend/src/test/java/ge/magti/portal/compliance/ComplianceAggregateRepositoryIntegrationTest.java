package ge.magti.portal.compliance;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.compliance.ComplianceAggregateRepository.AggregateRow;
import ge.magti.portal.compliance.ComplianceAggregateRepository.ScopeTarget;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
@Transactional
class ComplianceAggregateRepositoryIntegrationTest {

    @Autowired
    private ComplianceAggregateRepository aggregateRepository;
    @Autowired
    private ComplianceProgressQueryService progressQueryService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void clobScopeKeepsTheOracleResultBoundedToOneRowPerRequestedPair() {
        String suffix = "x".repeat(150);
        List<ScopeTarget> scopes = IntStream.range(0, 1_000)
                .mapToObj(index -> new ScopeTarget(100_000L + index, "scope-" + index + "-" + suffix))
                .toList();

        List<AggregateRow> rows = aggregateRepository.findRelevantCounts(scopes);

        assertEquals(scopes.size(), rows.size());
        assertTrue(rows.stream().allMatch(row -> row.requiredCount() == 0 && row.readCount() == 0));
    }

    @Test
    void exactPrefixAndAllCountsRemainOnTheEstablishedComplianceFormula() {
        User user = new User();
        user.setEmail("aggregate-query@magti.ge");
        user.setName("Aggregate query operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური — ჯგუფი 03");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        user = userRepository.saveAndFlush(user);

        RequiredReading all = reading("All", 1L);
        RequiredReading exact = reading("ტექნიკური — ჯგუფი 03", 2L);
        RequiredReading prefix = reading("ტექნიკური", 3L);
        reading("სხვა", 4L);
        markRead(user, all);
        markRead(user, prefix);

        ReadingProgress progress = progressQueryService.progressByUser(List.of(user)).get(user.getId());

        assertEquals(new ReadingProgress(3, 2, 67), progress);
        assertEquals(0, readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), exact.getId())
                .stream().count());
    }

    private RequiredReading reading(String targetDepartment, Long itemId) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(itemId);
        reading.setTargetDepartment(targetDepartment);
        reading.setDueDate(TbilisiTime.now().plusDays(5));
        reading.setPriority("normal");
        return requiredReadingRepository.saveAndFlush(reading);
    }

    private void markRead(User user, RequiredReading reading) {
        ReadStatus status = new ReadStatus();
        status.setUserId(user.getId());
        status.setRequiredReadingId(reading.getId());
        status.setStatus("read");
        status.setReadAt(TbilisiTime.now());
        status.setOperatorDepartmentSnapshot(user.getDepartment());
        readStatusRepository.saveAndFlush(status);
    }
}
