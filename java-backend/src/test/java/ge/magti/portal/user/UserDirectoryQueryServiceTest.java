package ge.magti.portal.user;

import ge.magti.portal.domain.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserDirectoryQueryServiceTest {

    @Test
    void completeResultAcceptsTheCeilingWithoutCopying() {
        List<User> users = users(UserDirectoryQueryService.MAX_ACTIVE_USERS);

        assertSame(users, UserDirectoryQueryService.requireWithinActiveUserLimit(users));
    }

    @Test
    void sentinelRowFailsInsteadOfReturningAPartialResult() {
        List<User> users = users(UserDirectoryQueryService.MAX_ACTIVE_USERS + 1);

        assertThrows(UserDirectoryQueryService.ActiveUserCardinalityExceededException.class,
                () -> UserDirectoryQueryService.requireWithinActiveUserLimit(users));
        assertEquals(UserDirectoryQueryService.MAX_ACTIVE_USERS + 1, users.size());
    }

    @Test
    void allUserSnapshotHasAnIndependentFailLoudCeiling() {
        List<User> users = users(UserDirectoryQueryService.MAX_DIRECTORY_USERS + 1);
        List<User> accepted = users(UserDirectoryQueryService.MAX_DIRECTORY_USERS);

        assertThrows(UserDirectoryQueryService.AllUserCardinalityExceededException.class,
                () -> UserDirectoryQueryService.requireWithinDirectoryLimit(users));
        assertSame(accepted, UserDirectoryQueryService.requireWithinDirectoryLimit(accepted));
    }

    private static List<User> users(int size) {
        return IntStream.range(0, size).mapToObj(ignored -> new User()).toList();
    }
}
