package ge.magti.portal.reminder;

import ge.magti.portal.audit.MutationAuditService;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.content.ItemTitleResolver;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.ReminderType;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.User;
import ge.magti.portal.org.OrgDirectoryQueryService;
import ge.magti.portal.query.CompleteResultGuard;
import ge.magti.portal.repository.ReadStatusRepository;
import ge.magti.portal.repository.ReminderRepository;
import ge.magti.portal.repository.RequiredReadingRepository;
import ge.magti.portal.repository.UserRepository;
import ge.magti.portal.security.Scope;
import ge.magti.portal.security.ScopeResolver;
import ge.magti.portal.util.DepartmentMatcher;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed-template reminder lifecycle, audience, cooldown and audit policy. */
@Service
public class ReminderService {

    static final int MANUAL_COOLDOWN_HOURS = 24;
    private static final String SYSTEM_ACTOR = "სისტემა";
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReminderRepository reminderRepository;
    private final RequiredReadingRepository readingRepository;
    private final ReadStatusRepository readStatusRepository;
    private final UserRepository userRepository;
    private final MutationAuditService mutationAuditService;
    private final ItemTitleResolver itemTitleResolver;
    private final ScopeResolver scopeResolver;
    private final OrgDirectoryQueryService orgDirectoryQueryService;
    /** Who an assignment addresses and whether it is in force (PO-40); it reads the directory. */
    private final MandatoryReach mandatoryReach;

    public ReminderService(
            ReminderRepository reminderRepository,
            RequiredReadingRepository readingRepository,
            ReadStatusRepository readStatusRepository,
            UserRepository userRepository,
            MutationAuditService mutationAuditService,
            ItemTitleResolver itemTitleResolver,
            ScopeResolver scopeResolver,
            OrgDirectoryQueryService orgDirectoryQueryService,
            MandatoryReach mandatoryReach) {
        this.reminderRepository = reminderRepository;
        this.readingRepository = readingRepository;
        this.readStatusRepository = readStatusRepository;
        this.userRepository = userRepository;
        this.mutationAuditService = mutationAuditService;
        this.itemTitleResolver = itemTitleResolver;
        this.scopeResolver = scopeResolver;
        this.orgDirectoryQueryService = orgDirectoryQueryService;
        this.mandatoryReach = mandatoryReach;
    }

    @Transactional(readOnly = true)
    public Page<Reminder> inbox(User recipient, int page, int size) {
        return reminderRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                recipient.getId(), PageRequest.of(page, size));
    }

    @Transactional
    public Reminder markRead(long reminderId, User recipient) {
        Reminder reminder = reminderRepository.findByIdAndRecipientUserId(reminderId, recipient.getId())
                .orElseThrow(() -> new ReminderNotFoundException("შეხსენება ვერ მოიძებნა"));
        if (reminder.getReadAt() == null) {
            Map<String, Object> before = MutationAuditService.reminderSnapshot(reminder);
            reminder.setReadAt(TbilisiTime.now());
            Reminder saved = reminderRepository.saveAndFlush(reminder);
            writeAudit(saved, recipient, "READ_REMINDER", before, Map.of());
            return saved;
        }
        return reminder;
    }

    /**
     * Delivers a reading's ASSIGNMENT reminders, once, when it is in force:
     * in the transaction that creates it, or from the sweep when an article
     * made mandatory in advance is published (PO-40). Out of force nothing is
     * sent and the reading keeps waiting; {@code creator} is null from the
     * sweep, which the audit records as the system.
     */
    @Transactional
    public int deliverAssignment(RequiredReading reading, User creator) {
        if (!mandatoryReach.reachOf(reading).inForce()) {
            return 0;
        }
        String title = titleOf(reading);

        int delivered = 0;
        // Obtain the complete bounded snapshot before the first reminder write,
        // so an oversized directory rolls the enclosing assignment transaction
        // back instead of creating a partial recipient set.
        for (User recipient : mandatoryReach.addressees(reading.getTargetDepartment())) {
            Reminder reminder = buildReadingReminder(reading, recipient, ReminderType.ASSIGNMENT, creator, title);
            Reminder saved = reminderRepository.saveAndFlush(reminder);
            writeAudit(saved, creator, "SEND_AUTOMATIC_REMINDER", null, Map.of("delivery", "ASSIGNMENT"));
            delivered++;
        }
        reading.setAssignmentDeliveredAt(TbilisiTime.now());
        readingRepository.saveAndFlush(reading);
        return delivered;
    }

    /** Pessimistically locked by {@link ReminderSweepService}. */
    @Transactional
    public int deliverScheduled(RequiredReading reading, ReminderType type) {
        if (type != ReminderType.DUE_SOON && type != ReminderType.OVERDUE) {
            throw new IllegalArgumentException("Scheduled reminder type required");
        }
        // PO-40: nobody is chased about material they cannot open now.
        if (!mandatoryReach.reachOf(reading).inForce()) {
            return 0;
        }
        String title = titleOf(reading);
        Set<Long> activeLeaderIds = activeLeaderIds();
        int delivered = 0;
        List<Reminder> seeds = CompleteResultGuard.enforce(
                reminderRepository.findByRequiredReadingIdAndTypeOrderByIdAsc(
                        reading.getId(), ReminderType.ASSIGNMENT, CompleteResultGuard.sentinelPage()));
        for (Reminder seed : seeds) {
            Long recipientId = seed.getRecipientUserId();
            if (recipientId == null
                    || reminderRepository.existsByRequiredReadingIdAndRecipientUserIdAndType(
                    reading.getId(), recipientId, type)
                    || readStatusRepository.findByUserIdAndRequiredReadingId(recipientId, reading.getId())
                    .map(ReadStatus::getStatus).filter("read"::equals).isPresent()) {
                continue;
            }
            User recipient = userRepository.findById(recipientId).orElse(null);
            if (recipient == null || !isEligible(recipient, activeLeaderIds)) {
                continue;
            }
            Reminder reminder = buildReadingReminder(reading, recipient, type, null, title);
            Reminder saved = reminderRepository.saveAndFlush(reminder);
            writeAudit(saved, null, "SEND_AUTOMATIC_REMINDER", null, Map.of("delivery", type.name()));
            delivered++;
        }
        return delivered;
    }

    @Transactional
    public Reminder sendManual(long recipientId, User actor) {
        User recipient = userRepository.findByIdForUpdate(recipientId)
                .orElseThrow(() -> new ReminderNotFoundException("თანამშრომელი ვერ მოიძებნა"));
        Scope scope = scopeResolver.resolveGroupLeadership(actor);
        if (!scope.unscoped() && (recipient.getTeamId() == null || !scope.includesTeam(recipient.getTeamId()))) {
            throw new ReminderScopeException("შეხსენების გაგზავნა მხოლოდ საკუთარი ჯგუფის თანამშრომლისთვის შეგიძლიათ");
        }
        if (!recipient.isActive()) {
            throw new InvalidReminderTargetException("არააქტიურ თანამშრომელს შეხსენება ვერ გაეგზავნება");
        }

        int pendingCount = pendingReadingCount(recipient);
        if (pendingCount == 0) {
            throw new InvalidReminderTargetException("თანამშრომელს შეუსრულებელი სავალდებულო მასალა არ აქვს");
        }

        OffsetDateTime now = TbilisiTime.now();
        reminderRepository.findFirstByRecipientUserIdAndTypeOrderByCreatedAtDesc(
                recipientId, ReminderType.MANUAL).ifPresent(previous -> {
            OffsetDateTime retryAt = previous.getCreatedAt().plusHours(MANUAL_COOLDOWN_HOURS);
            if (retryAt.isAfter(now)) {
                throw new ReminderCooldownException(
                        "ამ თანამშრომელთან შეხსენება უკვე გაიგზავნა; ხელახლა შესაძლებელი იქნება "
                                + DUE_FORMAT.format(retryAt) + "-დან", retryAt);
            }
        });

        Reminder reminder = new Reminder();
        reminder.setRecipientUserId(recipient.getId());
        reminder.setRecipientNameSnapshot(recipient.getName());
        reminder.setType(ReminderType.MANUAL);
        reminder.setContentSnapshot("გთხოვთ გაეცნოთ თქვენთვის მინიჭებულ სავალდებულო მასალებს.");
        reminder.setTriggeredByUserId(actor.getId());
        reminder.setTriggeredByNameSnapshot(actor.getName());
        reminder.setCreatedAt(now);
        Reminder saved = reminderRepository.saveAndFlush(reminder);
        writeAudit(saved, actor, "SEND_MANUAL_REMINDER", null, Map.of("pending_count", pendingCount));
        return saved;
    }

    private int pendingReadingCount(User recipient) {
        if (!isEligible(recipient, activeLeaderIds())) {
            return 0;
        }
        List<String> departments = targetDepartments(recipient);
        List<RequiredReading> addressed = CompleteResultGuard.enforce(
                readingRepository.findByTargetDepartmentIn(departments, CompleteResultGuard.sentinelPage()));
        // PO-40: only what is in force is outstanding.
        Set<Long> inForce = mandatoryReach.inForceIds(addressed);
        List<RequiredReading> readings = addressed.stream().filter(r -> inForce.contains(r.getId())).toList();
        if (readings.isEmpty()) {
            return 0;
        }
        Set<Long> readIds = readStatusRepository.findByUserIdAndRequiredReadingIdIn(
                        recipient.getId(), readings.stream().map(RequiredReading::getId).toList()).stream()
                .filter(status -> "read".equals(status.getStatus()))
                .map(ReadStatus::getRequiredReadingId)
                .collect(java.util.stream.Collectors.toSet());
        return (int) readings.stream().filter(reading -> !readIds.contains(reading.getId())).count();
    }

    private Reminder buildReadingReminder(
            RequiredReading reading, User recipient, ReminderType type, User actor, String title) {
        Reminder reminder = new Reminder();
        reminder.setRecipientUserId(recipient.getId());
        reminder.setRecipientNameSnapshot(recipient.getName());
        reminder.setRequiredReadingId(reading.getId());
        reminder.setType(type);
        reminder.setContentSnapshot(contentFor(type, title, reading.getDueDate()));
        reminder.setItemTypeSnapshot(reading.getItemType());
        reminder.setItemIdSnapshot(reading.getItemId());
        reminder.setItemTitleSnapshot(title);
        reminder.setDueAtSnapshot(reading.getDueDate());
        reminder.setTriggeredByUserId(actor == null ? null : actor.getId());
        reminder.setTriggeredByNameSnapshot(actor == null ? SYSTEM_ACTOR : actor.getName());
        reminder.setCreatedAt(TbilisiTime.now());
        return reminder;
    }

    private static String contentFor(ReminderType type, String title, OffsetDateTime dueAt) {
        String due = dueAt.withOffsetSameInstant(TbilisiTime.OFFSET).format(DUE_FORMAT);
        return switch (type) {
            case ASSIGNMENT -> "დაგემატათ სავალდებულო მასალა: „" + title + "“. ვადა: " + due + ".";
            case DUE_SOON -> "სავალდებულო მასალის „" + title + "“ ვადა 24 საათში იწურება.";
            case OVERDUE -> "სავალდებულო მასალა „" + title + "“ ვადაგადაცილებულია.";
            case MANUAL -> throw new IllegalArgumentException("Manual reminder has its own fixed template");
        };
    }

    private String titleOf(RequiredReading reading) {
        return itemTitleResolver.resolve(reading.getItemType(), reading.getItemId())
                .orElse("მასალა #" + reading.getItemId());
    }

    private static List<String> targetDepartments(User recipient) {
        String department = recipient.getDepartment();
        if (department == null || department.isBlank()) {
            return List.of("All");
        }
        String prefix = DepartmentMatcher.splitGroup(department).prefix();
        List<String> values = new ArrayList<>();
        values.add(department);
        if (prefix != null && !prefix.equals(department)) values.add(prefix);
        values.add("All");
        return values;
    }

    private static boolean isEligible(User user, Set<Long> activeLeaderIds) {
        return ge.magti.portal.compliance.ComplianceEligibilityService.resolve(
                user, activeLeaderIds.contains(user.getId()));
    }

    private void writeAudit(
            Reminder reminder,
            User actor,
            String action,
            Map<String, Object> before,
            Map<String, Object> extra) {
        Map<String, Object> after = new LinkedHashMap<>(MutationAuditService.reminderSnapshot(reminder));
        after.putAll(extra);
        if (actor == null) {
            mutationAuditService.recordSystemSuccess(
                    SYSTEM_ACTOR, action, "reminder", reminder.getId(),
                    reminder.getRecipientNameSnapshot(), before, after);
            return;
        }
        mutationAuditService.recordSuccess(
                actor, action, "reminder", reminder.getId(),
                reminder.getRecipientNameSnapshot(), before, after);
    }

    private Set<Long> activeLeaderIds() {
        return orgDirectoryQueryService.listActiveAssignmentsWithinLimit().stream()
                .map(LeadershipAssignment::getUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    public static class ReminderNotFoundException extends RuntimeException {
        public ReminderNotFoundException(String message) { super(message); }
    }
    public static class ReminderScopeException extends RuntimeException {
        public ReminderScopeException(String message) { super(message); }
    }
    public static class InvalidReminderTargetException extends RuntimeException {
        public InvalidReminderTargetException(String message) { super(message); }
    }
    public static class ReminderCooldownException extends RuntimeException {
        private final OffsetDateTime retryAt;
        public ReminderCooldownException(String message, OffsetDateTime retryAt) {
            super(message);
            this.retryAt = retryAt;
        }
        public OffsetDateTime retryAt() { return retryAt; }
    }
}
