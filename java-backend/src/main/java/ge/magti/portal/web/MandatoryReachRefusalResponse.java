package ge.magti.portal.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.magti.portal.compliance.MandatoryReach;
import ge.magti.portal.domain.User;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * PO-40: a mandatory assignment refused because some or all of its target
 * could not open the material. {@code detail} alone reads as a complete
 * sentence, for any client that shows only that.
 *
 * <p>Who it would have missed is given as a count by department and never by
 * name: assigning content is not a scope over employees (the access matrix's
 * content.evidence rule), and this answer reaches every create or change of an
 * assignment, including the editor's save commands. Names, for the callers
 * entitled to them, come from the by-item addressees lookup.
 */
public record MandatoryReachRefusalResponse(
        String detail,
        String reason,
        @JsonProperty("blocked_total") int blockedTotal,
        @JsonProperty("blocked_departments") List<DepartmentCount> blockedDepartments
) {
    /** How many of the blocked are in one department: an aggregate, never a person. */
    public record DepartmentCount(String department, int count) {
    }

    public static MandatoryReachRefusalResponse from(MandatoryReach.Obstacle obstacle, List<User> blocked) {
        return new MandatoryReachRefusalResponse(
                detailFor(obstacle, blocked.size()),
                obstacle.name().toLowerCase(Locale.ROOT),
                blocked.size(),
                countsByDepartment(blocked));
    }

    private static List<DepartmentCount> countsByDepartment(List<User> users) {
        Map<String, Integer> counts = new TreeMap<>();
        for (User user : users) {
            counts.merge(user.getDepartment() == null ? "—" : user.getDepartment(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new DepartmentCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(DepartmentCount::count).reversed())
                .toList();
    }

    private static String detailFor(MandatoryReach.Obstacle obstacle, int blocked) {
        String why = switch (obstacle) {
            case MISSING -> "მასალა ვერ მოიძებნა ან სანაგვეშია";
            case PRIVATE_DRAFT -> "მასალა პირადი მონახაზია და მას მხოლოდ ავტორი ხედავს";
            case UNPUBLISHED -> "მასალა გამოქვეყნებული არ არის და მას ვერავინ ხედავს";
            case ARCHIVED -> "მასალა დაარქივებულია და მას ვერავინ ხედავს";
            case OUTSIDE_AUDIENCE -> blocked + " თანამშრომელი ამ მასალას ვერ ხედავს, რადგან ის მათ დეპარტამენტს არ ეხება";
        };
        return "სავალდებულოდ ვერ დაინიშნება: " + why + ".";
    }
}
