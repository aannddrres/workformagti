package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.domain.Permission;
import ge.magti.portal.domain.Role;
import ge.magti.portal.domain.User;
import ge.magti.portal.domain.UserPermissionOverride;
import ge.magti.portal.stats.CriticalOperator;
import ge.magti.portal.stats.DepartmentGroupStats;
import ge.magti.portal.stats.DepartmentMember;
import ge.magti.portal.stats.DepartmentStats;
import ge.magti.portal.stats.GroupMemberCompletion;
import ge.magti.portal.stats.TeamMemberCompletion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wire shape of every response that carries employee identity, pinned.
 *
 * <p>SEC-03 is the reason this exists. That fix asked the right question --
 * "may a manager see these people's names?" -- answered it correctly, and
 * still leaked, because the answer was applied to the {@code members} list
 * while {@code compliance}, {@code output_volume} and {@code critical_count}
 * for every sibling group travelled in the same payload untouched. The gate
 * was right and the shape was wrong, and nothing in the build could tell.
 *
 * <p>A status code is easy to assert and a field is not, so scoping tests
 * tend to check the former. This checks the latter: the exact set of JSON
 * keys each employee-data response may carry. Adding a field to any of these
 * records fails here, which forces the question "who is allowed to see this,
 * at which scope?" to be answered in
 * {@code docs/ACCESS_CONTRACT_MATRIX_KA.md} before the field ships rather
 * than after someone notices it in a response.
 *
 * <p>Names are read as Jackson serialises them -- {@code @JsonProperty} where
 * present, the component name otherwise -- so renaming a key is caught too.
 * That matters because the Angular client and the Python original both key
 * off these exact strings.
 */
class ResponseShapeContractTest {

    private static List<String> wireFieldsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(component -> wireName(record, component))
                .toList();
    }

    /**
     * Reads the name Jackson would emit.
     *
     * <p>Not {@code component.getAnnotation(...)}: {@code @JsonProperty}'s
     * {@code @Target} list does not include {@code RECORD_COMPONENT}, so on a
     * record it lands on the backing field and the component sees nothing.
     * Asking the component would silently return every Java name instead --
     * a test that passes while checking the wrong strings.
     */
    private static String wireName(Class<?> record, RecordComponent component) {
        JsonProperty annotation = null;
        try {
            annotation = record.getDeclaredField(component.getName()).getAnnotation(JsonProperty.class);
        } catch (NoSuchFieldException e) {
            // A record component always has a backing field; keep the Java name if that ever changes.
        }
        return annotation == null || annotation.value().isEmpty() ? component.getName() : annotation.value();
    }

    // ---- leadership-scoped statistics -----------------------------------

    /** The rows redacted by SEC-03 and now removed at the query instead (Phase 0). */
    @Test
    void departmentMemberCarriesOnlyIdentityAndCompliance() {
        assertEquals(
                List.of("user_id", "user_name", "position", "read_count", "required_count", "percentage", "is_critical"),
                wireFieldsOf(DepartmentMember.class));
    }

    /**
     * The aggregates that survived SEC-03's redaction and leaked across every
     * sibling group until Phase 0 scoped the query behind them. Pinned so a
     * new per-group figure is a decision, not an addition.
     */
    @Test
    void departmentAndGroupAggregatesStayAggregates() {
        assertEquals(
                List.of("name", "full_department", "member_count", "compliance",
                        "output_volume", "critical_count", "members"),
                wireFieldsOf(DepartmentGroupStats.class));
        assertEquals(
                List.of("name", "member_count", "group_count", "compliance",
                        "output_volume", "critical_count", "is_empty", "groups"),
                wireFieldsOf(DepartmentStats.class));
    }

    @Test
    void teamAndGroupCompletionRowsCarryOnlyIdentityAndPercentage() {
        assertEquals(
                List.of("user_id", "user_name", "read_count", "required_count", "percentage"),
                wireFieldsOf(TeamMemberCompletion.class));
        assertEquals(
                List.of("user_id", "first_name", "last_name", "completion_percentage"),
                wireFieldsOf(GroupMemberCompletion.class));
    }

    @Test
    void criticalOperatorCarriesOnlyIdentityAndOverdueCount() {
        assertEquals(
                List.of("user_id", "first_name", "last_name", "department", "overdue_count"),
                wireFieldsOf(CriticalOperator.class));
    }

    @Test
    void userProgressCarriesOnlyIdentityAndProgress() {
        assertEquals(
                List.of("user_id", "user_name", "department", "read_count", "required_count", "percentage"),
                wireFieldsOf(UserProgressItemResponse.class));
    }

    // ---- content evidence (matrix decision D-2) -------------------------

    /** Official evidence deliberately omits contact details; view logs remain a separate SYSTEM_ADMIN surface. */
    @Test
    void articleEvidenceRowsCarryOnlyTheOperatorAndTheirReadingFacts() {
        assertEquals(
                List.of("operator_id", "operator_name", "department",
                        "read_at", "article_version", "has_read", "is_late", "deadline", "status"),
                wireFieldsOf(ArticleReadReceiptRowResponse.class));
        assertEquals(
                List.of("article_id", "article_title", "current_version", "eligible_count",
                        "read_count", "unread_count", "late_read_count", "receipts"),
                wireFieldsOf(ArticleReadReceiptResponse.class));
        assertEquals(
                List.of("operator_id", "operator_name", "operator_email", "department",
                        "article_version", "viewed_at"),
                wireFieldsOf(ArticleViewRowResponse.class));
    }

    // D-1 resolved 2026-08-21: /api/knowledge-leaderboard was removed, so
    // LeaderboardEntryResponse no longer exists and has no shape to pin. The
    // reason it was pinned in the first place is worth keeping: while a
    // decision about a response is open, the shape IS the decision, and
    // freezing it makes resolving it visible in review instead of silent.

    @Test
    void groupLeaderRowsCarryNoContactDetails() {
        assertEquals(List.of("id", "name"), wireFieldsOf(GroupLeaderResponse.class));
    }

    // ---- the two user responses (Phase 6) --------------------------------

    /**
     * These two were the gap: both carry employee identity and both grew two
     * fields at the Phase 6 cutover without anything in the build asking who
     * may see them.
     */
    @Test
    void theUserResponsesCarryIdentityProgressAndPermissionStateOnly() {
        assertEquals(
                List.of("id", "email", "name", "department", "position", "phone", "role", "team_id",
                        "is_active", "last_active", "read_count", "required_count", "progress_percentage",
                        "card_style", "permissions", "permission_overrides", "lock_version"),
                wireFieldsOf(UserResponse.class));
        assertEquals(
                List.of("id", "email", "name", "department", "position", "phone", "role", "team_id",
                        "is_active", "last_active", "read_count", "required_count", "progress_percentage",
                        "card_style", "permissions", "can_view_audit_log"),
                wireFieldsOf(CurrentUserResponse.class));
        assertEquals(List.of("permission", "state"), wireFieldsOf(PermissionOverrideResponse.class));
    }

    @Test
    void effectiveAccessCarriesOnlyTheDecisionInputsTheUiNeeds() {
        assertEquals(List.of("role", "permissions", "bypass", "can_publish_announcement"),
                wireFieldsOf(EffectiveAccessResponse.class));
    }

    @Test
    void broadcastExposesContentLifecycleAndAViewerSpecificEndDecisionButNoRecipientData() {
        assertEquals(
                List.of("id", "message", "priority", "published_at", "ends_at", "ended_at",
                        "publisher_name", "ended_by_name", "status", "can_end_early", "lock_version"),
                wireFieldsOf(BroadcastResponse.class));
        assertEquals(List.of("items", "page", "size", "total_items", "total_pages"),
                wireFieldsOf(BroadcastHistoryResponse.class));
    }

    @Test
    void reminderExposesFixedDeliveryContextWithoutReplyOrFreeTextFields() {
        assertEquals(
                List.of("id", "recipient_name", "type", "content", "required_reading_id",
                        "item_type", "item_id", "item_title", "due_at", "triggered_by_name",
                        "created_at", "read_at", "lock_version"),
                wireFieldsOf(ReminderResponse.class));
        assertEquals(List.of("items", "page", "size", "total_elements", "total_pages"),
                wireFieldsOf(ReminderPageResponse.class));
        assertEquals(List.of("unread_readings", "recent_news", "unread_reminders_count"),
                wireFieldsOf(NotificationsSummaryResponse.class));
    }

    @Test
    void orgStructureCarriesOnlyDirectoryIdentityAndMemberCounts() {
        assertEquals(List.of("departments"), wireFieldsOf(OrgStructureResponse.class));
        assertEquals(List.of("id", "stable_key", "name", "is_active", "teams"),
                wireFieldsOf(OrgDepartmentResponse.class));
        assertEquals(List.of("id", "stable_key", "name", "is_active", "member_count"),
                wireFieldsOf(OrgTeamResponse.class));
    }

    @Test
    void leadershipAssignmentsCarryOnlyTheActorTraceAndResolvedScopeNames() {
        assertEquals(
                List.of("id", "user_id", "user_name", "user_email", "scope", "department_id",
                        "department_name", "team_id", "team_name", "assignment_type", "is_active",
                        "started_at", "ended_at", "created_by", "source"),
                wireFieldsOf(LeadershipAssignmentResponse.class));
    }

    @Test
    void accessDiffNamesTheSubjectButExposesOnlyCountsForTheirScope() {
        assertEquals(List.of("generated_at", "totals", "rows"), wireFieldsOf(AccessDiffResponse.class));
        assertEquals(List.of("users", "gains", "losses", "unchanged"),
                wireFieldsOf(AccessDiffTotalsResponse.class));
        assertEquals(List.of("user_id", "user_name", "role", "compliance", "scope"),
                wireFieldsOf(AccessDiffRowResponse.class));
        assertEquals(List.of("legacy", "proposed"), wireFieldsOf(AccessDiffComplianceResponse.class));
        assertEquals(List.of("legacy_user_count", "proposed_user_count"),
                wireFieldsOf(AccessDiffScopeResponse.class));
    }

    /**
     * {@code permissions} must be what the gates answer, not what
     * {@code users.permissions} still holds.
     *
     * <p>Since the cutover the two diverge silently: a role change leaves the
     * old role's rows behind, and an explicit ALLOW is never written there at
     * all. Both directions are checked, because a permission screen that
     * over-reports is a support ticket and one that under-reports is an
     * administrator granting the same thing twice.
     */
    @Test
    void theUserResponsePermissionListIsTheEffectiveOneNotTheStoredColumn() {
        User operator = new User();
        operator.setId(1L);
        operator.setRole(Role.OPERATOR);
        operator.setActive(true);
        operator.setPermissions(new LinkedHashSet<>(List.of("articles.edit")));

        UserPermissionOverride allow = new UserPermissionOverride();
        allow.setPermission("content.manage");
        allow.setState(UserPermissionOverride.State.ALLOW);

        UserResponse response = UserResponse.from(operator, List.of(allow));

        assertEquals(List.of("content.manage"), response.permissions());
        assertEquals(1, response.permissionOverrides().size());
    }

    @Test
    void theCurrentUserResponseShipsTheSetItIsGivenRatherThanTheStoredColumn() {
        User admin = new User();
        admin.setId(2L);
        admin.setRole(Role.CONTENT_ADMIN);
        admin.setActive(true);
        admin.setPermissions(new LinkedHashSet<>(List.of("reports.export")));

        CurrentUserResponse response =
                CurrentUserResponse.from(admin, false, Set.of(Permission.CONTENT_MANAGE));

        assertEquals(List.of("content.manage"), response.permissions());
    }
}
