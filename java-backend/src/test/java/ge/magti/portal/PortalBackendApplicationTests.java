package ge.magti.portal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresOracle
@SpringBootTest
class PortalBackendApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() {
	}

	/**
	 * The five timers (export heartbeat, recovery and cleanup, reminders,
	 * login-attempt sweep) write audit rows on their own schedule, committed
	 * between whatever a test counts. VideoControllerIntegrationTest read 110
	 * audit rows where it expected 109 on 2026-09-26: the recovery sweep had
	 * audited an export left over from an earlier run. Tests that exercise a
	 * scheduler call its method; nothing in the suite waits for a timer.
	 */
	@Test
	void theTestContextRunsNoTimers() {
		assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(),
				"scheduling is on in the test context");
	}

}
