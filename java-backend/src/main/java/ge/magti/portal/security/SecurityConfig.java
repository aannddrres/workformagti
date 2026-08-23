package ge.magti.portal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.util.Map;

/**
 * Stateless-session, CSRF-disabled config -- matches the Python app's own
 * approach (a JSON API authenticated by a JWT bearer header or httpOnly
 * cookie, never server-side sessions, so there's no session-fixation
 * surface CSRF tokens would protect).
 *
 * <p><b>Deny by default.</b> This chain used to end in
 * {@code anyRequest().permitAll()}, on the stated ground that every endpoint
 * gates itself with its own {@code requireAuthenticated}/{@code
 * requireXPermission} call. That is still true -- {@code
 * docs/ACCESS_CONTRACT_MATRIX_KA.md} records a gate for all but four of the
 * endpoints, and {@code AccessContractCoverageTest} fails the build if a new
 * one appears without a row. But it made the whole authorization model rest
 * on nobody ever forgetting: an endpoint added without its guard served real
 * data to anonymous callers, and the only thing standing between that mistake
 * and production was a test noticing the row was missing.
 *
 * <p>The default is now {@code authenticated()}, so the same mistake answers
 * 401 instead. The per-endpoint guards are unchanged and still do the real
 * work -- they distinguish {@code content.manage} from {@code articles.edit},
 * which a filter chain cannot. This is a floor under them, not a replacement:
 * it decides only "some valid token or none", never which capability.
 *
 * <p><b>The four exceptions are listed here rather than implied.</b> That is
 * the other half of the change. {@code permitAll()} made every anonymous path
 * invisible -- including {@code /uploads/**}, which serves attachment bytes to
 * anyone who can reach the URL. The product owner has since decided that it
 * should not (D-4, 2026-08-22, recorded under question 9 in {@code
 * docs/QUESTIONS_FOR_IT.md}); acting on that decision changes what users can
 * do and is a separate commit, so that this one changes nothing observable.
 * What it changes is that the exception is now a line someone can review
 * instead of a consequence of a global default nobody reads.
 *
 * <p>Restricting a path by role or permission stays each endpoint's own
 * concern, mirroring {@code require_roles}/{@code require_permission} in
 * security.py.
 */
@Configuration
public class SecurityConfig {

	/**
	 * Anonymous GETs.
	 *
	 * <p>{@code /api/health} is a liveness probe that must answer before
	 * anyone can log in, and returns nothing but three status words (PR-09
	 * moved the JDBC URL out of its failure branch for exactly this reason).
	 *
	 * <p>{@code /uploads/**} is here only until D-4 is implemented. Filenames
	 * are UUIDs, so today the path is unguessable rather than protected --
	 * which is not the same thing, and is why the decision went the other way.
	 */
	private static final String[] ANONYMOUS_GET = {"/api/health", "/uploads/**"};

	/**
	 * Anonymous POSTs.
	 *
	 * <p>{@code /api/auth/login} issues the token, so it cannot require one.
	 *
	 * <p>{@code /api/auth/logout} succeeds without one on purpose: logging out
	 * when already logged out is not an error, and 401 here would turn "my
	 * token expired while the tab was open" into a dead end for the
	 * frontend's own logout path. It revokes nothing when there is no
	 * principal (AuthController#logout), so anonymous access costs nothing.
	 */
	private static final String[] ANONYMOUS_POST = {"/api/auth/login", "/api/auth/logout"};

	/**
	 * Not injected. {@code AuthenticationService} depends on this config, which
	 * pulls the whole chain into the earliest phase of context startup --
	 * before {@code JacksonAutoConfiguration} has contributed a bean, so
	 * injecting one fails the context outright. A private instance costs
	 * nothing here (two fixed messages, no application types) and keeps the
	 * escaping correct, which a hand-built JSON string would not.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();

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
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, exception) ->
								write(response, HttpStatus.UNAUTHORIZED, "Could not validate credentials"))
						.accessDeniedHandler((request, response, exception) ->
								write(response, HttpStatus.FORBIDDEN, "წვდომა უარყოფილია: არასაკმარისი უფლებები")))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.GET, ANONYMOUS_GET).permitAll()
						.requestMatchers(HttpMethod.POST, ANONYMOUS_POST).permitAll()
						// Spring forwards a failed request to /error to render
						// the body. That forward is authorized like any other
						// dispatch, so without this the 404 an anonymous caller
						// should get is replaced by the entry point's 401 --
						// and, worse, so is the body of every error raised by
						// an authenticated request whose own authorization
						// already passed.
						.requestMatchers("/error").permitAll()
						.anyRequest().authenticated());
		return http.build();
	}

	/**
	 * Same shape the controllers' own denials use ({@code {"detail": "..."}}),
	 * because the frontend's error handling reads {@code detail} and does not
	 * know which layer answered. Spring's default entry point would have sent
	 * an empty 403 instead -- a different status and no body.
	 */
	private void write(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		JSON.writeValue(response.getWriter(), Map.of("detail", detail));
	}

	/** Mirrors security.py's pwd_context = CryptContext(schemes=["bcrypt"]) -- reads existing hashes unchanged. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
