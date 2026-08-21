package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.stats.CriticalOperator;
import ge.magti.portal.stats.DepartmentGroupStats;
import ge.magti.portal.stats.DepartmentMember;
import ge.magti.portal.stats.DepartmentStats;
import ge.magti.portal.stats.GroupMemberCompletion;
import ge.magti.portal.stats.TeamMemberCompletion;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

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

    /**
     * Behind a content-admin role gate today, and org-wide. Both rows carry
     * {@code operator_email}, which is why the matrix splits
     * {@code content.evidence} out from {@code content.manage} rather than
     * letting one capability cover both. Pinned so the shape cannot grow
     * while D-2 is still open.
     */
    @Test
    void articleEvidenceRowsCarryOnlyTheOperatorAndTheirReadingFacts() {
        assertEquals(
                List.of("operator_id", "operator_name", "operator_email", "department",
                        "read_at", "article_version", "has_read", "is_late", "deadline", "status"),
                wireFieldsOf(ArticleReadReceiptRowResponse.class));
        assertEquals(
                List.of("operator_id", "operator_name", "operator_email", "department",
                        "article_version", "viewed_at"),
                wireFieldsOf(ArticleViewRowResponse.class));
    }

    // ---- open decision (matrix decision D-1) ----------------------------

    /**
     * Every authenticated operator reads this one, with no capability and no
     * leadership scope -- the matrix's D-1. Pinned deliberately while that
     * decision is open: the shape is the decision. If D-1 resolves to
     * anonymisation, this test is what has to change, which makes the change
     * visible in review instead of silent.
     */
    @Test
    void theKnowledgeLeaderboardStillNamesPeople() {
        assertEquals(
                List.of("user_id", "user_name", "department", "score", "rank"),
                wireFieldsOf(LeaderboardEntryResponse.class));
    }

    @Test
    void groupLeaderRowsCarryNoContactDetails() {
        assertEquals(List.of("id", "name"), wireFieldsOf(GroupLeaderResponse.class));
    }
}
