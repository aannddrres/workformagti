package ge.magti.portal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless-session, CSRF-disabled config -- matches the Python app's own
 * approach (a JSON API authenticated by a JWT bearer header or httpOnly
 * cookie, never server-side sessions, so there's no session-fixation
 * surface CSRF tokens would protect).
 *
 * <p><b>TEMPORARY, loudly so:</b> every path is permitAll below. There is no
 * User entity or repository yet (the app boots without a DataSource --
 * decided 2026-07-29, see docs/JAVA_ORACLE_ANGULAR_MIGRATION.md Phase 1), so
 * there is nothing yet to authenticate a request AGAINST. Wiring
 * JwtService into a real per-request filter that populates
 * SecurityContext and locking down paths by role/permission (mirroring
 * security.py's require_roles/require_permission) is the next increment,
 * once entities exist. Do not treat this file as "auth is done."
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
