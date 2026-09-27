package ge.magti.portal.storage;

import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.StoredFile;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.news.NewsVisibility;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.StoredFileRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.video.VideoVisibility;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a person may read an uploaded file (DEC-P01).
 *
 * <p>Before this, {@code /uploads/{filename}} checked authentication and
 * stopped. UAT finding F-1 showed what that meant in practice: an ოფისი
 * operator downloaded a 51 KB PNG out of a ტექნიკური article that answers
 * 404 for them. The file was protected by nothing but the difficulty of
 * guessing a 64-character name -- and names travel, in shared links, in
 * browser history, in a page someone could once open and now cannot.
 *
 * <p>The rule is the obvious one: <b>a file is readable when something that
 * references it is readable.</b> Not "the file's owner", because a diagram
 * can legitimately appear in an article and in the news post announcing it,
 * and losing access to one should not cost you the other.
 *
 * <p>Visibility of the referencing item is delegated, never re-implemented:
 * {@link ArticleVisibility} is the same predicate {@code ArticleController}
 * uses, so a file cannot outlive its article's audience by disagreeing with
 * it about what "visible" means.
 */
@Service
public class FileAccessPolicy {

    private final FileReferenceIndex referenceIndex;
    private final StoredFileRepository storedFileRepository;
    private final ArticleRepository articleRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final NewsRepository newsRepository;
    private final VideoInstructionRepository videoInstructionRepository;

    public FileAccessPolicy(
            FileReferenceIndex referenceIndex,
            StoredFileRepository storedFileRepository,
            ArticleRepository articleRepository,
            ArticleTargetQueryService articleTargetQueryService,
            NewsRepository newsRepository,
            VideoInstructionRepository videoInstructionRepository) {
        this.referenceIndex = referenceIndex;
        this.storedFileRepository = storedFileRepository;
        this.articleRepository = articleRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.newsRepository = newsRepository;
        this.videoInstructionRepository = videoInstructionRepository;
    }

    public Decision decide(String filename, User user) {
        List<FileReferenceIndex.Reference> references = referenceIndex.referencesTo(filename);

        if (references.isEmpty()) {
            // Nothing points at this file yet. That is the ordinary state of
            // an image between "editor dropped it into the composer" and
            // "editor pressed save" -- the article it belongs to does not
            // exist, so no audience can be derived from it. Refusing here
            // would mean an editor cannot see the picture they just inserted.
            //
            // DEC-P01 calls this out as needing its own confirmation, and the
            // narrowest answer that still works is: whoever uploaded it, and
            // nobody else. It closes on its own the moment the content is
            // saved and real references appear.
            return isUploader(filename, user)
                    ? Decision.ALLOWED_UPLOADER
                    : Decision.DENIED_ORPHAN;
        }

        for (FileReferenceIndex.Reference reference : references) {
            if (canRead(reference, user)) {
                return Decision.ALLOWED_REFERENCED;
            }
        }
        return Decision.DENIED_NOT_VISIBLE;
    }

    private boolean isUploader(String filename, User user) {
        // StoredFile is keyed by its own filename.
        return storedFileRepository.findById(filename)
                .map(StoredFile::getUploadedBy)
                .map(uploader -> Objects.equals(uploader, user.getId()))
                .orElse(false);
    }

    private boolean canRead(FileReferenceIndex.Reference reference, User user) {
        return switch (reference.itemType()) {
            case "article" -> canReadArticle(reference.itemId(), user);
            case "news" -> canReadNews(reference.itemId(), user);
            case "video" -> canReadVideo(reference.itemId(), user);
            default -> false;
        };
    }

    private boolean canReadArticle(Long articleId, User user) {
        // Trashed items are already filtered out of referencesTo(), so a
        // reference reaching here points at live content.
        Optional<Article> found = articleRepository.findById(articleId);
        if (found.isEmpty()) {
            return false;
        }
        List<String> targets = articleTargetQueryService.targetDepartmentsForArticleWithinLimit(articleId);
        return ArticleVisibility.isVisible(found.get(), targets, user);
    }

    /** Private drafts stay private even when their uploaded filename is known. */
    private boolean canReadNews(Long newsId, User user) {
        Optional<News> found = newsRepository.findById(newsId);
        if (found.isEmpty() || found.get().isArchived()) {
            return false;
        }
        return NewsVisibility.isVisible(found.get(), user);
    }

    private boolean canReadVideo(Long videoId, User user) {
        Optional<VideoInstruction> found = videoInstructionRepository.findById(videoId);
        if (found.isEmpty() || found.get().isArchived()) {
            return false;
        }
        return VideoVisibility.isInAudience(found.get(), user);
    }

    /**
     * Why a file was allowed or refused. Kept richer than a boolean because
     * the rollout runs in shadow first: the log needs to say which rule
     * would have fired, not merely that one did.
     */
    public enum Decision {
        ALLOWED_REFERENCED,
        ALLOWED_UPLOADER,
        DENIED_NOT_VISIBLE,
        DENIED_ORPHAN;

        public boolean allowed() {
            return this == ALLOWED_REFERENCED || this == ALLOWED_UPLOADER;
        }
    }
}
