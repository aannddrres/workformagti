package ge.magti.portal.stats;

import ge.magti.portal.compliance.ReadingProgress;
import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamStatsBuilderTest {

    private static ComplianceRecord record(Long id, int required, int read, int percentage) {
        User user = new User();
        user.setId(id);
        user.setName("user-" + id);
        return new ComplianceRecord(user, new ReadingProgress(required, read, percentage));
    }

    @Test
    void percentageLabelIsFormattedWithATrailingPercentSign() {
        List<TeamMemberCompletion> members = TeamStatsBuilder.buildTeamMemberCompletions(
                List.of(record(1L, 10, 4, 40)));

        assertEquals("40%", members.get(0).percentageLabel());
    }

    @Test
    void membersSortDescendingByPercentage() {
        List<TeamMemberCompletion> members = TeamStatsBuilder.buildTeamMemberCompletions(
                List.of(record(1L, 10, 2, 20), record(2L, 10, 9, 90)));

        assertEquals(2L, members.get(0).userId());
        assertEquals(1L, members.get(1).userId());
    }

    @Test
    void averagePercentageTruncatesTowardZeroLikePythonsIntNotRound() {
        // (10 + 25) / 2 = 17.5 -- truncates to 17.
        // Round-half-to-even would give 18 (nearest even) -- this must be 17.
        int average = TeamStatsBuilder.averagePercentage(List.of(record(1L, 10, 1, 10), record(2L, 10, 3, 25)));

        assertEquals(17, average);
    }

    @Test
    void averagePercentageIsZeroForNoMembers() {
        assertEquals(0, TeamStatsBuilder.averagePercentage(List.of()));
    }
}
