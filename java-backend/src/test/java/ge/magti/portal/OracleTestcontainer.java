package ge.magti.portal;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.utility.DockerImageName;

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
     * <p>Checked against the environment variable rather than
     * {@code spring.datasource.url}, which always has a value: application.yml
     * defaults it to {@code localhost:1521/orclpdb1}. That default is the
     * "no Oracle configured" case, not a configured one, so keying off it
     * would mean the container never starts for exactly the person it is for.
     */
    static class OnlyWithoutAnOracleUrl implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !StringUtils.hasText(context.getEnvironment().getProperty("ORACLE_DB_URL"));
        }
    }
}
