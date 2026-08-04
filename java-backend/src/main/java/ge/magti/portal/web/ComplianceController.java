package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.RequiredReadingNotifier;
import ge.magti.portal.content.ItemDetail;
import ge.magti.portal.content.ItemKey;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.quiz.QuizGateChecker;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mirrors routers/compliance.py -- all 7 endpoints: the operator's own
 * required-reading list + progress widget, the mark-read acknowledgement
 * (with quiz gate + read-receipt bridge), and the admin required-readings
 * CRUD (+ by-item lookup).
 *
 * <p>Access split mirrors Python exactly: my-readings / my-progress /
 * mark-read are {@code get_current_user} (any active role); the four
 * required-readings management endpoints are {@code get_current_admin_user}
 * (content_admin / system_admin).
 *
 * <p><b>Deliberate gaps, consistent with every Content controller:</b> no
 * automatic audit row on mark-read (Python's {@code log_audit MARK_READ},
 * compliance.py:186 -- the Audit domain's own write surface isn't wired
 * here yet); the create-notification fan-out writes the durable inbox
 * {@link ge.magti.portal.domain.Message} rows but not the SSE real-time
 * events (see {@link RequiredReadingNotifier}).
 */
@RestController
public class ComplianceController {

    private static final Logger log = LoggerFactory.getLogger(ComplianceController.class);
    private static final String READING_NOT_FOUND = "სავალდებულო მასალა ვერ მოიძებნა";

    private final ComplianceQueryService complianceQueryService;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final ArticleRepository articleRepository;
    private final ArticleReadReceiptRepository articleReadReceiptRepository;
    private final QuizGateChecker quizGateChecker;
    private final RequiredReadingNotifier requiredReadingNotifier;
    private final ItemTitleResolver itemTitleResolver;

    public ComplianceController(
            ComplianceQueryService complianceQueryService,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ArticleRepository articleRepository,
            ArticleReadReceiptRepository articleReadReceiptRepository,
            QuizGateChecker quizGateChecker,
            RequiredReadingNotifier requiredReadingNotifier,
            ItemTitleResolver itemTitleResolver) {
        this.complianceQueryService = complianceQueryService;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.articleRepository = articleRepository;
        this.articleReadReceiptRepository = articleReadReceiptRepository;
        this.quizGateChecker = quizGateChecker;
        this.requiredReadingNotifier = requiredReadingNotifier;
        this.itemTitleResolver = itemTitleResolver;
    }

    /** Port of get_my_readings (routers/compliance.py:30-113). */
    @GetMapping("/api/compliance/my-readings")
    public ResponseEntity<?> getMyReadings(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        // Management roles manage the system rather than consume operator-level
        // training content -- they get no required-reading items here.
        if (ComplianceCalculator.MANAGEMENT_ROLES.contains(user.getRole())) {
            return ResponseEntity.ok(List.of());
        }

        String deptPrefix = DepartmentMatcher.splitGroup(user.getDepartment()).prefix();
        List<RequiredReading> readings = requiredReadingRepository.findByTargetDepartmentIn(
                List.of(user.getDepartment(), deptPrefix, "All"));
        if (readings.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Map<ItemKey, ItemDetail> details = itemTitleResolver.resolveDetailsBulk(
                readings.stream().map(r -> new ItemKey(r.getItemType(), r.getItemId())).toList());

        Map<Long, ReadStatus> statusByReadingId = readStatusRepository
                .findByUserIdAndRequiredReadingIdIn(user.getId(), readings.stream().map(RequiredReading::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(ReadStatus::getRequiredReadingId, s -> s, (a, b) -> a));

        OffsetDateTime now = TbilisiTime.now();
        List<MyReadingResponse> results = new java.util.ArrayList<>();
        for (RequiredReading r : readings) {
            ReadStatus stat = statusByReadingId.get(r.getId());
            String currentStatus = stat != null ? stat.getStatus() : "unread";
            OffsetDateTime readAt = stat != null ? stat.getReadAt() : null;
            boolean isOverdue = "unread".equals(currentStatus) && r.getDueDate() != null && r.getDueDate().isBefore(now);
            if (isOverdue) {
                currentStatus = "overdue";
            }
            ItemDetail detail = details.get(new ItemKey(r.getItemType(), r.getItemId()));
            String itemTitle = detail != null ? detail.title() : ("Item #" + r.getItemId());
            String itemContent = detail != null ? detail.content() : "Content not available.";
            results.add(new MyReadingResponse(
                    RequiredReadingResponse.from(r), currentStatus, readAt, isOverdue, itemTitle, itemContent));
        }
        return ResponseEntity.ok(results);
    }

    /** Port of get_my_progress (routers/compliance.py:116-143). */
    @GetMapping("/api/compliance/my-progress")
    public ResponseEntity<?> getMyProgress(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        List<ComplianceRecord> records = complianceQueryService.computeForUser(user.getId());
        if (records.isEmpty()) {
            return ResponseEntity.ok(new MyProgressResponse(0, 0, 0, 0));
        }
        ComplianceRecord record = records.get(0);
        int total = record.progress().requiredCount();
        int completed = record.progress().readCount();
        return ResponseEntity.ok(new MyProgressResponse(total, completed, total - completed, record.progress().percentage()));
    }

    /** Port of mark_read (routers/compliance.py:146-197). */
    @PostMapping("/api/compliance/mark-read/{readingId}")
    @Transactional
    public ResponseEntity<?> markRead(@PathVariable("readingId") Long readingId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> found = requiredReadingRepository.findById(readingId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
        }
        RequiredReading reading = found.get();

        if (!DepartmentMatcher.matches(user.getDepartment(), List.of(reading.getTargetDepartment()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "ეს მასალა თქვენს დეპარტამენტს არ ეხება"));
        }

        Article readingArticle = null;
        if ("article".equals(reading.getItemType())) {
            readingArticle = articleRepository.findById(reading.getItemId()).orElse(null);
            if (readingArticle != null) {
                ResponseEntity<Map<String, String>> quizGate = quizGateChecker.denialFor(readingArticle, user);
                if (quizGate != null) {
                    return quizGate;
                }
            }
        }

        ReadStatus stat = readStatusRepository.findByUserIdAndRequiredReadingId(user.getId(), readingId)
                .orElseGet(() -> {
                    ReadStatus fresh = new ReadStatus();
                    fresh.setUserId(user.getId());
                    fresh.setRequiredReadingId(readingId);
                    return fresh;
                });
        stat.setStatus("read");
        stat.setReadAt(TbilisiTime.now());
        stat.setOperatorDepartmentSnapshot(user.getDepartment());
        ReadStatus savedStat = readStatusRepository.saveAndFlush(stat);
        ReadStatusResponse response = ReadStatusResponse.from(savedStat);

        // Receipt bridge (routers/compliance.py:190-195): a mandatory-reading
        // acknowledgement of an article is also a versioned read receipt. The
        // atomic MERGE (clearAutomatically) runs after the ReadStatus flush
        // above; single @Transactional makes Python's "commit first so the
        // retry can't discard the ReadStatus" ordering moot -- there's no
        // rollback-retry path here.
        if (readingArticle != null) {
            articleReadReceiptRepository.upsert(readingArticle.getId(), readingArticle.getTitle(),
                    readingArticle.getVersion(), user.getId(), user.getName(), user.getEmail(),
                    user.getDepartment(), TbilisiTime.now());
        }
        return ResponseEntity.ok(response);
    }

    /** Port of create_required_reading (routers/compliance.py:285-316). */
    @PostMapping("/api/compliance/required-readings")
    @Transactional
    public ResponseEntity<?> createRequiredReading(
            @Valid @RequestBody RequiredReadingRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }
        RequiredReading reading = new RequiredReading();
        reading.setItemType(request.itemType());
        reading.setItemId(request.itemId());
        reading.setTargetDepartment(request.targetDepartmentOrDefault());
        reading.setDueDate(normalizeDueDate(request.dueDate()));
        reading.setPriority(request.priorityOrDefault());
        RequiredReading saved = requiredReadingRepository.saveAndFlush(reading);

        // Best-effort, matching Python's try/except (compliance.py:309-313): a
        // notification failure must not fail the reading creation itself.
        try {
            requiredReadingNotifier.notifyAffectedUsers(saved, user.getId());
        } catch (Exception e) {
            log.warn("Error auto-generating mandatory-reading notifications for reading {}: {}", saved.getId(), e.toString());
        }

        return ResponseEntity.ok(RequiredReadingResponse.from(saved));
    }

    /** Port of get_required_reading_for_item (routers/compliance.py:319-335) -- Optional response, literal JSON null when absent. */
    @GetMapping("/api/compliance/required-readings/by-item/{itemType}/{itemId}")
    public ResponseEntity<?> getRequiredReadingForItem(
            @PathVariable String itemType, @PathVariable Long itemId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> rr = requiredReadingRepository.findFirstByItemTypeAndItemIdOrderByIdAsc(itemType, itemId);
        if (rr.isEmpty()) {
            // FastAPI's Optional[schema] returns the literal 4-char body "null";
            // ResponseEntity.ok(null) would write zero bytes and break a
            // client's response.json(). Same fix as GET .../note.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
        }
        return ResponseEntity.ok(RequiredReadingResponse.from(rr.get()));
    }

    /** Port of update_required_reading (routers/compliance.py:338-351). */
    @PutMapping("/api/compliance/required-readings/{readingId}")
    public ResponseEntity<?> updateRequiredReading(
            @PathVariable("readingId") Long readingId, @Valid @RequestBody RequiredReadingRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> found = requiredReadingRepository.findById(readingId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
        }
        RequiredReading reading = found.get();
        reading.setItemType(request.itemType());
        reading.setItemId(request.itemId());
        reading.setTargetDepartment(request.targetDepartmentOrDefault());
        reading.setDueDate(normalizeDueDate(request.dueDate()));
        reading.setPriority(request.priorityOrDefault());
        RequiredReading saved = requiredReadingRepository.save(reading);
        return ResponseEntity.ok(RequiredReadingResponse.from(saved));
    }

    /** Port of delete_required_reading (routers/compliance.py:354-364). */
    @DeleteMapping("/api/compliance/required-readings/{readingId}")
    public ResponseEntity<?> deleteRequiredReading(
            @PathVariable("readingId") Long readingId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireContentAdmin(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> found = requiredReadingRepository.findById(readingId);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
        }
        requiredReadingRepository.delete(found.get());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Normalises a client-supplied deadline to the Tbilisi offset so the
     * in-memory entity matches how it will be stored (TbilisiTimestampConverter
     * writes Tbilisi wall-clock) and how a later GET reads it back. Jackson
     * deserialises an incoming offset datetime adjusted to UTC by default, so
     * without this the create/update response would show a different wall-clock
     * (though the same instant) than the persisted-then-reread value. The
     * instant is preserved either way; this only fixes the displayed
     * offset/date. First client-supplied datetime field in the port -- the
     * same treatment will apply to any other (e.g. Articles' scheduled
     * published_at, not retrofitted here).
     */
    private static OffsetDateTime normalizeDueDate(OffsetDateTime dueDate) {
        return dueDate == null ? null : dueDate.withOffsetSameInstant(TbilisiTime.OFFSET);
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
