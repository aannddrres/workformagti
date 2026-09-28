package ge.magti.portal.web;

import ge.magti.portal.RequiresOracle;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.QuizQuestion;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.QuizAnswerRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.QuizQuestionRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
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
 * Two submissions of the same quiz by the same person, at once -- a double
 * click on "submit", or a retry after the first response was lost.
 *
 * <p>The attempt number was {@code COUNT(*) + 1} over committed attempts, and
 * quiz_attempts has no unique key on it (V26). A second submission that
 * counts while the first is still uncommitted gets the same number, and the
 * compliance record then shows two "attempt 1"s.
 *
 * <p>Transaction A stands for a submission already in flight: it holds its
 * submitter's users row -- the serialisation a submission has to take -- and
 * an uncommitted attempt 1, while request B runs. B's submission is audited,
 * and audit_logs keeps a foreign key to users, so the submitter cannot be
 * deleted afterwards; the fixture is deactivated instead and everything else
 * is removed.
 */
@RequiresOracle
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentQuizAttemptIntegrationTest {

    /** How long transaction A keeps its attempt uncommitted while B runs. */
    private static final long HOLD_MILLIS = 2_000;

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleTargetDepartmentRepository targetDepartmentRepository;
    @Autowired private QuizQuestionRepository quizQuestionRepository;
    @Autowired private QuizAnswerRepository quizAnswerRepository;
    @Autowired private QuizAttemptRepository quizAttemptRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void twoSubmissionsInFlightGetTwoAttemptNumbers() throws Exception {
        Fixture fixture = createFixture("conc-quiz-" + System.nanoTime());
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> transactionA = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                userRepository.findByIdForUpdate(fixture.userId()).orElseThrow();
                QuizAttempt first = new QuizAttempt();
                first.setArticleId(fixture.articleId());
                first.setArticleIdSnapshot(fixture.articleId());
                first.setArticleTitleSnapshot(fixture.tag());
                first.setArticleVersion(1);
                first.setUserId(fixture.userId());
                first.setAttemptNumber(1);
                first.setScore(1);
                first.setTotalQuestions(1);
                first.setPassed(true);
                first.setCreatedAt(TbilisiTime.now());
                quizAttemptRepository.saveAndFlush(first);
                written.countDown();
                awaitQuietly(release);
            }));
            assertTrue(written.await(30, TimeUnit.SECONDS), "transaction A never wrote its attempt");

            Future<long[]> requestB = threads.submit(() -> {
                long started = System.nanoTime();
                MockHttpServletResponse response = mockMvc.perform(
                                post("/api/articles/" + fixture.articleId() + "/quiz/attempt")
                                        .header("Authorization", "Bearer " + fixture.token())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"answers\":{\"" + fixture.questionId() + "\":"
                                                + fixture.correctAnswerId() + "}}"))
                        .andReturn().getResponse();
                return new long[] {response.getStatus(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)};
            });

            Thread.sleep(HOLD_MILLIS);
            release.countDown();
            transactionA.get(30, TimeUnit.SECONDS);
            long[] b = requestB.get(30, TimeUnit.SECONDS);

            assertEquals(200, b[0], "request B status");
            List<Integer> numbers = jdbcTemplate.queryForList(
                    "SELECT attempt_number FROM quiz_attempts WHERE article_id = ? AND article_version = 1"
                            + " AND user_id = ? ORDER BY attempt_number",
                    Integer.class, fixture.articleId(), fixture.userId());
            assertEquals(List.of(1, 2), numbers, "attempt numbers recorded for one person and one quiz");
            assertTrue(b[1] >= HOLD_MILLIS - 250, "B must have queued behind A, took " + b[1] + " ms");
        } finally {
            release.countDown();
            threads.shutdownNow();
            removeFixture(fixture);
        }
    }

    /** One operator, and one published article with a one-question quiz only that operator's department sees. */
    private Fixture createFixture(String tag) {
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
            article.setQuizEnabled(true);
            article.setPublishedAt(TbilisiTime.now().minusDays(1));
            article.setCreatedAt(TbilisiTime.now());
            article.setUpdatedAt(TbilisiTime.now());
            article.setVersion(1);
            Article savedArticle = articleRepository.saveAndFlush(article);

            ArticleTargetDepartment target = new ArticleTargetDepartment();
            target.setArticleId(savedArticle.getId());
            target.setDepartment(tag);
            targetDepartmentRepository.saveAndFlush(target);

            QuizQuestion question = new QuizQuestion();
            question.setArticleId(savedArticle.getId());
            question.setQuestionText("კითხვა");
            QuizQuestion savedQuestion = quizQuestionRepository.saveAndFlush(question);

            QuizAnswer answer = new QuizAnswer();
            answer.setQuestionId(savedQuestion.getId());
            answer.setAnswerText("პასუხი");
            answer.setCorrect(true);
            QuizAnswer savedAnswer = quizAnswerRepository.saveAndFlush(answer);

            String token = jwtService.createAccessToken(Map.of("sub", savedUser.getEmail(), "role", Role.OPERATOR.value()));
            return new Fixture(tag, savedUser.getId(), savedArticle.getId(), savedQuestion.getId(), savedAnswer.getId(), token);
        });
    }

    /** Attempts first (no cascade from users); the article cascades to its quiz. */
    private void removeFixture(Fixture fixture) {
        jdbcTemplate.update("DELETE FROM quiz_attempts WHERE user_id = ?", fixture.userId());
        jdbcTemplate.update("DELETE FROM articles WHERE id = ?", fixture.articleId());
        jdbcTemplate.update("UPDATE users SET is_active = 0 WHERE id = ?", fixture.userId());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Fixture(String tag, Long userId, Long articleId, Long questionId, Long correctAnswerId, String token) {
    }
}
