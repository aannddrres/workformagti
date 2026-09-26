package ge.magti.portal.quiz;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A6: an attempt number belongs to one attempt per person, article and
 * version. QuizController numbers attempts under the submitter's lock
 * (ConcurrentQuizAttemptIntegrationTest); this is the database refusing the
 * pair as well, whatever writes it.
 */
@RequiresOracle
@SpringBootTest
@Transactional
class QuizAttemptNumberConstraintIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private ArticleRepository articles;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void aSecondAttemptWithTheSameNumberIsRefused() {
        User user = user("a6-duplicate");
        Article article = article("A6 სტატია");

        insertAttempt(user, article.getId(), 1);
        assertThrows(DataIntegrityViolationException.class, () -> insertAttempt(user, article.getId(), 1));
        insertAttempt(user, article.getId(), 2);
        assertEquals(2, count(user));
    }

    /**
     * Existing duplicates are compliance evidence from before the lock, and
     * are kept: the constraint is enforced for new rows without validating
     * the old ones.
     */
    @Test
    void theConstraintIsEnforcedWithoutValidatingHistory() {
        Map<String, Object> constraint = jdbc.queryForMap(
                "SELECT status, validated FROM user_constraints WHERE constraint_name = 'UQ_QUIZ_ATTEMPTS_ATTEMPT_NO'");
        assertEquals("ENABLED", constraint.get("STATUS"));
        assertEquals("NOT VALIDATED", constraint.get("VALIDATED"));
    }

    /**
     * V42 made article_id ON DELETE SET NULL, so a purge nulls it on every
     * attempt of that article. Keyed on article_id, two purged articles'
     * first attempts would collide and the purge would fail; the key is the
     * immutable snapshot instead.
     */
    @Test
    void purgingTwoArticlesDoesNotMakeTheirAttemptsCollide() {
        User user = user("a6-purge");
        Article first = article("A6 პირველი");
        Article second = article("A6 მეორე");
        insertAttempt(user, first.getId(), 1);
        insertAttempt(user, second.getId(), 1);

        assertEquals(2, jdbc.update("UPDATE quiz_attempts SET article_id = NULL WHERE user_id = ?", user.getId()));
    }

    private void insertAttempt(User user, Long articleId, int attemptNumber) {
        jdbc.update("INSERT INTO quiz_attempts (article_id, article_id_snapshot, article_title_snapshot, "
                        + "article_version, user_id, attempt_number, score, total_questions, passed, created_at) "
                        + "VALUES (?, ?, 'A6', 1, ?, ?, 1, 2, 0, ?)",
                articleId, articleId, user.getId(), attemptNumber,
                Timestamp.valueOf(TbilisiTime.now().toLocalDateTime()));
    }

    private int count(User user) {
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM quiz_attempts WHERE user_id = ?",
                Integer.class, user.getId());
        return rows == null ? 0 : rows;
    }

    private User user(String local) {
        User user = new User();
        user.setEmail(local + "@magti.ge");
        user.setName(local);
        user.setRole(Role.OPERATOR);
        user.setDepartment("All");
        user.setActive(true);
        user.setHashedPassword(passwordEncoder.encode("unused"));
        user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                .map(Permission::value).collect(Collectors.toCollection(LinkedHashSet::new)));
        return users.saveAndFlush(user);
    }

    private Article article(String title) {
        Article article = new Article();
        article.setTitle(title);
        article.setContent("შინაარსი");
        article.setStatus("published");
        article.setDraft(false);
        article.setVersion(1);
        article.setCreatedAt(TbilisiTime.now());
        article.setUpdatedAt(TbilisiTime.now());
        return articles.saveAndFlush(article);
    }
}
