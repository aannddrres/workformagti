package ge.magti.portal.storage;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ArticleTargetDepartment;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ArticleTargetDepartmentRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.StoredFileRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAccessPolicyTest {

    private final FileReferenceIndex references = mock(FileReferenceIndex.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final ArticleTargetDepartmentRepository targets = mock(ArticleTargetDepartmentRepository.class);
    private final FileAccessPolicy policy = new FileAccessPolicy(
            references, mock(StoredFileRepository.class), articles,
            new ArticleTargetQueryService(targets), mock(NewsRepository.class),
            mock(VideoInstructionRepository.class));

    @Test
    void attachmentUsesTheCompleteArticleAudienceBeyondTheFirstHundredRows() {
        Article article = new Article();
        article.setStatus("published");
        article.setDraft(false);
        when(references.referencesTo("attachment.png"))
                .thenReturn(List.of(new FileReferenceIndex.Reference("article", 7L)));
        when(articles.findById(7L)).thenReturn(Optional.of(article));

        List<ArticleTargetDepartment> savedTargets = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            savedTargets.add(row("other-" + i));
        }
        savedTargets.add(row("target-101"));
        when(targets.findByArticleIdIn(eq(List.of(7L)), any(Pageable.class)))
                .thenReturn(savedTargets);

        User intendedReader = operator("target-101");
        User outsider = operator("not-targeted");
        assertEquals(FileAccessPolicy.Decision.ALLOWED_REFERENCED,
                policy.decide("attachment.png", intendedReader));
        assertEquals(FileAccessPolicy.Decision.DENIED_NOT_VISIBLE,
                policy.decide("attachment.png", outsider));
    }

    private static ArticleTargetDepartment row(String department) {
        ArticleTargetDepartment row = new ArticleTargetDepartment();
        row.setArticleId(7L);
        row.setDepartment(department);
        return row;
    }

    private static User operator(String department) {
        User user = new User();
        user.setRole(Role.OPERATOR);
        user.setDepartment(department);
        return user;
    }
}
