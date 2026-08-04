package ge.magti.portal.content;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Port of db_helpers.py's resolve_item_title -- looks up the display title
 * for one polymorphic (item_type, item_id) soft-reference. Shared by any
 * domain that stores such references (Favorites, RequiredReading, audit
 * item-name snapshots) rather than duplicated per call site, same reason
 * Python centralised it in db_helpers.py.
 *
 * <p>Not ported: resolve_item_titles_bulk (db_helpers.py:56-71), the
 * grouped-IN-query variant used by call sites resolving many items at
 * once (Compliance's my-readings, Platform's notifications-summary) --
 * added when those domains are actually built, not pre-emptively.
 */
@Service
public class ItemTitleResolver {

    private final ArticleRepository articleRepository;
    private final NewsRepository newsRepository;
    private final VideoInstructionRepository videoInstructionRepository;

    public ItemTitleResolver(
            ArticleRepository articleRepository,
            NewsRepository newsRepository,
            VideoInstructionRepository videoInstructionRepository) {
        this.articleRepository = articleRepository;
        this.newsRepository = newsRepository;
        this.videoInstructionRepository = videoInstructionRepository;
    }

    public Optional<String> resolve(String itemType, Long itemId) {
        return switch (itemType == null ? "" : itemType) {
            case "article" -> articleRepository.findById(itemId).map(Article::getTitle);
            case "news" -> newsRepository.findById(itemId).map(News::getTitle);
            case "video" -> videoInstructionRepository.findById(itemId).map(VideoInstruction::getTitle);
            default -> Optional.empty();
        };
    }
}
