package ge.magti.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the {@code portal.*} keys in application.yml. Mirrors config.py's
 * {@code Settings} class one-for-one so the two configs stay legible
 * side-by-side during the port.
 */
@Configuration
@ConfigurationProperties(prefix = "portal")
public class PortalProperties {

	/**
	 * Defaults to "production" deliberately (audit OPUS5 SEC-01): every
	 * insecure convenience in this app is gated on {@link #isProduction()},
	 * so a deployment that forgets APP_ENV must fail SAFE, not open. Local
	 * development asks for the insecure mode explicitly -- APP_ENV=development
	 * (plus ALLOW_DEV_LOGIN=true for the password-less bypass), or simply
	 * {@code --spring.profiles.active=dev} (application-dev.yml).
	 */
	private String appEnv = "production";

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

	public boolean isProduction() {
		return "production".equalsIgnoreCase(appEnv);
	}

	/**
	 * Whether {@code AuthenticationService}'s password-less bypass for the
	 * known dev/test emails is live. Deliberately needs BOTH halves: a
	 * non-production app-env AND an explicit
	 * {@code portal.security.allow-dev-login=true}. Before OPUS5 SEC-01 the
	 * only gate was {@code !isProduction()}, so one forgotten env var was
	 * enough to hand out SYSTEM_ADMIN tokens for any password.
	 * {@link ProductionSafetyGuard} refuses to boot if the flag is set with
	 * {@code portal.app-env=production}, and WARNs loudly when it is live.
	 */
	public boolean isDevLoginEnabled() {
		return !isProduction() && security.isAllowDevLogin();
	}

	public Security getSecurity() {
		return security;
	}

	public static class Security {
		@NestedConfigurationProperty
		private final Jwt jwt = new Jwt();
		@NestedConfigurationProperty
		private final Cookie cookie = new Cookie();

		/**
		 * Opt-in for the password-less dev login (ALLOW_DEV_LOGIN). Off by
		 * default so the insecure mode is the one that has to be asked for.
		 */
		private boolean allowDevLogin = false;

		public boolean isAllowDevLogin() {
			return allowDevLogin;
		}

		public void setAllowDevLogin(boolean allowDevLogin) {
			this.allowDevLogin = allowDevLogin;
		}

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
