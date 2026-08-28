package ge.magti.portal.search;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.SearchTrigramRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read side of the hand-built search index. Faithfully ports the query/
 * scoring logic of {@code global_search} and {@code _run_global_search_sync}
 * (routers/search.py:38-122, 124-201) -- same word-splitting, same AND-across-
 * words / OR-across-fields semantics, same {@code CASE WHEN} score
 * precedence (first matching field wins, not additive) -- but resolves
 * candidates via {@link SearchTrigramRepository} first instead of a full
 * table scan with {@code ILIKE}, then re-verifies every candidate with an
 * exact substring check against the real columns (see {@link
 * TrigramIndexer}'s javadoc for why the recheck is load-bearing for
 * correctness, not just an optimization).
 *
 * <p><b>One deliberate behavior fix, confirmed with the user before being
 * built:</b> the Python original's {@code _run_global_search_sync} news
 * branch has no draft/expiry check at all (only a department filter),
 * unlike {@code get_news_item}/{@link ge.magti.portal.news.NewsQueryService}
 * which both correctly hide drafts and expired news from non-admins -- a
 * real confidentiality gap (an operator could find an unpublished draft's
 * title/content through search that they can never otherwise see). Fixed
 * here to match News' own list-visibility rule exactly, not faithfully
 * reproduced. Articles and videos already carry the correct status/
 * archived check in the Python original, so nothing changes there.
 */
@Service
public class SearchQueryService {

    /**
     * Hard work-set ceiling. The public KB search can return up to the same
     * 1,000 items already supported by the list UI; global search reduces the
     * bounded candidates further to 8/5/5 response items.
     */
    static final int MAX_CANDIDATES = 1_000;
    static final int MAX_ARTICLE_RESULTS = 1_000;

    private final SearchTrigramRepository trigramRepository;
    private final ArticleRepository articleRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final NewsRepository newsRepository;
    private final VideoInstructionRepository videoRepository;

    public SearchQueryService(
            SearchTrigramRepository trigramRepository,
            ArticleRepository articleRepository,
            ArticleTargetQueryService articleTargetQueryService,
            NewsRepository newsRepository,
            VideoInstructionRepository videoRepository) {
        this.trigramRepository = trigramRepository;
        this.articleRepository = articleRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.newsRepository = newsRepository;
        this.videoRepository = videoRepository;
    }

    public record GlobalSearchResult(List<Article> articles, List<News> news, List<VideoInstruction> videos) {
    }

    /** Splits on whitespace, drops blanks -- matches Python's {@code q.split()} + {@code if w.strip()} exactly. */
    public static List<String> splitWords(String q) {
        List<String> words = new ArrayList<>();
        if (q == null) {
            return words;
        }
        for (String part : q.trim().split("\\s+")) {
            if (!part.isBlank()) {
                words.add(part);
            }
        }
        return words;
    }

    /** Mirrors global_search (routers/search.py:38-122), minus the SearchLog write (the controller's job). */
    public List<Article> searchArticles(String q, Long categoryId, User user) {
        List<String> words = splitWords(q);
        boolean isAdmin = user.getRole().isContentAdmin();

        List<Article> candidates;
        Map<Long, Integer> scores = Map.of();
        if (words.isEmpty()) {
            if (categoryId == null) {
                return List.of();
            }
            candidates = articleRepository.findByCategoryId(
                    categoryId, PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            Set<Long> candidateIds = candidateIds(SearchReindexService.ARTICLE, words);
            List<Article> fetched = candidateIds == null
                    ? newestArticles() : articleRepository.findAllById(candidateIds);

            Map<Long, Integer> scored = new LinkedHashMap<>();
            List<Article> matched = new ArrayList<>();
            for (Article article : fetched) {
                Integer score = scoreArticle(article, words);
                if (score != null) {
                    matched.add(article);
                    scored.put(article.getId(), score);
                }
            }
            candidates = matched;
            scores = scored;
        }

        List<Article> visible = new ArrayList<>();
        if (isAdmin) {
            visible.addAll(candidates);
        } else {
            OffsetDateTime now = TbilisiTime.now();
            Set<Long> ids = candidates.stream().map(Article::getId).collect(Collectors.toSet());
            Map<Long, List<String>> deptsByArticle =
                    articleTargetQueryService.targetDepartmentsByArticleWithinLimit(ids);
            for (Article article : candidates) {
                boolean statusOk = "published".equals(article.getStatus())
                        || ("scheduled".equals(article.getStatus())
                                && article.getPublishedAt() != null && !article.getPublishedAt().isAfter(now));
                boolean deptOk = DepartmentMatcher.matches(
                        user.getDepartment(), deptsByArticle.getOrDefault(article.getId(), List.of()));
                if (statusOk && deptOk) {
                    visible.add(article);
                }
            }
        }

        if (categoryId != null) {
            visible.removeIf(article -> !categoryId.equals(article.getCategoryId()));
        }
        if (!words.isEmpty()) {
            Map<Long, Integer> finalScores = scores;
            visible.sort(Comparator.comparingInt((Article a) -> finalScores.getOrDefault(a.getId(), 0)).reversed());
        }
        return visible.stream().limit(MAX_ARTICLE_RESULTS).toList();
    }

    /** Mirrors _run_global_search_sync (routers/search.py:124-201), minus caching/logging (the controller's job). */
    public GlobalSearchResult searchGlobal(String q, User user) {
        List<String> words = splitWords(q);
        if (words.isEmpty()) {
            return new GlobalSearchResult(List.of(), List.of(), List.of());
        }
        boolean isAdmin = user.getRole().isContentAdmin();

        List<Article> articles = searchArticles(q, null, user).stream().limit(8).toList();
        List<News> news = searchNews(words, isAdmin, user);
        List<VideoInstruction> videos = searchVideos(words, isAdmin, user);
        return new GlobalSearchResult(articles, news, videos);
    }

    private List<News> searchNews(List<String> words, boolean isAdmin, User user) {
        Set<Long> candidateIds = candidateIds(SearchReindexService.NEWS, words);
        List<News> fetched = candidateIds == null ? newestNews() : newsRepository.findAllById(candidateIds);

        Map<Long, Integer> scores = new LinkedHashMap<>();
        List<News> matched = new ArrayList<>();
        for (News news : fetched) {
            Integer score = scoreNews(news, words);
            if (score != null) {
                matched.add(news);
                scores.put(news.getId(), score);
            }
        }

        List<News> visible;
        if (isAdmin) {
            visible = matched;
        } else {
            visible = matched.stream()
                    .filter(n -> !n.isDraft())
                    .filter(n -> !n.isArchived())
                    .filter(n -> DepartmentMatcher.matches(user.getDepartment(), List.of(n.getTargetDepartment())))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        visible.sort(Comparator.comparingInt((News n) -> scores.getOrDefault(n.getId(), 0)).reversed());
        return visible.stream().limit(5).toList();
    }

    private List<VideoInstruction> searchVideos(List<String> words, boolean isAdmin, User user) {
        Set<Long> candidateIds = candidateIds(SearchReindexService.VIDEO, words);
        List<VideoInstruction> fetched = candidateIds == null
                ? newestVideos() : videoRepository.findAllById(candidateIds);

        List<VideoInstruction> matched = fetched.stream()
                .filter(v -> allWordsMatchVideo(v, words))
                .toList();

        List<VideoInstruction> visible = isAdmin
                ? matched
                : matched.stream()
                        .filter(v -> !v.isArchived())
                        .filter(v -> DepartmentMatcher.matches(user.getDepartment(), List.of(v.getTargetDepartment())))
                        .toList();
        return visible.stream().limit(5).toList();
    }

    /**
     * Entity ids sharing every trigram of every word 3+ characters long
     * (intersected across words). {@code null} means "no word was long
     * enough to narrow anything" -- the caller falls back to the newest
     * bounded work set. An empty (non-null) set means a genuine, provable
     * zero-match short-circuit. Every per-word index query is bounded too;
     * deterministic newest-id ordering makes overload degradation explicit
     * instead of allowing memory use to grow with the corpus.
     */
    private Set<Long> candidateIds(String entityType, List<String> words) {
        List<String> indexableWords = words.stream()
                .filter(w -> w.length() >= TrigramIndexer.TRIGRAM_LENGTH)
                .toList();
        if (indexableWords.isEmpty()) {
            return null;
        }
        Set<Long> intersection = null;
        for (String word : indexableWords) {
            Set<String> trigrams = TrigramIndexer.extract(word);
            List<Long> ids = trigramRepository.findCandidateEntityIds(
                    entityType, trigrams, trigrams.size(), PageRequest.of(0, MAX_CANDIDATES));
            if (intersection == null) {
                intersection = new HashSet<>(ids);
            } else {
                intersection.retainAll(ids);
            }
            if (intersection.isEmpty()) {
                break;
            }
        }
        return intersection;
    }

    private List<Article> newestArticles() {
        return articleRepository.findAll(
                PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    private List<News> newestNews() {
        return newsRepository.findAll(
                PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    private List<VideoInstruction> newestVideos() {
        return videoRepository.findAll(
                PageRequest.of(0, MAX_CANDIDATES, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();
    }

    /** TITLE(10) &gt; TAGS(5) &gt; CONTENT(1), first match wins per word -- null means the word matched nowhere (AND fails). */
    private static Integer scoreArticle(Article article, List<String> words) {
        int total = 0;
        for (String word : words) {
            String needle = word.toLowerCase(Locale.ROOT);
            int wordScore;
            if (containsCi(article.getTitle(), needle)) {
                wordScore = 10;
            } else if (containsCi(article.getTags(), needle)) {
                wordScore = 5;
            } else if (containsCi(article.getContent(), needle)) {
                wordScore = 1;
            } else {
                return null;
            }
            total += wordScore;
        }
        return total;
    }

    /** TITLE(3) &gt; CONTENT(1), same first-match-wins precedence, null means the word matched nowhere. */
    private static Integer scoreNews(News news, List<String> words) {
        int total = 0;
        for (String word : words) {
            String needle = word.toLowerCase(Locale.ROOT);
            int wordScore;
            if (containsCi(news.getTitle(), needle)) {
                wordScore = 3;
            } else if (containsCi(news.getContent(), needle)) {
                wordScore = 1;
            } else {
                return null;
            }
            total += wordScore;
        }
        return total;
    }

    /** Video has no relevance scoring in the Python original -- just title-OR-category membership. */
    private static boolean allWordsMatchVideo(VideoInstruction video, List<String> words) {
        for (String word : words) {
            String needle = word.toLowerCase(Locale.ROOT);
            if (!containsCi(video.getTitle(), needle) && !containsCi(video.getCategory(), needle)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsCi(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }
}
