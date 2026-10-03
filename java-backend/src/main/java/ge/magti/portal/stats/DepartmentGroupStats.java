package ge.magti.portal.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One group in the department dashboard -- members sorted by descending
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
