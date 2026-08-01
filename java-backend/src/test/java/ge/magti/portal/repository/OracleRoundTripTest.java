package ge.magti.portal.repository;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.ArticleTargetDepartmentId;
import ge.magti.portal.domain.AuditCategory;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.ExportJob;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.Team;
import ge.magti.portal.domain.User;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips real rows through the real Oracle instance (localhost:1521/
 * orclpdb1) -- schema validation alone only proves column shapes line up,
 * not that TbilisiTimestampConverter/RoleConverter/PermissionsConverter and
 * VARCHAR2(n CHAR) Georgian text actually survive a real write+read.
 *
 * <p>{@code @Transactional} rolls every test back afterward so the dev
 * database doesn't accumulate test rows.
 */
@SpringBootTest
@Transactional
class OracleRoundTripTest {

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private ArticleTargetDepartmentRepository articleTargetDepartmentRepository;
    @Autowired
    private ExportJobRepository exportJobRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void teamSurvivesGeorgianNameRoundTrip() {
        Team team = new Team();
        team.setName("მხარდაჭერის გუნდი");
        team.setCreatedAt(TbilisiTime.now());

        Team saved = teamRepository.saveAndFlush(team);
        Team reloaded = teamRepository.findById(saved.getId()).orElseThrow();

        assertEquals("მხარდაჭერის გუნდი", reloaded.getName());
        assertEquals(TbilisiTime.OFFSET, reloaded.getCreatedAt().getOffset());
    }

    @Test
    void categorySelfReferencingParentSurvivesRoundTrip() {
        Category parent = new Category();
        parent.setName("ტექნიკური საკითხები");
        Category savedParent = categoryRepository.saveAndFlush(parent);

        Category child = new Category();
        child.setName("ინტერნეტი");
        child.setParentId(savedParent.getId());
        Category savedChild = categoryRepository.saveAndFlush(child);

        Category reloaded = categoryRepository.findById(savedChild.getId()).orElseThrow();
        assertEquals(savedParent.getId(), reloaded.getParentId());
    }

    @Test
    void userRoleAndPermissionsAndDepartmentSurviveRoundTrip() {
        User user = new User();
        user.setEmail("round.trip.test@magti.ge");
        user.setName("გიორგი ტესტაშვილი");
        user.setDepartment("ტექნიკური მხარდაჭერა — ჯგუფი 3");
        user.setRole(Role.CONTENT_ADMIN);
        user.setPermissions(Set.of("articles.publish", "users.manage"));
        user.setLastActive(TbilisiTime.now());

        userRepository.saveAndFlush(user);
        User reloaded = userRepository.findByEmail("round.trip.test@magti.ge").orElseThrow();

        assertEquals("გიორგი ტესტაშვილი", reloaded.getName());
        assertEquals("ტექნიკური მხარდაჭერა — ჯგუფი 3", reloaded.getDepartment());
        assertEquals(Role.CONTENT_ADMIN, reloaded.getRole());
        assertEquals(Set.of("articles.publish", "users.manage"), reloaded.getPermissions());
        assertTrue(reloaded.isActive());
        assertEquals(TbilisiTime.OFFSET, reloaded.getLastActive().getOffset());
    }

    @Test
    void articleClobContentAndCompositeKeyJunctionSurviveRoundTrip() {
        Category category = new Category();
        category.setName("ტესტ კატეგორია");
        Category savedCategory = categoryRepository.saveAndFlush(category);

        Article article = new Article();
        article.setTitle("როგორ დავაყენოთ როუტერი");
        article.setContent("დიდი, მრავალაბზაციანი შიგთავსი ქართულად...".repeat(50));
        article.setCategoryId(savedCategory.getId());
        Article savedArticle = articleRepository.saveAndFlush(article);

        ArticleTargetDepartment targeting = new ArticleTargetDepartment();
        targeting.setArticleId(savedArticle.getId());
        targeting.setDepartment("ტექნიკური მხარდაჭერა");
        articleTargetDepartmentRepository.saveAndFlush(targeting);

        Article reloadedArticle = articleRepository.findById(savedArticle.getId()).orElseThrow();
        assertEquals("როგორ დავაყენოთ როუტერი", reloadedArticle.getTitle());
        assertTrue(reloadedArticle.getContent().startsWith("დიდი, მრავალაბზაციანი"));

        ArticleTargetDepartment reloadedTargeting = articleTargetDepartmentRepository
                .findById(new ArticleTargetDepartmentId(savedArticle.getId(), "ტექნიკური მხარდაჭერა"))
                .orElseThrow();
        assertEquals("ტექნიკური მხარდაჭერა", reloadedTargeting.getDepartment());
    }

    @Test
    void exportJobStringIdAndEpochDoubleSurviveRoundTrip() {
        ExportJob job = new ExportJob();
        job.setId("11111111-2222-3333-4444-555555555555");
        job.setStatus("completed");
        job.setPath("/exports/report.xlsx");
        job.setExpiresAt(1_800_000_000.5);

        exportJobRepository.saveAndFlush(job);
        ExportJob reloaded = exportJobRepository.findById("11111111-2222-3333-4444-555555555555").orElseThrow();

        assertEquals("completed", reloaded.getStatus());
        assertEquals(1_800_000_000.5, reloaded.getExpiresAt());
    }

    @Test
    void auditLogCategoryEnumSurvivesRoundTrip() {
        User admin = new User();
        admin.setEmail("audit.round.trip@magti.ge");
        admin.setName("ადმინისტრატორი");
        User savedAdmin = userRepository.saveAndFlush(admin);

        AuditLog log = new AuditLog();
        log.setAdminId(savedAdmin.getId());
        log.setAction("LOGIN");
        log.setItemType("user");
        log.setItemId(savedAdmin.getId());
        log.setTimestamp(TbilisiTime.now());
        log.setCategory(AuditCategory.SECURITY);

        AuditLog saved = auditLogRepository.saveAndFlush(log);
        AuditLog reloaded = auditLogRepository.findById(saved.getId()).orElseThrow();

        assertEquals(AuditCategory.SECURITY, reloaded.getCategory());
        assertEquals("LOGIN", reloaded.getAction());
    }
}
