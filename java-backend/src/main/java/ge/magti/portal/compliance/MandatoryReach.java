package ge.magti.portal.compliance;

import ge.magti.portal.article.ArticleTargetQueryService;
import ge.magti.portal.article.ArticleVisibility;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.news.NewsVisibility;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.VideoInstructionRepository;
import ge.magti.portal.user.UserDirectoryQueryService;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import ge.magti.portal.video.VideoVisibility;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PO-40: a mandatory reading binds only the people who can open its material.
 *
 * <p>The question is the item page's own, asked once per reading rather than
 * once per person: could an operator of the reading's target department open
 * this article, news item or video right now? It goes to
 * {@link ArticleVisibility}, {@link NewsVisibility} and {@link VideoVisibility}
 * through a probe operator carrying that department, so this class holds no
 * second copy of any visibility rule. A reading passes only when its whole
 * target is covered: "ტექნიკური" on an article for "ტექნიკური — ჯგუფი 03"
 * does not, because the department's other groups could not open it. Every
 * addressee of a covered reading can, since their department matches the
 * target and lifecycle is a property of the item, not of the reader.
 *
 * <p>Before this, nothing asked. An article still in draft, a department the
 * article never addressed, or an item archived after the assignment all left
 * operators "overdue" on material they could not open, and reminded about it.
 *
 * <p>Three states. {@link State#IN_FORCE}: listed, counted and reminded.
 * {@link State#PENDING}: an article scheduled for later that the target will
 * be able to open at its publication moment, when it comes into force and its
 * assignment reminders go out. {@link State#SUSPENDED}: nobody is bound while
 * the obstacle lasts; confirmations already given stay, and the obligation
 * resumes if the material becomes visible to its target again.
 */
@Service
public class MandatoryReach {

    public enum State { IN_FORCE, PENDING, SUSPENDED }

    /** Why a reading is not in force, for the editor's message. */
    public enum Obstacle {
        /** The item no longer exists, or is in the trash. */
        MISSING,
        /** A personal autosave draft: only its author sees it. */
        PRIVATE_DRAFT,
        /** Not published: a draft status, or a schedule with no moment. */
        UNPUBLISHED,
        ARCHIVED,
        /** Published, but not for the whole of the reading's target. */
        OUTSIDE_AUDIENCE
    }

    /**
     * @param obstacle null unless {@link State#SUSPENDED}
     * @param startsAt the publication moment, only for {@link State#PENDING}
     */
    public record Reach(State state, Obstacle obstacle, OffsetDateTime startsAt) {
        public boolean inForce() {
            return state == State.IN_FORCE;
        }
    }

    /**
     * What an assignment would do, for the endpoint that refuses one.
     *
     * @param blocked the addressees who could not open the item, in directory
     *                order; empty unless {@link State#SUSPENDED}
     */
    public record Assessment(Reach reach, List<User> blocked) {
    }

    private static final String WILDCARD = DepartmentMatcher.WILDCARD_TARGET;

    private final RequiredReadingRepository requiredReadingRepository;
    private final ArticleRepository articleRepository;
    private final NewsRepository newsRepository;
    private final VideoInstructionRepository videoInstructionRepository;
    private final ArticleTargetQueryService articleTargetQueryService;
    private final UserDirectoryQueryService userDirectoryQueryService;
    private final OrgDirectoryQueryService orgDirectoryQueryService;

    public MandatoryReach(
            RequiredReadingRepository requiredReadingRepository,
            ArticleRepository articleRepository,
            NewsRepository newsRepository,
            VideoInstructionRepository videoInstructionRepository,
            ArticleTargetQueryService articleTargetQueryService,
            UserDirectoryQueryService userDirectoryQueryService,
            OrgDirectoryQueryService orgDirectoryQueryService) {
        this.requiredReadingRepository = requiredReadingRepository;
        this.articleRepository = articleRepository;
        this.newsRepository = newsRepository;
        this.videoInstructionRepository = videoInstructionRepository;
        this.articleTargetQueryService = articleTargetQueryService;
        this.userDirectoryQueryService = userDirectoryQueryService;
        this.orgDirectoryQueryService = orgDirectoryQueryService;
    }

    /** {@link #reachOf(Collection)} for one reading. */
    public Reach reachOf(RequiredReading reading) {
        return reachOf(List.of(reading)).get(reading.getId());
    }

    /**
     * The state of each reading, keyed by reading id: one query per item type
     * and one for the articles' audiences, however many readings.
     */
    public Map<Long, Reach> reachOf(Collection<RequiredReading> readings) {
        Materials materials = load(readings.stream().map(r -> new Key(r.getItemType(), r.getItemId())).toList());
        OffsetDateTime now = TbilisiTime.now();
        Map<Long, Reach> result = new LinkedHashMap<>();
        for (RequiredReading reading : readings) {
            Material material = materials.get(reading.getItemType(), reading.getItemId());
            result.put(reading.getId(), reach(material, targetOf(reading.getTargetDepartment()), now));
        }
        return result;
    }

    /** The ids of the readings in force now; the only ones listed, counted or reminded. */
    public Set<Long> inForceIds(Collection<RequiredReading> readings) {
        return reachOf(readings).entrySet().stream()
                .filter(entry -> entry.getValue().inForce())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * {@link #inForceIds} over every reading targeting one of these exact
     * department strings -- the compliance numbers' denominator. Bounded like
     * every other reading scan: past the sentinel page it fails loudly.
     */
    public Set<Long> inForceIdsForTargets(Collection<String> targetDepartments) {
        if (targetDepartments.isEmpty()) {
            return Set.of();
        }
        return inForceIds(CompleteResultGuard.enforce(requiredReadingRepository.findByTargetDepartmentIn(
                List.copyOf(new LinkedHashSet<>(targetDepartments)), CompleteResultGuard.sentinelPage())));
    }

    /**
     * What assigning (itemType, itemId) to {@code targetDepartment} would do,
     * with the addressees who could not open it named.
     */
    public Assessment assess(String itemType, Long itemId, String targetDepartment) {
        Material material = load(List.of(new Key(itemType, itemId))).get(itemType, itemId);
        String target = targetOf(targetDepartment);
        Reach reach = reach(material, target, TbilisiTime.now());
        if (reach.state() != State.SUSPENDED) {
            return new Assessment(reach, List.of());
        }
        List<User> blocked = addressees(target).stream()
                .filter(user -> material == null || !material.visibleTo(user))
                .toList();
        return new Assessment(reach, blocked);
    }

    /**
     * Everyone a reading for {@code targetDepartment} addresses: active,
     * eligible for compliance, and in the target. The one list the assignment
     * reminders and the editor's warnings both use, so they cannot disagree.
     */
    public List<User> addressees(String targetDepartment) {
        return addresseesByTarget(List.of(targetOf(targetDepartment))).get(targetOf(targetDepartment));
    }

    /** {@link #addressees} for several targets, reading the directory once. */
    public Map<String, List<User>> addresseesByTarget(Collection<String> targetDepartments) {
        Set<Long> leaders = orgDirectoryQueryService.listActiveAssignmentsWithinLimit().stream()
                .map(LeadershipAssignment::getUserId)
                .collect(Collectors.toSet());
        List<User> eligible = userDirectoryQueryService.listActiveUsersWithinLimit().stream()
                .filter(user -> ComplianceEligibilityService.resolve(user, leaders.contains(user.getId())))
                .toList();
        Map<String, List<User>> result = new LinkedHashMap<>();
        for (String targetDepartment : targetDepartments) {
            String target = targetOf(targetDepartment);
            result.computeIfAbsent(target, t -> eligible.stream()
                    .filter(user -> DepartmentMatcher.matches(user.getDepartment(), List.of(t)))
                    .toList());
        }
        return result;
    }

    /**
     * The departments a mandatory article's readings target: its audience,
     * with anything already covered by another entry removed ("All" covers
     * everything, a department its own groups), so nobody is addressed twice
     * by the same article.
     */
    public static List<String> readingTargets(Collection<String> audience) {
        Set<String> departments = new LinkedHashSet<>();
        for (String department : audience) {
            if (department != null && !department.isBlank()) {
                departments.add(department);
            }
        }
        if (departments.contains(WILDCARD)) {
            return List.of(WILDCARD);
        }
        return departments.stream()
                .filter(department -> {
                    String prefix = DepartmentMatcher.splitGroup(department).prefix();
                    return prefix.equals(department) || !departments.contains(prefix);
                })
                .toList();
    }

    private static String targetOf(String targetDepartment) {
        return targetDepartment == null || targetDepartment.isBlank() ? WILDCARD : targetDepartment;
    }

    private static Reach reach(Material material, String target, OffsetDateTime now) {
        if (material == null) {
            return new Reach(State.SUSPENDED, Obstacle.MISSING, null);
        }
        if (material.visibleTo(probe(target))) {
            return new Reach(State.IN_FORCE, null, null);
        }
        OffsetDateTime startsAt = material.publicationPendingFor(target, now);
        if (startsAt != null) {
            return new Reach(State.PENDING, null, startsAt);
        }
        return new Reach(State.SUSPENDED, material.obstacleFor(target), null);
    }

    /** An operator in the target department: no id, so the author of nothing. */
    private static User probe(String target) {
        User probe = new User();
        probe.setRole(Role.OPERATOR);
        probe.setDepartment(target);
        probe.setActive(true);
        return probe;
    }

    private Materials load(Collection<Key> keys) {
        Set<Long> articleIds = idsOf(keys, "article");
        Set<Long> newsIds = idsOf(keys, "news");
        Set<Long> videoIds = idsOf(keys, "video");
        Materials materials = new Materials();
        if (!articleIds.isEmpty()) {
            Map<Long, List<String>> audiences =
                    articleTargetQueryService.targetDepartmentsByArticleWithinLimit(articleIds);
            for (Article article : articleRepository.findAllById(articleIds)) {
                materials.put("article", article.getId(),
                        new ArticleMaterial(article, audiences.getOrDefault(article.getId(), List.of())));
            }
        }
        if (!newsIds.isEmpty()) {
            for (News news : newsRepository.findAllById(newsIds)) {
                materials.put("news", news.getId(), new NewsMaterial(news));
            }
        }
        if (!videoIds.isEmpty()) {
            for (VideoInstruction video : videoInstructionRepository.findAllById(videoIds)) {
                materials.put("video", video.getId(), new VideoMaterial(video));
            }
        }
        return materials;
    }

    private static Set<Long> idsOf(Collection<Key> keys, String itemType) {
        return keys.stream()
                .filter(key -> itemType.equals(key.itemType()) && key.itemId() != null)
                .map(Key::itemId)
                .collect(Collectors.toSet());
    }

    private record Key(String itemType, Long itemId) {
    }

    private static final class Materials {
        private final Map<Key, Material> byKey = new HashMap<>();

        void put(String itemType, Long itemId, Material material) {
            byKey.put(new Key(itemType, itemId), material);
        }

        Material get(String itemType, Long itemId) {
            return byKey.get(new Key(itemType, itemId));
        }
    }

    /** A loaded item, answering through its own type's visibility rule. */
    private sealed interface Material permits ArticleMaterial, NewsMaterial, VideoMaterial {
        boolean visibleTo(User user);

        /** The publication moment, when the target will be able to open it then but not now. */
        OffsetDateTime publicationPendingFor(String target, OffsetDateTime now);

        Obstacle obstacleFor(String target);
    }

    private record ArticleMaterial(Article article, List<String> audience) implements Material {
        @Override
        public boolean visibleTo(User user) {
            return ArticleVisibility.isVisible(article, audience, user);
        }

        @Override
        public OffsetDateTime publicationPendingFor(String target, OffsetDateTime now) {
            OffsetDateTime publishedAt = article.getPublishedAt();
            boolean scheduledLater = "scheduled".equals(article.getStatus())
                    && publishedAt != null && publishedAt.isAfter(now);
            return scheduledLater && !article.isDraft() && DepartmentMatcher.matches(target, audience)
                    ? publishedAt : null;
        }

        @Override
        public Obstacle obstacleFor(String target) {
            if (article.isDraft()) {
                return Obstacle.PRIVATE_DRAFT;
            }
            if ("archived".equals(article.getStatus())) {
                return Obstacle.ARCHIVED;
            }
            if (!ArticleVisibility.isPublishedByLifecycle(article.getStatus(), article.getPublishedAt())) {
                return Obstacle.UNPUBLISHED;
            }
            return Obstacle.OUTSIDE_AUDIENCE;
        }
    }

    private record NewsMaterial(News news) implements Material {
        @Override
        public boolean visibleTo(User user) {
            return NewsVisibility.isVisible(news, user);
        }

        @Override
        public OffsetDateTime publicationPendingFor(String target, OffsetDateTime now) {
            return null;
        }

        @Override
        public Obstacle obstacleFor(String target) {
            if (news.isDraft()) {
                return Obstacle.PRIVATE_DRAFT;
            }
            return news.isArchived() ? Obstacle.ARCHIVED : Obstacle.OUTSIDE_AUDIENCE;
        }
    }

    private record VideoMaterial(VideoInstruction video) implements Material {
        @Override
        public boolean visibleTo(User user) {
            return VideoVisibility.isVisible(video, user);
        }

        @Override
        public OffsetDateTime publicationPendingFor(String target, OffsetDateTime now) {
            return null;
        }

        @Override
        public Obstacle obstacleFor(String target) {
            return video.isArchived() ? Obstacle.ARCHIVED : Obstacle.OUTSIDE_AUDIENCE;
        }
    }
}
