package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.content.ItemDetail;
import ge.magti.portal.content.ItemKey;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.repository.MessageRepository;
import ge.magti.portal.repository.NewsRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.TagRepository;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mirrors routers/platform.py's remaining two data endpoints, not covered by
 * any other controller: the normalized tag vocabulary and the combined
 * bell-icon notifications payload. ({@code /api/health} and
 * {@code /api/upload}, platform.py's other two API endpoints, already live
 * in {@link HealthController}/{@link UploadController}; the rest of
 * platform.py is server-rendered HTML page routes with no Angular
 * equivalent.)
 *
 * <p>Found missing, undocumented, during the 2026-08-11 PM migration-gap
 * audit -- unlike every other deliberate deferral in this codebase, neither
 * endpoint had a decision or a Javadoc trail, so both are ported now rather
 * than left as an unexplained gap.
 */
@RestController
public class PlatformController {

    private final TagRepository tagRepository;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final NewsRepository newsRepository;
    private final MessageRepository messageRepository;
    private final ItemTitleResolver itemTitleResolver;

    public PlatformController(
            TagRepository tagRepository,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            NewsRepository newsRepository,
            MessageRepository messageRepository,
            ItemTitleResolver itemTitleResolver) {
        this.tagRepository = tagRepository;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.newsRepository = newsRepository;
        this.messageRepository = messageRepository;
        this.itemTitleResolver = itemTitleResolver;
    }

    /** Port of get_tags (routers/platform.py:269-281). */
    @GetMapping("/api/tags")
    public ResponseEntity<?> getTags(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        return ResponseEntity.ok(tagRepository.findAllByOrderByName().stream().map(TagResponse::from).toList());
    }

    /** Port of get_notifications_summary (routers/platform.py:125-197). */
    @GetMapping("/api/notifications/summary")
    public ResponseEntity<?> getNotificationsSummary(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }

        OffsetDateTime now = TbilisiTime.now();

        List<UnreadReadingSummaryItem> unreadReadings = new ArrayList<>();
        if (!ComplianceCalculator.MANAGEMENT_ROLES.contains(user.getRole())) {
            String deptPrefix = DepartmentMatcher.splitGroup(user.getDepartment()).prefix();
            List<RequiredReading> readings = requiredReadingRepository.findByTargetDepartmentIn(
                    List.of(user.getDepartment(), deptPrefix, "All"));
            if (!readings.isEmpty()) {
                Map<ItemKey, ItemDetail> details = itemTitleResolver.resolveDetailsBulk(
                        readings.stream().map(r -> new ItemKey(r.getItemType(), r.getItemId())).toList());
                Map<Long, ReadStatus> statusByReadingId = readStatusRepository
                        .findByUserIdAndRequiredReadingIdIn(
                                user.getId(), readings.stream().map(RequiredReading::getId).toList())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ReadStatus::getRequiredReadingId, s -> s, (a, b) -> a));

                for (RequiredReading r : readings) {
                    ReadStatus stat = statusByReadingId.get(r.getId());
                    if (stat != null && "read".equals(stat.getStatus())) {
                        continue;
                    }
                    boolean overdue = r.getDueDate() != null && r.getDueDate().isBefore(now);
                    ItemDetail detail = details.get(new ItemKey(r.getItemType(), r.getItemId()));
                    String title = detail != null ? detail.title() : ("მასალა #" + r.getItemId());
                    unreadReadings.add(new UnreadReadingSummaryItem(
                            r.getId(), r.getItemType(), r.getItemId(), title, r.getDueDate(), overdue));
                }
            }
        }

        OffsetDateTime sevenDaysAgo = now.minusDays(7);
        List<News> newsList = Role.CONTENT_ADMIN_ROLES.contains(user.getRole())
                ? newsRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(sevenDaysAgo, PageRequest.of(0, 10))
                : newsRepository.findByCreatedAtGreaterThanEqualAndTargetDepartmentInOrderByCreatedAtDesc(
                        sevenDaysAgo, List.of(user.getDepartment(), "All"), PageRequest.of(0, 10));
        List<RecentNewsSummaryItem> recentNews = newsList.stream()
                .map(n -> new RecentNewsSummaryItem(n.getId(), n.getTitle(), n.getTargetDepartment(), n.getCreatedAt()))
                .toList();

        long unreadMessages = messageRepository.countByUserIdAndReadFalse(user.getId());

        return ResponseEntity.ok(new NotificationsSummaryResponse(unreadReadings, recentNews, (int) unreadMessages));
    }

    private static ResponseEntity<Map<String, String>> requireAuthenticated(User user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("detail", "Could not validate credentials"));
        }
        return null;
    }
}
