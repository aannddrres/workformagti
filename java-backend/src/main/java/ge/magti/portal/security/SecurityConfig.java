package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.nio.charset.StandardCharsets;

/**
 * Stateless-session security for a JWT stored in an httpOnly cookie. Cookie
 * authentication is CSRF-relevant even without server-side sessions, so SPA
 * requests use Angular's XSRF cookie/header pair. Explicit Bearer requests
 * (integration clients and tests) remain CSRF-exempt because browsers do not
 * attach that credential cross-site.
 *
 * <p>The filter chain is the deny-by-default authentication boundary. Only
 * the login/SSO bootstrap, idempotent logout compatibility path and aggregate
 * operational probes are public. Domain role, permission and scope checks
 * remain controller/service concerns, so a valid identity is necessary but
 * never sufficient for privileged access.
 */
@Configuration
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final PortalProperties portalProperties;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, PortalProperties portalProperties) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.portalProperties = portalProperties;
	}

	/**
	 * The only GET paths reachable without a token.
	 *
	 * <p>Named rather than written inline because this array IS the anonymous
	 * surface: the chain denies by default, so whether an endpoint can be
	 * reached without credentials is decided here and nowhere else. A pattern
	 * that is broader than it looks -- {@code "/api/auth/**"} where two
	 * explicit paths were meant -- reopens every future endpoint under it, and
	 * nothing at runtime would say so. {@link AnonymousSurfaceTest} reads
	 * these two arrays and refuses to let them disagree with
	 * {@code docs/ACCESS_CONTRACT_MATRIX_KA.md}.
	 */
	static final String[] ANONYMOUS_GET = {"/api/health"};

	/**
	 * Operational probes and metrics. Separate from {@link #ANONYMOUS_GET}
	 * because they are Actuator's, not the API's: they carry no portal data,
	 * appear in no access contract row, and exist for the orchestrator and
	 * the monitoring system rather than for a caller. Keeping them apart is
	 * what lets the contract check above be exact instead of carrying a
	 * permanent exception.
	 *
	 * <p><b>These are reachable without a token, and that is only safe
	 * because nothing routes them from outside.</b> The Ingress publishes
	 * the frontend Service, and the frontend's nginx proxies exactly
	 * {@code /api/} and {@code /uploads/} -- not {@code /actuator}. So these
	 * paths answer only to callers already inside the cluster: the kubelet
	 * running the probes, and Prometheus scraping the pod directly. A
	 * scraper cannot hold a portal login, which is why this is an allowlist
	 * entry rather than a guarded endpoint.
	 *
	 * <p>The metrics themselves carry no personal data: request series are
	 * keyed by the TEMPLATED route ({@code /api/articles/{id}}), never the
	 * resolved one, so no article id, department or email reaches them.
	 *
	 * <p>Anyone changing the Ingress to route {@code /actuator} publicly is
	 * undoing this reasoning. k8s/README_KA.md says so where the routing is
	 * configured.
	 */
	static final String[] ANONYMOUS_PROBES = {
			"/actuator/health", "/actuator/health/**", "/actuator/prometheus"};

	/** The only POST paths reachable without a token: sign-in, and an idempotent sign-out. */
	static final String[] ANONYMOUS_POST = {"/api/auth/login", "/api/auth/sso/start", "/api/auth/logout"};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfRepository.setCookieCustomizer(cookie -> cookie
				.path("/")
				.sameSite("Strict")
				.secure(portalProperties.getSecurity().getCookie().isSecure()));
		RequestMatcher bearerRequest = request -> {
			String authorization = request.getHeader("Authorization");
			return authorization != null && authorization.startsWith("Bearer ");
		};
		RequestMatcher unauthenticatedAuthStart = request ->
				"POST".equals(request.getMethod())
						&& ("/api/auth/login".equals(request.getRequestURI())
						|| "/api/auth/sso/start".equals(request.getRequestURI()));

		http
				.csrf(csrf -> csrf
						.csrfTokenRepository(csrfRepository)
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
						.ignoringRequestMatchers(unauthenticatedAuthStart, bearerRequest))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, exception) -> {
							response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
							response.setContentType(MediaType.APPLICATION_JSON_VALUE);
							response.setCharacterEncoding(StandardCharsets.UTF_8.name());
							response.getWriter().write("{\"detail\":\"Could not validate credentials\"}");
						}))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, ANONYMOUS_POST).permitAll()
						.requestMatchers(HttpMethod.GET, ANONYMOUS_GET).permitAll()
						.requestMatchers(HttpMethod.GET, ANONYMOUS_PROBES).permitAll()
						.anyRequest().authenticated());
		return http.build();
	}

	/** Mirrors security.py's pwd_context = CryptContext(schemes=["bcrypt"]) -- reads existing hashes unchanged. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
