package ge.magti.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// No JPA/DataSource auto-configuration to exclude: spring-boot-starter-data-jpa
// is deliberately not a dependency yet (see pom.xml's comment) -- no Oracle
// dev environment exists (decided 2026-07-29, see
// docs/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1). Re-add that starter once
// entities/repositories are ready to be wired to a real datasource.
@SpringBootApplication
public class PortalBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(PortalBackendApplication.class, args);
	}

}
