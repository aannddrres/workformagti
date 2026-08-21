package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mirrors one group entry routers/stats.py's build_department_stats
 * assembles (routers/stats.py:622-637), matching schemas.DeptGroupStats
 * (schemas.py:703-711) field-for-field -- members sorted by descending
 * percentage.
 */
public record DepartmentGroupStats(
        String name,
        @JsonProperty("full_department") String fullDepartment,
        @JsonProperty("member_count") int memberCount,
        int compliance,
        @JsonProperty("output_volume") int outputVolume,
        @JsonProperty("critical_count") int criticalCount,
        List<DepartmentMember> members) {
}
