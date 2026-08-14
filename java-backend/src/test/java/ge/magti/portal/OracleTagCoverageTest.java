package ge.magti.portal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Keeps the CI unit/integration split honest: every {@code @SpringBootTest}
 * in this module boots a real DataSource and therefore needs Oracle, so
 * every one of them must carry {@link RequiresOracle}.
 *
 * <p>Without this guard the split rots silently in the worst direction. A new
 * {@code @SpringBootTest} added without the annotation lands in the DB-free
 * CI job, fails there with {@code ORA-12541}, and the natural fix under
 * deadline pressure is to stop trusting that job -- which is how this repo
 * ended up with no Java CI at all (audit finding PR-01). Failing here names
 * the offending class and the one-line fix instead.
 *
 * <p>Deliberately DB-free itself (plain classpath scanning, no context boot),
 * so it runs in the same job it protects.
 */
class OracleTagCoverageTest {

    @Test
    void everySpringBootTestIsTaggedRequiresOracle() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition bd) {
                        return true; // test classes are not @Component-style candidates
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class));

        List<String> untagged = new ArrayList<>();
        int total = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("ge.magti.portal")) {
            total++;
            Class<?> testClass;
            try {
                testClass = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but unloadable: " + definition.getBeanClassName(), e);
            }
            if (!testClass.isAnnotationPresent(RequiresOracle.class)) {
                untagged.add(testClass.getSimpleName());
            }
        }

        assertFalse(total == 0, "scanner found no @SpringBootTest at all -- the guard would pass vacuously");
        assertEquals(List.of(), untagged,
                "these @SpringBootTest classes need Oracle but are missing @RequiresOracle, so they would run "
                        + "(and fail with ORA-12541) in the DB-free CI job -- add @RequiresOracle to each");
    }
}
