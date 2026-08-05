package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mirrors one top-level department entry routers/stats.py's
 * build_department_stats assembles (routers/stats.py:619-651), matching
 * schemas.DepartmentStats (schemas.py:714-723) field-for-field. Always one
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
