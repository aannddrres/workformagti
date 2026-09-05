package ge.magti.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// No JPA/DataSource auto-configuration to exclude: spring-boot-starter-data-jpa
// is deliberately not a dependency yet (see pom.xml's comment) -- no Oracle
// dev environment exists (decided 2026-07-29, see
// docs/archive/migration/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1). Re-add that starter once
// entities/repositories are ready to be wired to a real datasource.
//
// @EnableAsync backs ExportJobWorker (mirrors FastAPI's BackgroundTasks for
// xlsx/pdf export builds); @EnableScheduling backs ExportJobCleanupScheduler
// (the export_jobs.expires_at cleanup Python never implemented -- known bug
// #9, fixed in this port, 2026-08-06). Both added for the Exports domain.
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class PortalBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PortalBackendApplication.class, args);
	}

}
