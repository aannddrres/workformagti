package ge.magti.portal.content;

import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Port of db_helpers.py's resolve_item_title -- looks up the display title
 * for one polymorphic (item_type, item_id) soft-reference. Shared by any
 * domain that stores such references (Favorites, RequiredReading, audit
 * item-name snapshots) rather than duplicated per call site, same reason
 * Python centralised it in db_helpers.py.
 *
 * <p>{@link #resolveDetailsBulk} is the batched variant get_my_readings
 * uses (routers/compliance.py:56-85, "Item 15 (perf)"): one IN(...) query
 * per item type instead of one query per reading. Platform's
 * notifications-summary will reuse it once that domain is built.
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

    /**
     * Batched title+content resolution for get_my_readings: collects ids by
     * type, runs one {@code findAllById} per type, and returns a map keyed by
     * (itemType, itemId). A key is absent when the underlying item no longer
     * exists -- the caller supplies its own fallback text, exactly as Python
     * does. Content is "" for article/news and the video URL for a video.
     */
    public Map<ItemKey, ItemDetail> resolveDetailsBulk(List<ItemKey> keys) {
        Set<Long> articleIds = keys.stream().filter(k -> "article".equals(k.itemType())).map(ItemKey::itemId).collect(Collectors.toSet());
        Set<Long> newsIds = keys.stream().filter(k -> "news".equals(k.itemType())).map(ItemKey::itemId).collect(Collectors.toSet());
        Set<Long> videoIds = keys.stream().filter(k -> "video".equals(k.itemType())).map(ItemKey::itemId).collect(Collectors.toSet());

        Map<ItemKey, ItemDetail> details = new HashMap<>();
        if (!articleIds.isEmpty()) {
            for (Article a : articleRepository.findAllById(articleIds)) {
                details.put(new ItemKey("article", a.getId()), new ItemDetail(a.getTitle(), ""));
            }
        }
        if (!newsIds.isEmpty()) {
            for (News n : newsRepository.findAllById(newsIds)) {
                details.put(new ItemKey("news", n.getId()), new ItemDetail(n.getTitle(), ""));
            }
        }
        if (!videoIds.isEmpty()) {
            for (VideoInstruction v : videoInstructionRepository.findAllById(videoIds)) {
                details.put(new ItemKey("video", v.getId()), new ItemDetail(v.getTitle(), v.getVideoUrl()));
            }
        }
        return details;
    }
}
