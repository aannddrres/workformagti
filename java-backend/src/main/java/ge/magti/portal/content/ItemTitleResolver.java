package ge.magti.portal.content;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.news.NewsVisibility;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.video.VideoVisibility;
import org.springframework.stereotype.Service;

import java.util.Collection;
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
    private final ArticleTargetQueryService articleTargetQueryService;

    public ItemTitleResolver(
            ArticleRepository articleRepository,
            NewsRepository newsRepository,
            VideoInstructionRepository videoInstructionRepository,
            ArticleTargetQueryService articleTargetQueryService) {
        this.articleRepository = articleRepository;
        this.newsRepository = newsRepository;
        this.videoInstructionRepository = videoInstructionRepository;
        this.articleTargetQueryService = articleTargetQueryService;
    }

    /** {@link #visibleTitles} for one item. */
    public Optional<String> resolveFor(String itemType, Long itemId, User viewer) {
        ItemKey key = new ItemKey(itemType, itemId);
        return Optional.ofNullable(visibleTitles(List.of(key), viewer).get(key));
    }

    /**
     * The title of each item this viewer may open, by (type, id). An item they
     * may not open -- another department's, an archived one, another author's
     * private draft -- is absent, exactly like one that no longer exists, and
     * the caller shows its own placeholder (the owner's decision on A17,
     * 2026-09-26).
     *
     * <p>A bookmark takes any id, and answered with that item's title: walking
     * ids read the title of anything. Each type now asks the rule its own page
     * applies ({@link ArticleVisibility}, {@link NewsVisibility},
     * {@link VideoVisibility}). One query per type, plus one for the articles'
     * audiences, rather than one per bookmark.
     */
    public Map<ItemKey, String> visibleTitles(Collection<ItemKey> keys, User viewer) {
        Set<Long> articleIds = idsOf(keys, "article");
        Set<Long> newsIds = idsOf(keys, "news");
        Set<Long> videoIds = idsOf(keys, "video");

        Map<ItemKey, String> titles = new HashMap<>();
        if (!articleIds.isEmpty()) {
            // An administrator's audience is not consulted, so it is not loaded.
            Map<Long, List<String>> audiences = viewer.getRole().isContentAdmin() ? Map.of()
                    : articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);
            for (Article a : articleRepository.findAllById(articleIds)) {
                if (ArticleVisibility.isVisible(a, audiences.getOrDefault(a.getId(), List.of()), viewer)) {
                    titles.put(new ItemKey("article", a.getId()), a.getTitle());
                }
            }
        }
        if (!newsIds.isEmpty()) {
            for (News n : newsRepository.findAllById(newsIds)) {
                if (NewsVisibility.isVisible(n, viewer)) {
                    titles.put(new ItemKey("news", n.getId()), n.getTitle());
                }
            }
        }
        if (!videoIds.isEmpty()) {
            for (VideoInstruction v : videoInstructionRepository.findAllById(videoIds)) {
                if (VideoVisibility.isVisible(v, viewer)) {
                    titles.put(new ItemKey("video", v.getId()), v.getTitle());
                }
            }
        }
        return titles;
    }

    private static Set<Long> idsOf(Collection<ItemKey> keys, String itemType) {
        return keys.stream()
                .filter(k -> itemType.equals(k.itemType()) && k.itemId() != null)
                .map(ItemKey::itemId)
                .collect(Collectors.toSet());
    }

    /**
     * Whether (itemType, itemId) is another author's private draft, the one
     * kind of item a caller may not address at all (PO-34, D2). Here because
     * this is the class that already loads an item from its wire name and id.
     */
    public boolean isPrivateDraftOfAnother(String itemType, Long itemId, User viewer) {
        if (itemId == null) {
            return false;
        }
        return switch (itemType == null ? "" : itemType) {
            case "article" -> articleRepository.findById(itemId)
                    .map(a -> ArticleVisibility.isPrivateDraftOfAnother(a, viewer)).orElse(false);
            case "news" -> newsRepository.findById(itemId)
                    .map(n -> NewsVisibility.isPrivateDraftOfAnother(n, viewer)).orElse(false);
            default -> false;
        };
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
                details.put(new ItemKey("article", a.getId()),
                        new ItemDetail(a.getTitle(), "", a.getUpdatedAt()));
            }
        }
        if (!newsIds.isEmpty()) {
            for (News n : newsRepository.findAllById(newsIds)) {
                // News carries no updated_at, only created_at -- see ItemDetail.
                details.put(new ItemKey("news", n.getId()), new ItemDetail(n.getTitle(), "", null));
            }
        }
        if (!videoIds.isEmpty()) {
            for (VideoInstruction v : videoInstructionRepository.findAllById(videoIds)) {
                details.put(new ItemKey("video", v.getId()),
                        new ItemDetail(v.getTitle(), v.getVideoUrl(), null));
            }
        }
        return details;
    }
}
