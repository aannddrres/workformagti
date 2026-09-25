package ge.magti.portal.web;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.quiz.KnowledgeScoreService;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.QuizAnswerRepository;
import ge.magti.portal.repository.QuizAttemptRepository;
import ge.magti.portal.repository.QuizQuestionRepository;
import ge.magti.portal.security.PermissionChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QuizControllerCardinalityTest {

    @Test
    void oversizedAnswerReplacementFailsBeforeDeletingTheExistingQuiz() {
        ArticleRepository articles = mock(ArticleRepository.class);
        ArticleTargetQueryService targets = mock(ArticleTargetQueryService.class);
        QuizQuestionRepository questions = mock(QuizQuestionRepository.class);
        QuizAnswerRepository answers = mock(QuizAnswerRepository.class);
        QuizAttemptRepository attempts = mock(QuizAttemptRepository.class);
        MutationAuditService audit = mock(MutationAuditService.class);
        KnowledgeScoreService scores = mock(KnowledgeScoreService.class);
        PermissionChecker permissions = mock(PermissionChecker.class);
        QuizController controller = new QuizController(
                articles, targets, questions, answers, attempts, audit, scores, permissions);

        User actor = new User();
        actor.setRole(Role.CONTENT_ADMIN);
        when(permissions.hasPermission(actor, Permission.CONTENT_MANAGE)).thenReturn(true);
        Article visibleArticle = new Article();
        visibleArticle.setDraft(false);
        when(articles.findById(42L)).thenReturn(Optional.of(visibleArticle));

        List<QuizAnswerAdminDto> oversizedAnswers = IntStream
                .range(0, CompleteResultGuard.MAX_ROWS + 1)
                .mapToObj(index -> new QuizAnswerAdminDto(
                        null, "answer-" + index, index == 0, index))
                .toList();
        QuizAdminUpdate replacement = new QuizAdminUpdate(List.of(
                new QuizQuestionAdminDto(null, "question", 0, oversizedAnswers)));

        assertThrows(CompleteResultGuard.CompleteResultCardinalityExceededException.class,
                () -> controller.updateArticleQuizAdmin(42L, replacement, actor));

        verifyNoInteractions(questions, answers, attempts, audit, scores, targets);
    }
}
