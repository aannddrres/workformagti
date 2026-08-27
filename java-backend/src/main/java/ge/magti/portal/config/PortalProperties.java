package ge.magti.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds the {@code portal.*} keys in application.yml. Mirrors config.py's
 * {@code Settings} class one-for-one so the two configs stay legible
 * side-by-side during the port.
 */
@Configuration
@ConfigurationProperties(prefix = "portal")
public class PortalProperties {

	/**
	 * Defaults to production so the INSECURE mode is the one that must be
	 * asked for.
	 *
	 * It used to default to "development" here and in application.yml, while
	 * the Dockerfile set no APP_ENV at all — so the shipping image booted with
	 * the password-less dev login enabled, and a deployment manifest that
	 * merely forgot the variable (or misspelled it, or wrote APP_ENV=prod)
	 * handed a SYSTEM_ADMIN token to anyone who could reach the URL
	 * (audit SEC-01, Critical). The Python side already fails safe this way;
	 * this brings the Java image in line.
	 */
	private String appEnv = "production";

	/**
	 * Read-only legacy path since PR-03: new uploads go into Oracle
	 * ({@code stored_files}), and {@code FileStorageService} consults this
	 * directory only to keep attachments written before that change
	 * resolvable. Nothing writes here any more.
	 */
	private String uploadsDir = "uploads";

	@NestedConfigurationProperty
	private final Security security = new Security();

	public String getAppEnv() {
		return appEnv;
	}

	public void setAppEnv(String appEnv) {
		this.appEnv = appEnv;
	}

	public String getUploadsDir() {
		return uploadsDir;
	}

	public void setUploadsDir(String uploadsDir) {
		this.uploadsDir = uploadsDir;
	}

	/**
	 * Whitespace around the value is ignored, and an unset or blank value is
	 * production (DEC-P04).
	 *
	 * <p>This used to be {@code equalsIgnoreCase} and nothing else, which
	 * made the casing of APP_ENV free but let a stray character decide
	 * whether the deployment was production at all. {@code APP_ENV=production}
	 * with one trailing space -- which .env files and docker compose both
	 * preserve -- was not production, and both of this method's callers turn
	 * on that answer: {@link ProductionSafetyGuard} returns before its first
	 * check, and {@code AuthenticationService:80} gates the password-less dev
	 * login on {@code !isProduction()}. So a single invisible character
	 * disabled the boot-time guard AND made the bypass eligible again -- both
	 * halves of the SEC-01 fix, undone by a space.
	 *
	 * <p>Blank is treated the same as absent for the same reason the field
	 * above defaults to production: {@code APP_ENV=} is a variable somebody
	 * meant to set and did not, and the insecure mode is the one that has to
	 * be asked for. A developer who lands here gets a loud refusal naming
	 * APP_ENV, not a silent production boot.
	 *
	 * <p><b>Still not production:</b> {@code prod}, and every other spelling
	 * that is not the word. That is a deliberately separate question -- which
	 * aliases count is a list someone has to choose, not whitespace to
	 * discard -- and it is recorded rather than answered here.
	 */
	public boolean isProduction() {
		return appEnv == null || appEnv.isBlank() || "production".equalsIgnoreCase(appEnv.strip());
	}

	public Security getSecurity() {
		return security;
	}

	public static class Security {
		/**
		 * Second, explicit switch for the password-less dev login.
		 *
		 * !isProduction() alone was too easy to satisfy by accident — any
		 * environment that is not exactly "production" enabled it silently.
		 * The bypass is deliberate for local development (see the
		 * auth-bypass-intentional-pending-ad decision), but it now has to be
		 * asked for by name, and ProductionSafetyGuard refuses to let it
		 * coexist with a production environment.
		 */
		private boolean allowDevLogin = false;

		/**
		 * Addresses or CIDR ranges whose {@code X-Forwarded-For} header may be
		 * believed — the reverse proxy(ies) in front of this app, nothing else.
		 *
		 * Empty by default, and empty means the header is ignored entirely
		 * (see {@link ge.magti.portal.security.ClientIpResolver}). That is the
		 * safe direction: a deployment that forgets to set this loses IP
		 * granularity in rate limiting and the audit log, while one that
		 * trusts blindly would let anyone able to reach the app directly forge
		 * both.
		 */
		private List<String> trustedProxies = new ArrayList<>();

		public boolean isAllowDevLogin() {
			return allowDevLogin;
		}

		public void setAllowDevLogin(boolean allowDevLogin) {
			this.allowDevLogin = allowDevLogin;
		}

		public List<String> getTrustedProxies() {
			return trustedProxies;
		}

		public void setTrustedProxies(List<String> trustedProxies) {
			this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
		}

		@NestedConfigurationProperty
		private final Jwt jwt = new Jwt();
		@NestedConfigurationProperty
		private final Cookie cookie = new Cookie();

		public Jwt getJwt() {
			return jwt;
		}

		public Cookie getCookie() {
			return cookie;
		}
	}

	public static class Jwt {
		private String secret;
		private String algorithm = "HS256";
		private long accessTokenExpireMinutes = 60;

		public String getSecret() {
			return secret;
		}

		public void setSecret(String secret) {
			this.secret = secret;
		}

		public String getAlgorithm() {
			return algorithm;
		}

		public void setAlgorithm(String algorithm) {
			this.algorithm = algorithm;
		}

		public long getAccessTokenExpireMinutes() {
			return accessTokenExpireMinutes;
		}

		public void setAccessTokenExpireMinutes(long accessTokenExpireMinutes) {
			this.accessTokenExpireMinutes = accessTokenExpireMinutes;
		}
	}

	public static class Cookie {
		private boolean secure = false;
		private String sameSite = "lax";

		public boolean isSecure() {
			return secure;
		}

		public void setSecure(boolean secure) {
			this.secure = secure;
		}

		public String getSameSite() {
			return sameSite;
		}

		public void setSameSite(String sameSite) {
			this.sameSite = sameSite;
		}
	}
}
