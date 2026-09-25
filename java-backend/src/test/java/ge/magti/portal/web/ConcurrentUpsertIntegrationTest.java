package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.FavoriteRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.JwtService;
import ge.magti.portal.util.TbilisiTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Two requests for the same bookmark, or the same read receipt, arriving
 * together -- a double click, or a retry after the first response was lost
 * (an nginx 499 on a POST that the backend still committed).
 *
 * <p>Both writes were documented as "a single atomic statement". On Oracle
 * they are not: under read committed, the second statement's NOT EXISTS (or
 * MERGE ... ON) cannot see the first transaction's uncommitted row, so it
 * attempts the insert, waits on the unique key the first transaction holds,
 * and -- once that transaction commits -- fails with ORA-00001. The caller
 * got a 500 for a bookmark or a receipt that by then existed.
 *
 * <p>Deterministic rather than a thread race: transaction A writes and is
 * held open on a latch while request B runs, so B always meets A's
 * uncommitted key. B's elapsed time is asserted as well -- that is what
 * proves B really waited on A, instead of arriving after the commit and
 * passing for the wrong reason. The rows must be committed for B to be
 * blocked by them, so this class is not {@code @Transactional} and removes
 * its own rows.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentUpsertIntegrationTest {

    /** How long transaction A keeps its row uncommitted while B runs. */
    private static final long HOLD_MILLIS = 2_000;

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired private FavoriteRepository favoriteRepository;
    @Autowired private ArticleReadReceiptRepository readReceiptRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void aDuplicateBookmarkThatWaitedOnTheFirstIsReturnedNotRejected() throws Exception {
        Fixture fixture = createFixture("conc-fav");
        try {
            Outcome second = whileHeldOpen(
                    () -> favoriteRepository.insertIfMissing(fixture.userId(), "article", fixture.articleId()),
                    post("/api/favorites")
                            .header("Authorization", "Bearer " + fixture.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"item_type\":\"article\",\"item_id\":" + fixture.articleId() + "}"));

            assertTrue(second.elapsedMillis() >= HOLD_MILLIS - 250, "B did not wait on A's key: " + second);
            assertEquals(200, second.status(), "B: " + second);
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM favorites WHERE user_id = ? AND item_type = 'article' AND item_id = ?",
                    fixture.userId(), fixture.articleId()));
        } finally {
            removeFixture(fixture);
        }
    }

    @Test
    void aDuplicateReadReceiptThatWaitedOnTheFirstIsRecordedNotRejected() throws Exception {
        Fixture fixture = createFixture("conc-receipt");
        try {
            Outcome second = whileHeldOpen(
                    () -> readReceiptRepository.upsert(fixture.articleId(), fixture.tag(), 1, fixture.userId(),
                            fixture.tag(), fixture.email(), fixture.tag(), TbilisiTime.now()),
                    post("/api/articles/" + fixture.articleId() + "/read-receipt")
                            .header("Authorization", "Bearer " + fixture.token()));

            assertTrue(second.elapsedMillis() >= HOLD_MILLIS - 250, "B did not wait on A's key: " + second);
            assertEquals(200, second.status(), "B: " + second);
            assertEquals(1, count(
                    "SELECT COUNT(*) FROM article_read_receipts WHERE article_id = ? AND operator_id = ?",
                    fixture.articleId(), fixture.userId()));
        } finally {
            removeFixture(fixture);
        }
    }

    /**
     * Runs {@code firstWrite} in transaction A and keeps A open; sends
     * {@code secondRequest} from another thread meanwhile; lets A commit after
     * {@link #HOLD_MILLIS}; returns what B got and how long B took.
     */
    private Outcome whileHeldOpen(Runnable firstWrite, RequestBuilder secondRequest) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> transactionA = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                firstWrite.run();
                written.countDown();
                awaitQuietly(release);
            }));
            assertTrue(written.await(30, TimeUnit.SECONDS), "transaction A never wrote its row");

            Future<Outcome> requestB = threads.submit(() -> {
                long started = System.nanoTime();
                MockHttpServletResponse response = mockMvc.perform(secondRequest).andReturn().getResponse();
                return new Outcome(response.getStatus(), response.getContentAsString(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            });

            Thread.sleep(HOLD_MILLIS);
            release.countDown();
            transactionA.get(30, TimeUnit.SECONDS);
            return requestB.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }

    /** One operator and one article only that operator's department can see, both committed. */
    private Fixture createFixture(String prefix) {
        String tag = prefix + "-" + System.nanoTime();
        return transactionTemplate.execute(status -> {
            User user = new User();
            user.setEmail(tag + "@example.invalid");
            user.setName(tag);
            user.setRole(Role.OPERATOR);
            user.setDepartment(tag);
            user.setActive(true);
            user.setHashedPassword(passwordEncoder.encode("unused"));
            user.setPermissions(Permission.defaultsFor(Role.OPERATOR).stream()
                    .map(Permission::value)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            User savedUser = userRepository.saveAndFlush(user);

            Article article = new Article();
            article.setTitle(tag);
            article.setContent("შინაარსი ტესტისთვის");
            article.setStatus("published");
            article.setDraft(false);
            article.setPublishedAt(TbilisiTime.now().minusDays(1));
            article.setCreatedAt(TbilisiTime.now());
            article.setUpdatedAt(TbilisiTime.now());
            article.setVersion(1);
            Article savedArticle = articleRepository.saveAndFlush(article);

            ArticleTargetDepartment target = new ArticleTargetDepartment();
            target.setArticleId(savedArticle.getId());
            target.setDepartment(tag);
            targetDepartmentRepository.saveAndFlush(target);

            String token = jwtService.createAccessToken(Map.of("sub", savedUser.getEmail(), "role", Role.OPERATOR.value()));
            return new Fixture(tag, savedUser.getId(), savedUser.getEmail(), savedArticle.getId(), token);
        });
    }

    /** Children first: favorites has no cascade, and the receipts would otherwise outlive the article. */
    private void removeFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM favorites WHERE user_id = ?", fixture.userId());
        jdbcTemplate.update("DELETE FROM article_read_receipts WHERE operator_id = ?", fixture.userId());
        jdbcTemplate.update("DELETE FROM articles WHERE id = ?", fixture.articleId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", fixture.userId());
    }

    private long count(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Fixture(String tag, Long userId, String email, Long articleId, String token) {
    }

    private record Outcome(int status, String body, long elapsedMillis) {
    }
}
