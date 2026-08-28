package ge.magti.portal.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.StoredFile;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.CategoryRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.StoredFileRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashSet;

import static ge.magti.portal.content.ContentLifecycleService.ItemType.ARTICLE;
import static ge.magti.portal.content.ContentLifecycleService.Status.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
@Transactional
class ContentLifecycleServiceIntegrationTest {

    @Autowired
    private ContentLifecycleService lifecycleService;
    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private StoredFileRepository storedFileRepository;
    @Autowired
    private RequiredReadingRepository requiredReadingRepository;
    @Autowired
    private ReadStatusRepository readStatusRepository;
    @Autowired
    private QuizAttemptRepository quizAttemptRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void trashRestoreAndDuePurgePreserveEvidenceAndRemoveOrphanedAttachmentPayload() throws Exception {
        User admin = user("lifecycle-admin-" + System.nanoTime() + "@magti.ge", Role.SYSTEM_ADMIN);
        User operator = user("lifecycle-operator-" + System.nanoTime() + "@magti.ge", Role.OPERATOR);

        Category category = new Category();
        category.setName("Lifecycle " + System.nanoTime());
        category.setActive(true);
        category = categoryRepository.saveAndFlush(category);

        String filename = "lifecycle-" + System.nanoTime() + ".pdf";
        StoredFile file = new StoredFile();
        file.setFilename(filename);
        file.setContentType("application/pdf");
        file.setByteSize(4);
        file.setUploadedBy(admin.getId());
        file.setCreatedAt(TbilisiTime.now());
        file.setContent(new byte[]{1, 2, 3, 4});
        storedFileRepository.saveAndFlush(file);

        Article article = new Article();
        article.setTitle("აღდგენადი სტატია");
        article.setContent("<p>ტესტი</p><a href=\"/uploads/" + filename + "\">ფაილი</a>");
        article.setAttachmentUrl("/uploads/" + filename);
        article.setCategoryId(category.getId());
        article.setStatus("archived");
        article.setDraft(false);
        article.setVersion(3);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        article = articleRepository.saveAndFlush(article);
        Long articleId = article.getId();

        RequiredReading reading = new RequiredReading();
        reading.setItemType("article");
        reading.setItemId(articleId);
        reading.setItemTitleSnapshot(article.getTitle());
        reading.setTargetDepartment("All");
        reading.setDueDate(TbilisiTime.now().plusDays(1));
        reading = requiredReadingRepository.saveAndFlush(reading);

        ReadStatus read = new ReadStatus();
        read.setUserId(operator.getId());
        read.setRequiredReadingId(reading.getId());
        read.setStatus("read");
        read.setReadAt(TbilisiTime.now());
        readStatusRepository.saveAndFlush(read);

        QuizAttempt attempt = new QuizAttempt();
        attempt.setArticleId(articleId);
        attempt.setArticleIdSnapshot(articleId);
        attempt.setArticleTitleSnapshot(article.getTitle());
        attempt.setArticleVersion(article.getVersion());
        attempt.setUserId(operator.getId());
        attempt.setAttemptNumber(1);
        attempt.setScore(1);
        attempt.setTotalQuestions(1);
        attempt.setPassed(true);
        attempt.setCreatedAt(TbilisiTime.now());
        attempt = quizAttemptRepository.saveAndFlush(attempt);

        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, articleId, admin));
        entityManager.clear();
        assertTrue(articleRepository.findById(articleId).isEmpty(), "ordinary queries must hide trash");
        assertTrue(storedFileRepository.findById(filename).isEmpty(), "trashed attachment must not be served");
        assertEquals(1, lifecycleService.listTrash().stream()
                .filter(item -> item.itemType().equals("article") && item.itemId().equals(articleId)).count());
        AuditLog trashAudit = audit("TRASH", articleId);
        var trashDetails = objectMapper.readTree(trashAudit.getDetails());
        assertEquals(admin.getId(), trashAudit.getAdminId());
        assertEquals(admin.getName(), trashAudit.getAdminNameSnapshot());
        assertEquals(article.getTitle(), trashAudit.getItemNameSnapshot());
        assertEquals("ACTIVE", trashDetails.path("before").path("lifecycle_state").asText());
        assertEquals("TRASHED", trashDetails.path("after").path("lifecycle_state").asText());
        assertEquals(3, trashDetails.path("after").path("version").asInt());
        assertEquals(1, trashDetails.path("after").path("attachment_reference_count").asInt());

        assertEquals(OK, lifecycleService.restore(ARTICLE, articleId, admin));
        entityManager.clear();
        assertTrue(articleRepository.findById(articleId).isPresent());
        assertTrue(storedFileRepository.findById(filename).isPresent());
        var restoreDetails = objectMapper.readTree(audit("RESTORE_FROM_TRASH", articleId).getDetails());
        assertEquals("TRASHED", restoreDetails.path("before").path("lifecycle_state").asText());
        assertEquals("ACTIVE", restoreDetails.path("after").path("lifecycle_state").asText());
        assertTrue(restoreDetails.path("after").path("purge_after").isNull());

        assertEquals(OK, lifecycleService.moveToTrash(ARTICLE, articleId, admin));
        Timestamp due = Timestamp.valueOf(TbilisiTime.now().minusMinutes(1).toLocalDateTime());
        jdbcTemplate.update("UPDATE articles SET purge_after = ? WHERE id = ?", due, articleId);
        jdbcTemplate.update("UPDATE stored_files SET purge_after = ? WHERE filename = ?", due, filename);
        assertEquals(OK, lifecycleService.purge(ARTICLE, articleId, admin));
        entityManager.clear();

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM articles WHERE id = ?", Integer.class, articleId));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stored_files WHERE filename = ?", Integer.class, filename));
        assertEquals(1, requiredReadingRepository.findByItemTypeAndItemId("article", articleId).size());
        assertTrue(readStatusRepository.findByUserIdAndRequiredReadingId(operator.getId(), reading.getId()).isPresent());

        QuizAttempt retained = quizAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertNull(retained.getArticleId());
        assertEquals(articleId, retained.getArticleIdSnapshot());
        assertEquals("აღდგენადი სტატია", retained.getArticleTitleSnapshot());
        var purgeDetails = objectMapper.readTree(audit("PURGE", articleId).getDetails());
        assertEquals("TRASHED", purgeDetails.path("before").path("lifecycle_state").asText());
        assertEquals("PURGED", purgeDetails.path("after").path("lifecycle_state").asText());
        assertEquals("SUCCESS", purgeDetails.path("result").asText());
    }

    private AuditLog audit(String action, Long itemId) {
        return auditLogRepository.findAll().stream()
                .filter(a -> action.equals(a.getAction()) && "article".equals(a.getItemType())
                        && itemId.equals(a.getItemId()))
                .findFirst().orElseThrow();
    }

    private User user(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("Lifecycle test");
        user.setRole(role);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(role).stream()
                .map(Permission::value)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        return userRepository.saveAndFlush(user);
    }
}
