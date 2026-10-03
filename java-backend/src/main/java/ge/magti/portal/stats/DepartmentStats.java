package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One top-level department in the department dashboard. Always one
 * of these per {@link DepartmentBuckets#WHITELIST} entry, even when
 * {@code empty} -- the dashboard renders all three whitelisted
 * departments unconditionally.
 */
public record DepartmentStats(
        String name,
        @JsonProperty("member_count") int memberCount,
        @JsonProperty("group_count") int groupCount,
        int compliance,
        @JsonProperty("output_volume") int outputVolume,
        @JsonProperty("critical_count") int criticalCount,
        @JsonProperty("is_empty") boolean empty,
        List<DepartmentGroupStats> groups) {
}
