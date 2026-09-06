package ge.magti.portal.web;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.QuizQuestion;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.quiz.KnowledgeScoreResult;
import ge.magti.portal.quiz.KnowledgeScoreService;
import ge.magti.portal.quiz.QuizGradeResult;
import ge.magti.portal.quiz.QuizGrader;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.QuizAnswerRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.QuizQuestionRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mirrors routers/articles.py's quiz + knowledge sub-surface: admin
 * question-bank CRUD, the operator-facing quiz (no {@code is_correct}
 * anywhere), server-side grading, and the two knowledge-score read
 * endpoints that share {@link KnowledgeScoreService}.
 *
 * <p>{@link #assertArticleVisible} is duplicated from {@link
 * ArticleController} rather than shared, matching this migration's
 * established per-controller pattern (each Python router file
 * independently wires its own dependencies too).
 *
 * <p><b>Known, deliberate gap:</b> only the admin question-bank replace
 * (PUT quiz/admin) writes an audit row (UPDATE_QUIZ), exactly matching
 * what routers/articles.py's own code explicitly does -- no other quiz
 * endpoint is audited in Python either.
 */
@RestController
public class QuizController {

    private static final String ARTICLE_NOT_FOUND_DETAIL = "სტატია ვერ მოიძებნა";

    private final ArticleRepository articleRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizAnswerRepository quizAnswerRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final MutationAuditService mutationAuditService;
    private final KnowledgeScoreService knowledgeScoreService;
    private final PermissionChecker permissionChecker;

    public QuizController(
            ArticleRepository articleRepository,
            ArticleTargetQueryService articleTargetQueryService,
            QuizQuestionRepository quizQuestionRepository,
            QuizAnswerRepository quizAnswerRepository,
            QuizAttemptRepository quizAttemptRepository,
            MutationAuditService mutationAuditService,
            KnowledgeScoreService knowledgeScoreService,
            PermissionChecker permissionChecker) {
        this.articleRepository = articleRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.quizQuestionRepository = quizQuestionRepository;
        this.quizAnswerRepository = quizAnswerRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.mutationAuditService = mutationAuditService;
        this.knowledgeScoreService = knowledgeScoreService;
        this.permissionChecker = permissionChecker;
    }

    @GetMapping("/api/articles/{id}/quiz/admin")
    public ResponseEntity<?> getArticleQuizAdmin(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        if (articleRepository.findById(id).isEmpty()) {
            return articleNotFound();
        }
        return ResponseEntity.ok(buildAdminQuizView(id));
    }

    @PutMapping("/api/articles/{id}/quiz/admin")
    @Transactional
    public ResponseEntity<?> updateArticleQuizAdmin(
            @PathVariable Long id, @RequestBody QuizAdminUpdate payload, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireContentManage(user, permissionChecker);
        if (denial != null) {
            return denial;
        }
        if (articleRepository.findById(id).isEmpty()) {
            return articleNotFound();
        }

        List<QuizQuestionAdminDto> questions = payload.questions();
        if (questions == null || questions.isEmpty()) {
            return unprocessable("ქვიზს უნდა ჰქონდეს მინიმუმ ერთი კითხვა");
        }
        CompleteResultGuard.enforceSize(questions.size());
        long totalAnswers = 0;
        for (QuizQuestionAdminDto question : questions) {
            if (question.answers() == null || question.answers().size() < 2) {
                return unprocessable("ყოველ კითხვას უნდა ჰქონდეს მინიმუმ 2 პასუხი");
            }
            totalAnswers += question.answers().size();
            if (totalAnswers > CompleteResultGuard.MAX_ROWS) {
                throw new CompleteResultGuard.CompleteResultCardinalityExceededException();
            }
            long correctCount = question.answers().stream().filter(QuizAnswerAdminDto::isCorrect).count();
            if (correctCount != 1) {
                return unprocessable("ყოველ კითხვას უნდა ჰქონდეს ზუსტად ერთი სწორი პასუხი");
            }
        }

        Map<String, Object> before = quizSnapshot(id);

        // Full replace: delete existing questions (cascades to answers at
        // the DB level -- quiz_answers.question_id is ON DELETE CASCADE),
        // insert the new set.
        quizQuestionRepository.deleteByArticleId(id);
        for (int qi = 0; qi < questions.size(); qi++) {
            QuizQuestionAdminDto questionDto = questions.get(qi);
            QuizQuestion question = new QuizQuestion();
            question.setArticleId(id);
            question.setQuestionText(questionDto.questionText());
            question.setPosition(qi);
            QuizQuestion savedQuestion = quizQuestionRepository.saveAndFlush(question);

            List<QuizAnswerAdminDto> answers = questionDto.answers();
            for (int ai = 0; ai < answers.size(); ai++) {
                QuizAnswerAdminDto answerDto = answers.get(ai);
                QuizAnswer answer = new QuizAnswer();
                answer.setQuestionId(savedQuestion.getId());
                answer.setAnswerText(answerDto.answerText());
                answer.setCorrect(answerDto.isCorrect());
                answer.setPosition(ai);
                quizAnswerRepository.save(answer);
            }
        }

        quizQuestionRepository.flush();
        quizAnswerRepository.flush();
        mutationAuditService.recordSuccess(
                user, "UPDATE_QUIZ", "article", id, "Article quiz #" + id,
                before, quizSnapshot(id));

        return ResponseEntity.ok(buildAdminQuizView(id));
    }

    private Map<String, Object> quizSnapshot(Long articleId) {
        List<QuizQuestion> questions = CompleteResultGuard.enforce(
                quizQuestionRepository.findByArticleIdOrderByPosition(
                        articleId, CompleteResultGuard.sentinelPage()));
        List<Long> questionIds = questions.stream().map(QuizQuestion::getId).toList();
        List<QuizAnswer> answers = questionIds.isEmpty()
                ? List.of() : CompleteResultGuard.enforce(
                        quizAnswerRepository.findByQuestionIdIn(
                                questionIds, CompleteResultGuard.sentinelPage()));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("question_count", questions.size());
        snapshot.put("answer_count", answers.size());
        snapshot.put("correct_answer_count", answers.stream().filter(QuizAnswer::isCorrect).count());
        return snapshot;
    }

    @GetMapping("/api/articles/{id}/quiz")
    public ResponseEntity<?> getArticleQuiz(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return articleNotFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, user);
        if (visibility != null) {
            return visibility;
        }
        if (!article.isQuizEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ამ სტატიას კვიზი არ აქვს"));
        }

        List<QuizQuestionPublicDto> questions = loadQuestionsWithAnswers(id).stream()
                .map(QuizQuestionPublicDto::from)
                .toList();
        return ResponseEntity.ok(new QuizPublicResponse(id, article.getVersion(), questions));
    }

    @PostMapping("/api/articles/{id}/quiz/attempt")
    @Transactional
    public ResponseEntity<?> submitArticleQuizAttempt(
            @PathVariable Long id, @RequestBody QuizAttemptSubmitRequest payload, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        Optional<Article> found = articleRepository.findById(id);
        if (found.isEmpty()) {
            return articleNotFound();
        }
        Article article = found.get();
        ResponseEntity<Map<String, String>> visibility = assertArticleVisible(article, user);
        if (visibility != null) {
            return visibility;
        }
        if (!article.isQuizEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "ამ სტატიას კვიზი არ აქვს"));
        }

        List<QuizQuestion> questions = loadQuestionsWithAnswers(id);
        if (questions.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("detail", "ამ სტატიას კვიზის კითხვები არ აქვს"));
        }

        QuizGradeResult grade = QuizGrader.grade(questions, payload.answersOrEmpty());
        int attemptNumber = quizAttemptRepository
                .countByArticleIdAndArticleVersionAndUserId(id, article.getVersion(), user.getId()) + 1;

        QuizAttempt attempt = new QuizAttempt();
        attempt.setArticleId(id);
        attempt.setArticleIdSnapshot(id);
        attempt.setArticleTitleSnapshot(article.getTitle());
        attempt.setArticleVersion(article.getVersion());
        attempt.setUserId(user.getId());
        attempt.setAttemptNumber(attemptNumber);
        attempt.setScore(grade.score());
        attempt.setTotalQuestions(grade.totalQuestions());
        attempt.setPassed(grade.passed());
        attempt.setCreatedAt(TbilisiTime.now());
        QuizAttempt savedAttempt = quizAttemptRepository.saveAndFlush(attempt);
        mutationAuditService.recordSuccess(
                user, "SUBMIT_QUIZ_ATTEMPT", "quiz_attempt", savedAttempt.getId(),
                article.getTitle(), null, MutationAuditService.quizAttemptSnapshot(savedAttempt));

        return ResponseEntity.ok(new QuizAttemptResultResponse(
                grade.passed(), grade.score(), grade.totalQuestions(), grade.wrongQuestionIds(), attemptNumber));
    }

    @GetMapping("/api/users/me/knowledge-score")
    public ResponseEntity<?> getMyKnowledgeScore(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        KnowledgeScoreResult result = knowledgeScoreService.compute(user.getId());
        return ResponseEntity.ok(KnowledgeScoreResponse.from(user.getId(), result));
    }

    // GET /api/knowledge-leaderboard was REMOVED here (access contract D-1,
    // decided 2026-08-21).
    //
    // It returned user_id, user_name, department and knowledge score for every
    // eligible person in the caller's department to ANY authenticated
    // operator -- no capability, no leadership assignment. Plan §8 says only a
    // SYSTEM_ADMIN bypass or an active leadership assignment may produce a
    // scope over other employees' data, and a colleague's quiz performance is
    // exactly that. It also appeared in no confirmed product document, so
    // there was no product decision holding it up.
    //
    // Phase 2 forced the question rather than deferring it: the endpoint's
    // scope="team" branch reads users.team_id, which is populated on zero rows
    // today and therefore silently falls through to the department branch. The
    // V36 backfill fills that column, so the branch would have started working
    // for the first time as a side effect of a schema migration.
    //
    // The owner's call was that a leaderboard is not a feature they need. The
    // personal score above stays -- it is the caller's own data.

    private List<QuizQuestion> loadQuestionsWithAnswers(Long articleId) {
        List<QuizQuestion> questions = CompleteResultGuard.enforce(
                quizQuestionRepository.findByArticleIdOrderByPosition(
                        articleId, CompleteResultGuard.sentinelPage()));
        if (questions.isEmpty()) {
            return questions;
        }
        Set<Long> questionIds = questions.stream().map(QuizQuestion::getId).collect(Collectors.toSet());
        Map<Long, List<QuizAnswer>> answersByQuestion = CompleteResultGuard.enforce(
                        quizAnswerRepository.findByQuestionIdIn(
                                questionIds, CompleteResultGuard.sentinelPage())).stream()
                .collect(Collectors.groupingBy(QuizAnswer::getQuestionId));
        questions.forEach(q -> q.setAnswers(answersByQuestion.getOrDefault(q.getId(), List.of())));
        return questions;
    }

    private QuizAdminUpdate buildAdminQuizView(Long articleId) {
        List<QuizQuestionAdminDto> dtos = loadQuestionsWithAnswers(articleId).stream()
                .map(QuizQuestionAdminDto::from)
                .toList();
        return new QuizAdminUpdate(dtos);
    }

    /** Port of _assert_article_visible (routers/articles.py:61-94), duplicated from ArticleController. */
    private ResponseEntity<Map<String, String>> assertArticleVisible(Article article, User user) {
        if (user.getRole().isContentAdmin()) {
            return null;
        }
        List<String> targetDepartments =
                articleTargetQueryService.targetDepartmentsForArticleWithinLimit(article.getId());
        if (!DepartmentMatcher.matches(user.getDepartment(), targetDepartments)) {
            return articleNotFoundMap();
        }
        if ("published".equals(article.getStatus())) {
            return null;
        }
        if ("scheduled".equals(article.getStatus()) && article.getPublishedAt() != null
                && !article.getPublishedAt().isAfter(TbilisiTime.now())) {
            return null;
        }
        return articleNotFoundMap();
    }

    private static ResponseEntity<?> articleNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ARTICLE_NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> articleNotFoundMap() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ARTICLE_NOT_FOUND_DETAIL));
    }

    private static ResponseEntity<Map<String, String>> unprocessable(String detail) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", detail));
    }

}
