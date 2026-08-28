package ge.magti.portal.web;

import ge.magti.portal.security.ScopeResolver;
import java.util.List;

public record LeadershipOptionsResponse(
        List<ScopeResolver.LeadershipOption> groups,
        Long defaultTeamId,
        boolean canExportPrimary) {
}
