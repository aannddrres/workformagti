package ge.magti.portal.compliance;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.compliance.ComplianceAggregateRepository.AggregateRow;
import ge.magti.portal.compliance.ComplianceAggregateRepository.ScopeTarget;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
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
import java.util.Set;
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
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository articleTargetDepartmentRepository;

    @Test
    void clobScopeKeepsTheOracleResultBoundedToOneRowPerRequestedPair() {
        String suffix = "x".repeat(150);
        List<ScopeTarget> scopes = IntStream.range(0, 1_000)
                .mapToObj(index -> new ScopeTarget(100_000L + index, "scope-" + index + "-" + suffix))
                .toList();

        List<AggregateRow> rows = aggregateRepository.findRelevantCounts(scopes, Set.of());

        assertEquals(scopes.size(), rows.size());
        assertTrue(rows.stream().allMatch(row -> row.requiredCount() == 0 && row.readCount() == 0));
    }

    @Test
    void exactPrefixAndAllCountsRemainOnTheEstablishedComplianceFormula() {
        User user = operator("aggregate-query@magti.ge");

        RequiredReading all = reading("All", openArticle().getId());
        RequiredReading exact = reading("ტექნიკური — ჯგუფი 03", openArticle().getId());
        RequiredReading prefix = reading("ტექნიკური", openArticle().getId());
        reading("სხვა", openArticle().getId());
        markRead(user, all);
        markRead(user, prefix);

        ReadingProgress progress = progressQueryService.progressByUser(List.of(user)).get(user.getId());

        assertEquals(new ReadingProgress(3, 2, 67), progress);
        assertEquals(0, readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), exact.getId())
                .stream().count());
    }

    /**
     * PO-40: a reading nobody in its target can open -- an archived article,
     * one still in draft, one that does not exist -- counts neither as owed
     * nor, where someone confirmed it earlier, as read.
     */
    @Test
    void readingsOutOfForceCountNeitherAsOwedNorAsRead() {
        User user = operator("aggregate-out-of-force@magti.ge");
        RequiredReading open = reading("ტექნიკური", openArticle().getId());
        Article archived = openArticle();
        archived.setStatus("archived");
        articleRepository.saveAndFlush(archived);
        RequiredReading onArchived = reading("ტექნიკური", archived.getId());
        Article draft = openArticle();
        draft.setStatus("draft");
        articleRepository.saveAndFlush(draft);
        reading("ტექნიკური", draft.getId());
        reading("ტექნიკური", 987_654_321L);
        markRead(user, onArchived);

        ReadingProgress progress = progressQueryService.progressByUser(List.of(user)).get(user.getId());

        assertEquals(new ReadingProgress(1, 0, 0), progress);
        assertEquals(1, readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), onArchived.getId())
                .stream().count(), "the earlier confirmation stays evidence");
        assertEquals(0, readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), open.getId())
                .stream().count());
    }

    private Article openArticle() {
        return OpenMaterial.article(articleRepository, articleTargetDepartmentRepository, "ღია სტატია", "All");
    }

    private User operator(String email) {
        User user = new User();
        user.setEmail(email);
        user.setName("Aggregate query operator");
        user.setRole(Role.OPERATOR);
        user.setDepartment("ტექნიკური — ჯგუფი 03");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
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
