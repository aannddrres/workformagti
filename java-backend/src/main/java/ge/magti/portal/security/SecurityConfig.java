package ge.magti.portal.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless-session, CSRF-disabled config -- matches the Python app's own
 * approach (a JSON API authenticated by a JWT bearer header or httpOnly
 * cookie, never server-side sessions, so there's no session-fixation
 * surface CSRF tokens would protect).
 *
 * <p><b>Still permitAll on every path, but no longer for lack of anything
 * to check.</b> {@link JwtAuthenticationFilter} now runs on every request
 * and populates {@code SecurityContext} whenever a valid token is present
 * (see its own javadoc), mirroring security.py's get_current_user. Paths
 * stay open here because there are no real business endpoints in this
 * app yet to lock down -- only health/actuator, which security.py's
 * equivalent doesn't gate either. Restricting a path by role/permission
 * (mirroring require_roles/require_permission) is each endpoint's own
 * concern as it's ported, domain by domain (Phase 1e) -- not something to
 * anticipate here with no endpoint to attach it to.
 */
@Configuration
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	/** Mirrors security.py's pwd_context = CryptContext(schemes=["bcrypt"]) -- reads existing hashes unchanged. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
