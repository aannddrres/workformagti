package ge.magti.portal.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ge.magti.portal.domain.Article;
import ge.magti.portal.domain.AuditLog;
import ge.magti.portal.domain.BroadcastAnnouncement;
import ge.magti.portal.domain.Category;
import ge.magti.portal.domain.LeadershipAssignment;
import ge.magti.portal.domain.News;
import ge.magti.portal.domain.Reminder;
import ge.magti.portal.domain.RequiredReading;
import ge.magti.portal.domain.ReadStatus;
import ge.magti.portal.domain.QuizAttempt;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.domain.VideoInstruction;
import ge.magti.portal.repository.AuditLogRepository;
import ge.magti.portal.util.TbilisiTime;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes reconstructable, transaction-critical audit evidence for business
 * mutations. Callers must invoke this inside the same transaction as the
 * business change. {@code saveAndFlush} is intentional: a constraint, trigger,
 * or chain failure is observed before the endpoint returns, so the surrounding
 * transaction cannot commit the mutation without its evidence.
 */
@Service
public class MutationAuditService {

    private static final int DETAILS_SCHEMA_VERSION = 1;

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MutationAuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void recordSuccess(
            User actor,
            String action,
            String itemType,
            Long itemId,
            String itemName,
            Map<String, Object> before,
            Map<String, Object> after) {
        recordResult(
                actor, action, itemType, itemId, itemName,
                "SUCCESS", null, before, after, null, null);
    }

    public void recordResult(
            User actor,
            String action,
            String itemType,
            Long itemId,
            String itemName,
            String result,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String ipAddress,
            String userAgent) {
        recordResult(
                actor.getId(), actor.getName(), actor.getEmail(), action, itemType, itemId,
                itemName, result, reason, before, after, ipAddress, userAgent);
    }

    public void recordSystemSuccess(
            String actorName,
            String action,
            String itemType,
            Long itemId,
            String itemName,
            Map<String, Object> before,
            Map<String, Object> after) {
        recordResult(
                null, actorName, null, action, itemType, itemId, itemName,
                "SUCCESS", null, before, after, null, null);
    }

    public void recordSystemResult(
            String actorName,
            String action,
            String itemType,
            Long itemId,
            String itemName,
            String result,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        recordResult(
                null, actorName, null, action, itemType, itemId, itemName,
                result, reason, before, after, null, null);
    }

    private void recordResult(
            Long actorId,
            String actorName,
            String actorEmail,
            String action,
            String itemType,
            Long itemId,
            String itemName,
            String result,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            String ipAddress,
            String userAgent) {
        AuditLog audit = new AuditLog();
        audit.setAdminId(actorId);
        audit.setAdminNameSnapshot(actorName);
        audit.setAdminEmailSnapshot(actorEmail);
        audit.setAction(action);
        audit.setItemType(itemType);
        audit.setItemId(itemId);
        audit.setItemNameSnapshot(itemName);
        audit.setTimestamp(TbilisiTime.now());
        audit.setIpAddress(ipAddress);
        audit.setUserAgent(userAgent);
        audit.setDetails(writeDetails(result, reason, before, after));
        auditLogRepository.saveAndFlush(audit);
    }

    public static Map<String, Object> articleSnapshot(Article article, List<String> targetDepartments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", article.getTitle());
        snapshot.put("category_id", article.getCategoryId());
        snapshot.put("status", article.getStatus());
        snapshot.put("is_draft", article.isDraft());
        snapshot.put("version", article.getVersion());
        snapshot.put("last_verified_at", article.getLastVerifiedAt() == null
                ? null : article.getLastVerifiedAt().toString());
        snapshot.put("target_departments", targetDepartments == null ? List.of() : List.copyOf(targetDepartments));
        snapshot.put("tags", article.getTags());
        snapshot.put("attachment_present", article.getAttachmentUrl() != null && !article.getAttachmentUrl().isBlank());
        return snapshot;
    }

    public static Map<String, Object> newsSnapshot(News news) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", news.getTitle());
        snapshot.put("target_department", news.getTargetDepartment());
        snapshot.put("is_draft", news.isDraft());
        snapshot.put("version", news.getVersion());
        snapshot.put("expires_at", news.getExpiresAt() == null ? null : news.getExpiresAt().toString());
        snapshot.put("attachment_present", news.getAttachmentUrl() != null && !news.getAttachmentUrl().isBlank());
        return snapshot;
    }

    public static Map<String, Object> videoSnapshot(VideoInstruction video) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", video.getTitle());
        snapshot.put("category", video.getCategory());
        snapshot.put("target_department", video.getTargetDepartment());
        snapshot.put("archived", video.isArchived());
        snapshot.put("tags", video.getTags());
        return snapshot;
    }

    public static Map<String, Object> categorySnapshot(Category category) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", category.getName());
        snapshot.put("slug", category.getSlug());
        snapshot.put("parent_id", category.getParentId());
        snapshot.put("active", category.isActive());
        return snapshot;
    }

    /**
     * Security-relevant user state. The phone value and credential/token data
     * are deliberately excluded; presence is sufficient to prove that the
     * profile field changed without copying contact data into audit details.
     */
    public static Map<String, Object> userSnapshot(User user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("role", user.getRole() == null ? null : user.getRole().value());
        snapshot.put("active", user.isActive());
        snapshot.put("department", user.getDepartment());
        snapshot.put("position", user.getPosition());
        snapshot.put("phone_present", user.getPhone() != null && !user.getPhone().isBlank());
        snapshot.put("team_id", user.getTeamId());
        snapshot.put("manager_id", user.getManagerId());
        snapshot.put("compliance_override", user.getComplianceOverride());
        snapshot.put("card_style", user.getCardStyle());
        snapshot.put("lock_version", user.getLockVersion());
        return snapshot;
    }

    /** Sorted, stable permission-state map; absence means INHERIT. */
    public static Map<String, Object> permissionSnapshot(List<UserPermissionOverride> overrides) {
        Map<String, String> states = new TreeMap<>();
        for (UserPermissionOverride override : overrides) {
            states.put(override.getPermission(), override.getState().name());
        }
        return new LinkedHashMap<>(states);
    }

    public static Map<String, Object> leadershipSnapshot(LeadershipAssignment assignment) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("leader_user_id", assignment.getUserId());
        snapshot.put("department_id", assignment.getDepartmentId());
        snapshot.put("team_id", assignment.getTeamId());
        snapshot.put("assignment_type", assignment.getAssignmentType().name());
        snapshot.put("active", assignment.isActive());
        snapshot.put("started_at", assignment.getStartedAt().toString());
        snapshot.put("ended_at", assignment.getEndedAt() == null ? null : assignment.getEndedAt().toString());
        snapshot.put("source", assignment.getSource().name());
        return snapshot;
    }

    public static Map<String, Object> reminderSnapshot(Reminder reminder) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("recipient_user_id", reminder.getRecipientUserId());
        snapshot.put("reminder_type", reminder.getType().name());
        snapshot.put("required_reading_id", reminder.getRequiredReadingId());
        snapshot.put("item_type", reminder.getItemTypeSnapshot());
        snapshot.put("item_id", reminder.getItemIdSnapshot());
        snapshot.put("item_title", reminder.getItemTitleSnapshot());
        snapshot.put("due_at", reminder.getDueAtSnapshot() == null
                ? null : reminder.getDueAtSnapshot().toString());
        snapshot.put("read_at", reminder.getReadAt() == null ? null : reminder.getReadAt().toString());
        snapshot.put("lock_version", reminder.getLockVersion());
        return snapshot;
    }

    /**
     * Broadcast state without copying the announcement body into the audit
     * trail. Length is enough to distinguish an empty/corrupt payload while
     * keeping employee-facing communication out of duplicated evidence.
     */
    public static Map<String, Object> broadcastSnapshot(BroadcastAnnouncement announcement) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("audience", "ALL_AUTHENTICATED");
        snapshot.put("priority", announcement.getPriority().name());
        snapshot.put("message_length", announcement.getMessage() == null ? 0 : announcement.getMessage().length());
        snapshot.put("published_at", announcement.getPublishedAt().toString());
        snapshot.put("ends_at", announcement.getEndsAt().toString());
        snapshot.put("ended_at", announcement.getEndedAt() == null ? null : announcement.getEndedAt().toString());
        snapshot.put("published_by_user_id", announcement.getPublishedByUserId());
        snapshot.put("ended_by_user_id", announcement.getEndedByUserId());
        snapshot.put("lock_version", announcement.getLockVersion());
        return snapshot;
    }

    public static Map<String, Object> requiredReadingSnapshot(RequiredReading reading) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("item_type", reading.getItemType());
        snapshot.put("item_id", reading.getItemId());
        snapshot.put("item_title", reading.getItemTitleSnapshot());
        snapshot.put("target_department", reading.getTargetDepartment());
        snapshot.put("due_date", reading.getDueDate() == null ? null : reading.getDueDate().toString());
        snapshot.put("priority", reading.getPriority());
        return snapshot;
    }

    public static Map<String, Object> readStatusSnapshot(ReadStatus status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_id", status.getUserId());
        snapshot.put("required_reading_id", status.getRequiredReadingId());
        snapshot.put("status", status.getStatus());
        snapshot.put("read_at", status.getReadAt() == null ? null : status.getReadAt().toString());
        snapshot.put("operator_department", status.getOperatorDepartmentSnapshot());
        return snapshot;
    }

    public static Map<String, Object> quizAttemptSnapshot(QuizAttempt attempt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("article_id", attempt.getArticleIdSnapshot());
        snapshot.put("article_version", attempt.getArticleVersion());
        snapshot.put("user_id", attempt.getUserId());
        snapshot.put("attempt_number", attempt.getAttemptNumber());
        snapshot.put("score", attempt.getScore());
        snapshot.put("total_questions", attempt.getTotalQuestions());
        snapshot.put("passed", attempt.isPassed());
        snapshot.put("created_at", attempt.getCreatedAt() == null ? null : attempt.getCreatedAt().toString());
        return snapshot;
    }

    private String writeDetails(
            String result, String reason, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("schema_version", DETAILS_SCHEMA_VERSION);
        details.put("result", result);
        details.put("reason", reason);
        details.put("before", before);
        details.put("after", after);
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Mutation audit details could not be serialized", e);
        }
    }
}
