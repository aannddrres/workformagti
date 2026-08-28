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
						.requestMatchers(HttpMethod.POST,
								"/api/auth/login", "/api/auth/sso/start", "/api/auth/logout").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/health", "/actuator/health", "/actuator/health/**")
						.permitAll()
						.anyRequest().authenticated());
		return http.build();
	}

	/** Mirrors security.py's pwd_context = CryptContext(schemes=["bcrypt"]) -- reads existing hashes unchanged. */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
