package ge.magti.portal.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The timers are on unless a context says otherwise; only the test context does. */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void timersRunWhenNothingSaysOtherwise() {
        runner.run(context -> assertEquals(1,
                context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size()));
    }

    @Test
    void theKeySwitchesThemOff() {
        runner.withPropertyValues("portal.scheduling.enabled=false").run(context -> assertEquals(0,
                context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size()));
    }
}
