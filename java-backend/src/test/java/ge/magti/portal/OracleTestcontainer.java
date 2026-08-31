package ge.magti.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Supplies an Oracle to the {@code @RequiresOracle} suite when the machine
 * running it has no other one.
 *
 * <h2>Why this exists</h2>
 *
 * Roughly half of this module's tests -- every {@code @SpringBootTest},
 * because the context boots a real DataSource and Flyway runs
 * {@code db/migration} against it -- need Oracle. CI's oracle job supplies
 * one through {@code services:} and sets {@code ORACLE_DB_URL}; a developer
 * with a local instance sets the same variable. Nobody else could run them at
 * all, which is how a database-bound suite quietly becomes one that is only
 * ever exercised after the push.
 *
 * <h2>It adds a path, it does not take one away</h2>
 *
 * The container starts <b>only</b> when {@code ORACLE_DB_URL} is unset. With
 * the variable set, this configuration contributes nothing and the datasource
 * comes from {@code application.yml} exactly as before -- so CI's oracle job
 * is untouched, and so is anyone pointing the suite at a real instance. That
 * is deliberate: a container is slower than a database that already exists,
 * and silently overriding a URL somebody set on purpose would be worse than
 * not offering the fallback.
 *
 * <p>The image is the one CI already uses, so the two paths test against the
 * same Oracle rather than two that merely both say "Oracle".
 *
 * <h2>How it reaches every test</h2>
 *
 * {@link RequiresOracle} is meta-annotated with {@code @Import} of this
 * class, so the twenty test classes that need a database get it without
 * twenty edits -- and {@link OracleTagCoverageTest} already fails the build
 * for a {@code @SpringBootTest} missing that annotation, so a new one cannot
 * be added and silently miss out.
 *
 * <p>{@code @ServiceConnection} points {@code spring.datasource.*} at the
 * container. Flyway then migrates the empty schema from V1, which is also the
 * only place in this repository where the full migration chain runs from
 * nothing rather than against a database somebody has already used.
 */
@TestConfiguration(proxyBeanMethods = false)
@Conditional(OracleTestcontainer.OnlyWithoutAnOracleUrl.class)
public class OracleTestcontainer {

    /** The same image CI's oracle job runs (ci.yml:118). */
    private static final DockerImageName IMAGE =
            DockerImageName.parse("gvenzl/oracle-xe:21-slim-faststart");

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        return new OracleContainer(IMAGE)
                // Matches ORACLE_DB_USER's default in application.yml, so a
                // migration or a test that spells the schema out by name
                // behaves the same on both paths.
                .withUsername("magti_app")
                .withPassword("magti_app")
                // Oracle XE is slow to open even on the faststart image, and
                // the default 120s is not always enough on a cold pull.
                .withStartupTimeoutSeconds(600)
                // Survives between runs on a developer's machine (needs
                // testcontainers.reuse.enable=true in ~/.testcontainers.properties);
                // ignored on CI, which is ephemeral anyway.
                .withReuse(true);
    }

    /**
     * Present only when nothing else has offered a database.
     *
     * <p>{@code ORACLE_DB_URL} being set is the explicit answer -- CI's
     * oracle job and anyone pointing the suite at a real instance -- and it
     * short-circuits everything below.
     *
     * <p>Unset does <b>not</b> mean "no Oracle", which is what this condition
     * originally assumed. application.yml defaults the URL to
     * {@code localhost:1521/orclpdb1}, and on the machine this project is
     * developed on that default is a real 19c instance that has never needed
     * an environment variable. Treating unset as absent started a fresh XE
     * container for every distinct Spring context in the suite -- three were
     * running at once before this was noticed -- each one booting an empty
     * schema through the whole migration chain while a perfectly good
     * database sat idle on the same host.
     *
     * <p>So the fallback asks instead of assuming: it opens a connection to
     * the configured URL, with a short login timeout so the developer this
     * class is actually for waits seconds rather than minutes for the answer
     * "no". Reachable means use it; unreachable means start the container.
     */
    static class OnlyWithoutAnOracleUrl implements Condition {

        /** Long enough for a local instance to answer, short enough not to stall. */
        private static final int LOGIN_TIMEOUT_SECONDS = 3;

        /**
         * Probed once per JVM, not once per context.
         *
         * <p>Spring evaluates this condition again for every distinct test
         * context, and the suite has a dozen of them. Asking the database
         * each time was both wasteful and <b>unstable</b>: one context saw a
         * momentarily unavailable connection, concluded there was no local
         * Oracle, and started a container -- which then failed to come up and
         * took fourteen tests with it, while every other context in the same
         * run used the local instance quite happily. A decision this
         * consequential must be the same answer all run, so it is taken once.
         */
        private static volatile Boolean localDatabaseAvailable;

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            if (StringUtils.hasText(environment.getProperty("ORACLE_DB_URL"))) {
                return false;
            }
            return !localDatabaseIsAvailable(environment);
        }

        private static boolean localDatabaseIsAvailable(Environment environment) {
            Boolean cached = localDatabaseAvailable;
            if (cached != null) {
                return cached;
            }
            synchronized (OnlyWithoutAnOracleUrl.class) {
                if (localDatabaseAvailable == null) {
                    localDatabaseAvailable = databaseAnswersAt(
                            environment.getProperty("spring.datasource.url"),
                            environment.getProperty("spring.datasource.username"),
                            environment.getProperty("spring.datasource.password"));
                }
                return localDatabaseAvailable;
            }
        }

        private static boolean databaseAnswersAt(String url, String username, String password) {
            if (!StringUtils.hasText(url)) {
                return false;
            }
            int previousTimeout = DriverManager.getLoginTimeout();
            DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
            try (Connection ignored = DriverManager.getConnection(url, username, password)) {
                return true;
            } catch (SQLException e) {
                // Wrong password, no listener, no database -- all the same
                // answer here: nothing usable is already running, so start one.
                return false;
            } finally {
                DriverManager.setLoginTimeout(previousTimeout);
            }
        }
    }
}
