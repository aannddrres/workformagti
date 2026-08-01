package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.QuizAnswer;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.QuizQuestion;
import ge.magti.portal.domain.User;
import ge.magti.portal.quiz.KnowledgeScoreResult;
import ge.magti.portal.quiz.KnowledgeScoreService;
import ge.magti.portal.quiz.QuizGradeResult;
import ge.magti.portal.quiz.QuizGrader;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.repository.QuizAnswerRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.QuizQuestionRepository;
import ge.magti.portal.repository.UserRepository;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private final ArticleTargetDepartmentRepository targetDepartmentRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizAnswerRepository quizAnswerRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final KnowledgeScoreService knowledgeScoreService;

    public QuizController(
            ArticleRepository articleRepository,
            ArticleTargetDepartmentRepository targetDepartmentRepository,
            QuizQuestionRepository quizQuestionRepository,
            QuizAnswerRepository quizAnswerRepository,
            QuizAttemptRepository quizAttemptRepository,
            AuditLogRepository auditLogRepository,
            UserRepository userRepository,
            KnowledgeScoreService knowledgeScoreService) {
        this.articleRepository = articleRepository;
        this.targetDepartmentRepository = targetDepartmentRepository;
        this.quizQuestionRepository = quizQuestionRepository;
        this.quizAnswerRepository = quizAnswerRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.knowledgeScoreService = knowledgeScoreService;
    }

    @GetMapping("/api/articles/{id}/quiz/admin")
    public ResponseEntity<?> getArticleQuizAdmin(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
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
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
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
        for (QuizQuestionAdminDto question : questions) {
            if (question.answers() == null || question.answers().size() < 2) {
                return unprocessable("ყოველ კითხვას უნდა ჰქონდეს მინიმუმ 2 პასუხი");
            }
            long correctCount = question.answers().stream().filter(QuizAnswerAdminDto::isCorrect).count();
            if (correctCount != 1) {
                return unprocessable("ყოველ კითხვას უნდა ჰქონდეს ზუსტად ერთი სწორი პასუხი");
            }
        }

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

        AuditLog entry = new AuditLog();
        entry.setAdminId(user.getId());
        entry.setAction("UPDATE_QUIZ");
        entry.setItemType("article");
        entry.setItemId(id);
        entry.setTimestamp(TbilisiTime.now());
        auditLogRepository.save(entry);

        return ResponseEntity.ok(buildAdminQuizView(id));
    }

    @GetMapping("/api/articles/{id}/quiz")
    public ResponseEntity<?> getArticleQuiz(@PathVariable Long id, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
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
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
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
        attempt.setArticleVersion(article.getVersion());
        attempt.setUserId(user.getId());
        attempt.setAttemptNumber(attemptNumber);
        attempt.setScore(grade.score());
        attempt.setTotalQuestions(grade.totalQuestions());
        attempt.setPassed(grade.passed());
        attempt.setCreatedAt(TbilisiTime.now());
        quizAttemptRepository.save(attempt);

        return ResponseEntity.ok(new QuizAttemptResultResponse(
                grade.passed(), grade.score(), grade.totalQuestions(), grade.wrongQuestionIds(), attemptNumber));
    }

    @GetMapping("/api/users/me/knowledge-score")
    public ResponseEntity<?> getMyKnowledgeScore(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        KnowledgeScoreResult result = knowledgeScoreService.compute(user.getId());
        return ResponseEntity.ok(KnowledgeScoreResponse.from(user.getId(), result));
    }

    @GetMapping("/api/knowledge-leaderboard")
    public ResponseEntity<?> getKnowledgeLeaderboard(
            @RequestParam(defaultValue = "department") String scope, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        List<User> candidates = ("team".equals(scope) && user.getTeamId() != null)
                ? userRepository.findByActiveTrueAndTeamId(user.getTeamId())
                : userRepository.findByActiveTrueAndDepartment(user.getDepartment());
        List<User> eligible = candidates.stream()
                .filter(u -> !ComplianceCalculator.MANAGEMENT_ROLES.contains(u.getRole()))
                .toList();

        List<LeaderboardEntryResponse> entries = new ArrayList<>();
        for (User candidate : eligible) {
            KnowledgeScoreResult result = knowledgeScoreService.compute(candidate.getId());
            if (result.score() == 0) {
                continue;
            }
            entries.add(new LeaderboardEntryResponse(
                    candidate.getId(), candidate.getName(), candidate.getDepartment(), result.score(), 0));
        }
        entries.sort(Comparator.comparingInt(LeaderboardEntryResponse::score).reversed());
        List<LeaderboardEntryResponse> ranked = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardEntryResponse e = entries.get(i);
            ranked.add(new LeaderboardEntryResponse(e.userId(), e.userName(), e.department(), e.score(), i + 1));
        }

        return ResponseEntity.ok(new LeaderboardResponse(ranked, TbilisiTime.now()));
    }

    private List<QuizQuestion> loadQuestionsWithAnswers(Long articleId) {
        List<QuizQuestion> questions = quizQuestionRepository.findByArticleIdOrderByPosition(articleId);
        if (questions.isEmpty()) {
            return questions;
        }
        Set<Long> questionIds = questions.stream().map(QuizQuestion::getId).collect(Collectors.toSet());
        Map<Long, List<QuizAnswer>> answersByQuestion = quizAnswerRepository.findByQuestionIdIn(questionIds).stream()
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
        List<String> targetDepartments = targetDepartmentRepository.findByArticleId(article.getId()).stream()
                .map(ArticleTargetDepartment::getDepartment)
                .toList();
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

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> requireContentAdmin(User user) {
        ResponseEntity<Map<String, String>> authFailure = requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!user.getRole().isContentAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "Not enough permissions to perform this action"));
        }
        return null;
    }
}
