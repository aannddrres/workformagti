package ge.magti.portal.security;

import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLRecoverableException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which database failures stop a sign-in at the throttle, and which let it
 * through. No database: the store is handed a JdbcTemplate that fails the
 * way Hikari and the Oracle driver do.
 *
 * <p>The distinction matters because of what comes after the throttle. For a
 * company sign-in the next step is the directory, asked with no connection
 * held (AuthController.login), and only then the account, session and audit
 * row. So an unreachable database that the throttle waves through reaches
 * the directory on every attempt, unthrottled, for as long as the outage
 * lasts, and fails afterwards anyway.
 */
class JdbcLoginAttemptStoreTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    @Test
    void aDatabaseThePoolCannotReachStopsTheSignInAtTheThrottle() {
        RuntimeException outage = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLTransientConnectionException("Connection is not available, request timed out after 30000ms."));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> storeFailingWith(outage).tryConsume("acct|a@magti.ge|10.0.0.1", 10, MINUTE),
                "with the database unreachable the attempt must not go on to the directory");
        assertSame(outage, thrown);
    }

    @Test
    void aConnectionLostMidStatementStopsItToo() {
        RuntimeException lost = new RecoverableDataAccessException("INSERT INTO login_attempts",
                new SQLRecoverableException("ORA-03113: end-of-file on communication channel"));

        assertThrows(RecoverableDataAccessException.class,
                () -> storeFailingWith(lost).tryConsume("acct|a@magti.ge|10.0.0.1", 10, MINUTE));
    }

    @Test
    void aProblemWithTheCountingTableAloneStillLetsTheAttemptThrough() {
        // The trade-off the store has always made, kept: everything else the
        // sign-in needs is still working, so refusing here would lock the
        // whole company out over a table of throwaway counters.
        RuntimeException tableGone = new BadSqlGrammarException("login throttle",
                "INSERT INTO login_attempts", new SQLSyntaxErrorException("ORA-00942: table or view does not exist"));

        assertTrue(storeFailingWith(tableGone).tryConsume("acct|a@magti.ge|10.0.0.1", 10, MINUTE));
    }

    private static JdbcLoginAttemptStore storeFailingWith(RuntimeException failure) {
        return new JdbcLoginAttemptStore(new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                throw failure;
            }
        });
    }
}
