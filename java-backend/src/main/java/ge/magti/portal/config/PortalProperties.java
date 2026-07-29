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

	private String appEnv = "development";

	@NestedConfigurationProperty
	private final Security security = new Security();

	public String getAppEnv() {
		return appEnv;
	}

	public void setAppEnv(String appEnv) {
		this.appEnv = appEnv;
	}

	public boolean isProduction() {
		return "production".equalsIgnoreCase(appEnv);
	}

	public Security getSecurity() {
		return security;
	}

	public static class Security {
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
