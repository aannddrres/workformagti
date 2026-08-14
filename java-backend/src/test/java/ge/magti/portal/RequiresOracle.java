package ge.magti.portal;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that cannot run without a live Oracle 19c on
 * {@code ORACLE_DB_URL} -- every {@code @SpringBootTest} in this module,
 * because the context boots a real DataSource and Flyway runs
 * {@code db/migration} against it.
 *
 * <p>Exists so CI can gate every push on the fast, DB-free tests
 * ({@code mvn -B test -DexcludedGroups=oracle}) while the full suite runs in
 * a separate job that has a database. Without the split, a runner with no
 * Oracle fails ~220 tests with {@code ORA-12541} and CI can never be green,
 * which is why this repo had no Java job at all (audit finding PR-01).
 *
 * <p><b>Add this to every new {@code @SpringBootTest}.</b>
 * {@link OracleTagCoverageTest} fails the build if one is missing, so the
 * unit job cannot silently start erroring on a DB-less runner.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("oracle")
public @interface RequiresOracle {
}
