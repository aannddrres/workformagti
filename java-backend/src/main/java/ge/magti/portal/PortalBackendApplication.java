package ge.magti.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// No JPA/DataSource auto-configuration to exclude: spring-boot-starter-data-jpa
// is deliberately not a dependency yet (see pom.xml's comment) -- no Oracle
// dev environment exists (decided 2026-07-29, see
// docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1). Re-add that starter once
// entities/repositories are ready to be wired to a real datasource.
//
// @EnableAsync backs ExportJobWorker (background xlsx/pdf export builds).
// Scheduling, added with it for
// ExportJobCleanupScheduler (the export_jobs.expires_at cleanup -- known
// bug #9, fixed 2026-08-06), is switched
// on in config/SchedulingConfig, where the test context can leave it off.
@EnableAsync
@SpringBootApplication
public class PortalBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PortalBackendApplication.class, args);
	}

}
