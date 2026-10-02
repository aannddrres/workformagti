package ge.magti.portal.web;

import ge.magti.portal.compliance.ComplianceCalculator;
import ge.magti.portal.compliance.ComplianceQueryService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.compliance.RequiredReadingMutationService;
import ge.magti.portal.compliance.ReadingAcknowledgementService;
import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.reminder.ReminderService;
import ge.magti.portal.content.ItemDetail;
import ge.magti.portal.content.ItemKey;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.quiz.QuizGateChecker;
import ge.magti.portal.repository.ArticleReadReceiptRepository;
import ge.magti.portal.repository.ArticleRepository;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.security.PermissionChecker;
import ge.magti.portal.security.Scope;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.stats.ComplianceRecord;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * <p>PO-16 reminder delivery is durable, fixed-template and portal-only. It
 * commits atomically with a new assignment; no private message or SSE chat
 * path is involved.
 */
@RestController
public class ComplianceController {

    private static final String READING_NOT_FOUND = "სავალდებულო მასალა ვერ მოიძებნა";
    private static final String ITEM_NOT_FOUND = "მასალა ვერ მოიძებნა";
    private static final String READING_HAS_READ_RECEIPTS =
            "სავალდებულო მასალის წაშლა ვერ ხერხდება -- მომხმარებლებმა უკვე გაიცნეს იგი";
    private static final String READING_NOT_IN_FORCE = "ეს მასალა ახლა თქვენთვის სავალდებულო არ არის";
    private static final String DUE_BEFORE_PUBLICATION =
            "ვადა სტატიის გამოქვეყნების შემდეგ უნდა იყოს -- სავალდებულო გამოქვეყნებისას ჩაირთვება";

    private final ComplianceQueryService complianceQueryService;
    private final RequiredReadingRepository requiredReadingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final ArticleRepository articleRepository;
    private final ArticleReadReceiptRepository articleReadReceiptRepository;
    private final QuizGateChecker quizGateChecker;
    private final ReminderService reminderService;
    private final ItemTitleResolver itemTitleResolver;
    private final PermissionChecker permissionChecker;
    private final MutationAuditService mutationAuditService;
    private final RequiredReadingMutationService requiredReadingMutationService;
    private final ReadingAcknowledgementService readingAcknowledgementService;

    /** Optional so the DB-free test constructions need no change; Spring always sets it. */
    private ge.magti.portal.org.DepartmentTargets departmentTargets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDepartmentTargets(ge.magti.portal.org.DepartmentTargets departmentTargets) {
        this.departmentTargets = departmentTargets;
    }

    /** A 422 naming any audience department that reaches nobody (simulation, 2026-10-01). */
    private ResponseEntity<Map<String, String>> unknownDepartments(java.util.Collection<String> targets) {
        return departmentTargets == null ? null : departmentTargets.refusal(targets);
    }
    private final MandatoryReach mandatoryReach;
    private final ScopeResolver scopeResolver;

    public ComplianceController(
            ComplianceQueryService complianceQueryService,
            RequiredReadingRepository requiredReadingRepository,
            ReadStatusRepository readStatusRepository,
            ArticleRepository articleRepository,
            ArticleReadReceiptRepository articleReadReceiptRepository,
            QuizGateChecker quizGateChecker,
            ReminderService reminderService,
            ItemTitleResolver itemTitleResolver,
            PermissionChecker permissionChecker,
            MutationAuditService mutationAuditService,
            RequiredReadingMutationService requiredReadingMutationService,
            ReadingAcknowledgementService readingAcknowledgementService,
            MandatoryReach mandatoryReach,
            ScopeResolver scopeResolver) {
        this.complianceQueryService = complianceQueryService;
        this.requiredReadingRepository = requiredReadingRepository;
        this.readStatusRepository = readStatusRepository;
        this.articleRepository = articleRepository;
        this.articleReadReceiptRepository = articleReadReceiptRepository;
        this.quizGateChecker = quizGateChecker;
        this.reminderService = reminderService;
        this.itemTitleResolver = itemTitleResolver;
        this.permissionChecker = permissionChecker;
        this.mutationAuditService = mutationAuditService;
        this.requiredReadingMutationService = requiredReadingMutationService;
        this.readingAcknowledgementService = readingAcknowledgementService;
        this.mandatoryReach = mandatoryReach;
        this.scopeResolver = scopeResolver;
    }

    /** Port of get_my_readings (routers/compliance.py:30-113). */
    @GetMapping("/api/compliance/my-readings")
    public ResponseEntity<?> getMyReadings(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
        if (denial != null) {
            return denial;
        }
        // Management roles manage the system rather than consume operator-level
        // training content -- they get no required-reading items here.
        if (ComplianceCalculator.MANAGEMENT_ROLES.contains(user.getRole())) {
            return ResponseEntity.ok(List.of());
        }

        List<RequiredReading> addressed = CompleteResultGuard.enforce(
                requiredReadingRepository.findByTargetDepartmentIn(
                        DepartmentMatcher.visibilityTargets(user.getDepartment()),
                        CompleteResultGuard.sentinelPage()));
        // PO-40: an assignment this operator could not open is not theirs to
        // owe -- a draft, an archived item, a schedule still ahead, or a
        // department the item does not address.
        Set<Long> inForce = mandatoryReach.inForceIds(addressed);
        List<RequiredReading> readings = addressed.stream().filter(r -> inForce.contains(r.getId())).toList();
        if (readings.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        Map<ItemKey, ItemDetail> details = itemTitleResolver.resolveDetailsBulk(
                readings.stream().map(r -> new ItemKey(r.getItemType(), r.getItemId())).toList());

        Map<Long, ReadStatus> statusByReadingId = readStatusRepository
                .findByUserIdAndRequiredReadingIdIn(user.getId(), readings.stream().map(RequiredReading::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(ReadStatus::getRequiredReadingId, s -> s, (a, b) -> a));

        Map<Long, Integer> currentArticleVersions = articleRepository.findAllById(readings.stream()
                        .filter(r -> "article".equals(r.getItemType())).map(RequiredReading::getItemId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(Article::getId, Article::getVersion));

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
            String itemTitle = detail != null ? detail.title()
                    : (r.getItemTitleSnapshot() != null ? r.getItemTitleSnapshot() : ("Item #" + r.getItemId()));
            String itemContent = detail != null ? detail.content() : "Content not available.";
            // F-2: flag material edited after this operator acknowledged it.
            // The acknowledgement stays valid -- it records a real event on a
            // real date -- but the list now says the text has moved on, so
            // "read" no longer quietly means "read the current version".
            boolean changedSinceRead;
            if ("article".equals(r.getItemType())) {
                // An article has versions, and a receipt per version read. Its
                // updated_at also moves on a retarget, a status change or a
                // "verified" stamp, which flagged unchanged text as changed --
                // and once confirming again was possible, asked people to
                // re-read text nobody had touched (simulation, 2026-10-01).
                Integer version = currentArticleVersions.get(r.getItemId());
                changedSinceRead = readAt != null && version != null
                        && articleReadReceiptRepository.findByArticleIdSnapshotAndArticleVersionAndOperatorId(
                                r.getItemId(), version, user.getId()).isEmpty();
            } else {
                changedSinceRead = readAt != null
                        && detail != null
                        && detail.updatedAt() != null
                        && detail.updatedAt().isAfter(readAt);
            }
            results.add(new MyReadingResponse(
                    RequiredReadingResponse.from(r), currentStatus, readAt, isOverdue,
                    itemTitle, itemContent, changedSinceRead));
        }
        return ResponseEntity.ok(results);
    }

    /** Port of get_my_progress (routers/compliance.py:116-143). */
    @GetMapping("/api/compliance/my-progress")
    public ResponseEntity<?> getMyProgress(@AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
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
        ResponseEntity<Map<String, String>> denial = Guards.requireAuthenticated(user);
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
        MandatoryReach.Reach reach = mandatoryReach.reachOf(reading);
        if (!reach.inForce()) {
            // A missing item keeps its old answer; anything else is an
            // obligation not in force (PO-40), so there is nothing to confirm.
            if (reach.obstacle() == MandatoryReach.Obstacle.MISSING) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", READING_NOT_IN_FORCE));
        }

        Article readingArticle = null;
        if ("article".equals(reading.getItemType())) {
            readingArticle = articleRepository.findById(reading.getItemId()).orElse(null);
            if (readingArticle == null) {
                // Keep the assignment and its past read evidence for audit,
                // but never create a new acknowledgement for payload the
                // employee can no longer open (trashed or purged).
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
            }
            ResponseEntity<Map<String, String>> quizGate = quizGateChecker.denialFor(readingArticle, user);
            if (quizGate != null) {
                return quizGate;
            }
        }

        OffsetDateTime itemUpdatedAt = null;
        if (readingArticle == null) {
            ItemDetail detail = itemTitleResolver.resolveDetailsBulk(
                    List.of(new ItemKey(reading.getItemType(), reading.getItemId())))
                    .get(new ItemKey(reading.getItemType(), reading.getItemId()));
            itemUpdatedAt = detail == null ? null : detail.updatedAt();
        }
        ReadStatus savedStat = readingAcknowledgementService.acknowledgeRequiredReading(
                reading, readingArticle, user, itemUpdatedAt);
        return ResponseEntity.ok(ReadStatusResponse.from(savedStat));
    }

    /** Port of create_required_reading (routers/compliance.py:285-316). */
    @PostMapping("/api/compliance/required-readings")
    @Transactional
    public ResponseEntity<?> createRequiredReading(
            @Valid @RequestBody RequiredReadingRequest request, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireComplianceAssign(user);
        if (denial != null) {
            return denial;
        }
        if (isPrivateDraftOfAnother(request.itemType(), request.itemId(), user)) {
            // The title snapshot below would carry a colleague's private
            // draft into every assignee's reading list and reminders (PO-34).
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", ITEM_NOT_FOUND));
        }
        ResponseEntity<Map<String, String>> unknownTargets = unknownDepartments(List.of(request.targetDepartmentOrDefault()));
        if (unknownTargets != null) {
            return unknownTargets;
        }
        // PO-40: nobody is assigned what they could not open. Refused whole,
        // with the people it would have missed named, rather than quietly
        // narrowed: the editor chose this department and should know.
        MandatoryReach.Assessment assessment = mandatoryReach.assess(
                request.itemType(), request.itemId(), request.targetDepartmentOrDefault());
        ResponseEntity<?> refusal = refusalFor(assessment, request.dueDate(), true);
        if (refusal != null) {
            return refusal;
        }
        return ResponseEntity.ok(RequiredReadingResponse.from(saveReading(request, user)));
    }

    /**
     * A reading for a department a mandatory article's audience gained while
     * nobody could open it -- archived, unpublished, or scheduled past its due
     * date. Not refused like an editor's own assignment: the editor chose the
     * department and the obligation already exists for the others. It is
     * kept out of force and comes into force with the article, its assignment
     * going out from the reminder sweep then (PO-40 §2 and §4, owner,
     * 2026-10-02). Before, it was dropped without a word, and restoring the
     * article left the new department owing nothing.
     */
    public RequiredReading addReadingOutOfForce(RequiredReadingRequest request, User user) {
        return saveReading(request, user);
    }

    private RequiredReading saveReading(RequiredReadingRequest request, User user) {
        RequiredReading reading = new RequiredReading();
        reading.setItemType(request.itemType());
        reading.setItemId(request.itemId());
        reading.setItemTitleSnapshot(itemTitleResolver.resolve(request.itemType(), request.itemId())
                .orElse("მასალა #" + request.itemId()));
        reading.setTargetDepartment(request.targetDepartmentOrDefault());
        reading.setDueDate(normalizeDueDate(request.dueDate()));
        reading.setPriority(request.priorityOrDefault());
        RequiredReading saved = requiredReadingRepository.saveAndFlush(reading);

        // PO-16 makes assignment delivery part of the durable contract. The
        // reading and its fixed reminders therefore commit atomically -- now,
        // or, for one not yet in force, when the reminder sweep sees it come
        // into force (PO-40). deliverAssignment is a no-op until then.
        reminderService.deliverAssignment(saved, user);
        mutationAuditService.recordSuccess(
                user, "CREATE_REQUIRED_READING", "required_reading", saved.getId(),
                saved.getItemTitleSnapshot(), null,
                MutationAuditService.requiredReadingSnapshot(saved));
        return saved;
    }

    /** Port of get_required_reading_for_item (routers/compliance.py:319-335) -- Optional response, literal JSON null when absent. */
    @GetMapping("/api/compliance/required-readings/by-item/{itemType}/{itemId}")
    public ResponseEntity<?> getRequiredReadingForItem(
            @PathVariable String itemType, @PathVariable Long itemId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireComplianceAssign(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> rr = isPrivateDraftOfAnother(itemType, itemId, user) ? Optional.empty()
                : requiredReadingRepository.findFirstByItemTypeAndItemIdOrderByIdAsc(itemType, itemId);
        if (rr.isEmpty()) {
            // FastAPI's Optional[schema] returns the literal 4-char body "null";
            // ResponseEntity.ok(null) would write zero bytes and break a
            // client's response.json(). Same fix as GET .../note.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null");
        }
        return ResponseEntity.ok(RequiredReadingResponse.from(rr.get()));
    }

    /**
     * PO-40: who a mandatory item binds now, and who it will bind when a
     * scheduled article is published. The editor's form reads this before
     * saving a change -- archiving, unpublishing, a department removed -- so
     * the people who would stop owing it are named first. Same gate and the
     * same private-draft answer as the by-item lookup beside it.
     */
    @GetMapping("/api/compliance/required-readings/by-item/{itemType}/{itemId}/addressees")
    public ResponseEntity<?> getRequiredReadingAddressees(
            @PathVariable String itemType, @PathVariable Long itemId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireComplianceAssign(user);
        if (denial != null) {
            return denial;
        }
        if (isPrivateDraftOfAnother(itemType, itemId, user)) {
            return ResponseEntity.ok(MandatoryAddresseesResponse.empty());
        }
        List<RequiredReading> readings = requiredReadingRepository.findByItemTypeAndItemId(itemType, itemId);
        Map<Long, MandatoryReach.Reach> reach = mandatoryReach.reachOf(readings);
        List<RequiredReading> binding = readings.stream()
                .filter(r -> reach.get(r.getId()).state() != MandatoryReach.State.SUSPENDED)
                .toList();
        if (binding.isEmpty()) {
            return ResponseEntity.ok(MandatoryAddresseesResponse.empty());
        }
        Set<Long> confirmed = CompleteResultGuard.enforce(readStatusRepository.findByRequiredReadingIdInAndStatus(
                        binding.stream().map(RequiredReading::getId).toList(), "read",
                        CompleteResultGuard.sentinelPage())).stream()
                .map(ReadStatus::getUserId)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, List<User>> byTarget = mandatoryReach.addresseesByTarget(
                binding.stream().map(RequiredReading::getTargetDepartment).toList());
        Map<Long, User> people = new LinkedHashMap<>();
        Map<Long, Boolean> pendingOnly = new LinkedHashMap<>();
        for (RequiredReading r : binding) {
            boolean pending = reach.get(r.getId()).state() == MandatoryReach.State.PENDING;
            String target = r.getTargetDepartment() == null || r.getTargetDepartment().isBlank()
                    ? DepartmentMatcher.WILDCARD_TARGET : r.getTargetDepartment();
            for (User addressee : byTarget.getOrDefault(target, List.of())) {
                people.putIfAbsent(addressee.getId(), addressee);
                // In force wins over pending when two readings reach one person.
                pendingOnly.merge(addressee.getId(), pending, Boolean::logicalAnd);
            }
        }

        Map<String, int[]> byDepartment = new java.util.TreeMap<>();
        List<MandatoryAddresseesResponse.Entry> named = new java.util.ArrayList<>();
        Scope namedScope = scopeResolver.resolveGroupLeadership(user);
        int pendingTotal = 0;
        for (User person : people.values()) {
            boolean pending = pendingOnly.get(person.getId());
            boolean read = confirmed.contains(person.getId());
            int[] row = byDepartment.computeIfAbsent(
                    person.getDepartment() == null ? "—" : person.getDepartment(), d -> new int[3]);
            row[pending ? 1 : 0]++;
            row[2] += read ? 1 : 0;
            pendingTotal += pending ? 1 : 0;
            if (namedScope.includesTeam(person.getTeamId())) {
                named.add(new MandatoryAddresseesResponse.Entry(
                        person.getId(), person.getName(), person.getDepartment(), read, pending));
            }
        }
        List<MandatoryAddresseesResponse.DepartmentRow> departments = byDepartment.entrySet().stream()
                .map(e -> new MandatoryAddresseesResponse.DepartmentRow(
                        e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
        return ResponseEntity.ok(new MandatoryAddresseesResponse(
                people.size() - pendingTotal, pendingTotal, departments, List.copyOf(named)));
    }

    /** Port of update_required_reading (routers/compliance.py:338-351). */
    @PutMapping("/api/compliance/required-readings/{readingId}")
    @Transactional
    public ResponseEntity<?> updateRequiredReading(
            @PathVariable("readingId") Long readingId, @Valid @RequestBody RequiredReadingRequest request,
            @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireComplianceAssign(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> found = requiredReadingRepository.findById(readingId);
        // An assignment over a colleague's private draft is theirs to change,
        // as the draft is -- and its response carries the title (PO-34, D2).
        if (found.isEmpty()
                || isPrivateDraftOfAnother(found.get().getItemType(), found.get().getItemId(), user)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
        }
        RequiredReading reading = found.get();
        if (!request.targetDepartmentOrDefault().equals(reading.getTargetDepartment())) {
            ResponseEntity<Map<String, String>> unknownTargets =
                    unknownDepartments(List.of(request.targetDepartmentOrDefault()));
            if (unknownTargets != null) {
                return unknownTargets;
            }
        }
        Map<String, Object> before = MutationAuditService.requiredReadingSnapshot(reading);

        // BL-04: read_statuses is keyed on required_reading_id (V22:11), not
        // on the item. So re-pointing an existing reading at a different
        // article carried every "read" status across with it -- assign new
        // material this way and the compliance dashboard shows it at 100%
        // the instant it is saved, for people who have never seen it. That
        // is the one number this product exists to report, wrong in the
        // direction that hides the problem.
        //
        // Rejected rather than silently clearing the statuses: clearing is
        // also a large, invisible mutation (it resets everyone's history for
        // that obligation), and "this is a different obligation" is what the
        // create endpoint is for. Deleting and re-creating makes the intent
        // explicit and leaves both actions in the audit trail.
        boolean repointed = !reading.getItemType().equals(request.itemType())
                || !reading.getItemId().equals(request.itemId());
        if (repointed) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "detail", "სავალდებულო მასალის სხვა ერთეულზე გადამისამართება შეუძლებელია — "
                            + "წაკითხვის სტატუსები მასზეა მიბმული. წაშალეთ და შექმენით ახალი."));
        }

        // PO-40: a new target must be one the item reaches. The same target
        // may stay while the item is out of reach -- that is how an editor
        // archives or unpublishes mandatory material, and the obligation is
        // then simply not in force until the item is visible again.
        boolean retargeted = !java.util.Objects.equals(
                reading.getTargetDepartment(), request.targetDepartmentOrDefault());
        MandatoryReach.Assessment assessment = mandatoryReach.assess(
                request.itemType(), request.itemId(), request.targetDepartmentOrDefault());
        ResponseEntity<?> refusal = refusalFor(assessment, request.dueDate(), retargeted);
        if (refusal != null) {
            return refusal;
        }

        reading.setTargetDepartment(request.targetDepartmentOrDefault());
        reading.setDueDate(normalizeDueDate(request.dueDate()));
        reading.setPriority(request.priorityOrDefault());
        RequiredReading saved = requiredReadingRepository.saveAndFlush(reading);

        // BL-04's other half: this endpoint wrote no audit row at all, so a
        // changed deadline -- the thing that decides who counts as overdue --
        // left no record of who moved it or when.
        mutationAuditService.recordSuccess(
                user, "UPDATE_REQUIRED_READING", "required_reading", saved.getId(),
                saved.getItemTitleSnapshot(), before,
                MutationAuditService.requiredReadingSnapshot(saved));

        return ResponseEntity.ok(RequiredReadingResponse.from(saved));
    }

    /** Port of delete_required_reading (routers/compliance.py:354-364). */
    @DeleteMapping("/api/compliance/required-readings/{readingId}")
    public ResponseEntity<?> deleteRequiredReading(
            @PathVariable("readingId") Long readingId, @AuthenticationPrincipal User user) {
        ResponseEntity<Map<String, String>> denial = requireComplianceAssign(user);
        if (denial != null) {
            return denial;
        }
        Optional<RequiredReading> found = requiredReadingRepository.findById(readingId);
        if (found.isEmpty()
                || isPrivateDraftOfAnother(found.get().getItemType(), found.get().getItemId(), user)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", READING_NOT_FOUND));
        }
        try {
            requiredReadingMutationService.delete(found.get(), user);
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", READING_HAS_READ_RECEIPTS));
        }
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

    /**
     * PO-40's two refusals: an assignment some or all of whose target could
     * not open the item (409, naming them), and a scheduled article's deadline
     * that falls at or before its publication (422) -- it would be born
     * overdue the moment it came into force.
     *
     * @param refuseOutOfReach false for an update that keeps its target, which
     *                         may leave an obligation out of force on purpose
     */
    private static ResponseEntity<?> refusalFor(
            MandatoryReach.Assessment assessment, OffsetDateTime dueDate, boolean refuseOutOfReach) {
        MandatoryReach.Reach reach = assessment.reach();
        if (reach.state() == MandatoryReach.State.SUSPENDED && refuseOutOfReach) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(MandatoryReachRefusalResponse.from(reach.obstacle(), assessment.blocked()));
        }
        if (reach.state() == MandatoryReach.State.PENDING && dueDate != null
                && !dueDate.isAfter(reach.startsAt())) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", DUE_BEFORE_PUBLICATION));
        }
        return null;
    }

    /**
     * SEC-06: {@code compliance.assign} sat in the catalog as a switch the
     * admin UI offered, validated and persisted -- and consulted nowhere.
     * Assigning mandatory reading gated on the ROLE alone, so granting or
     * revoking the permission changed nothing.
     *
     * <p>Applied to create, change, delete and the by-item lookup. The lookup
     * is part of the same administration drawer, so its visibility follows
     * the same capability instead of a separate role-only rule.
     */
    private ResponseEntity<Map<String, String>> requireComplianceAssign(User user) {
        ResponseEntity<Map<String, String>> authFailure = Guards.requireAuthenticated(user);
        if (authFailure != null) {
            return authFailure;
        }
        if (!permissionChecker.hasPermission(user, Permission.COMPLIANCE_ASSIGN)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("detail", "წვდომა უარყოფილია: არასაკმარისი უფლებები"));
        }
        return null;
    }

    /** Another author's private draft, which this caller may not address at all (PO-34, D2). */
    private boolean isPrivateDraftOfAnother(String itemType, Long itemId, User user) {
        return itemTitleResolver.isPrivateDraftOfAnother(itemType, itemId, user);
    }
}
