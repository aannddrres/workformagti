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
	@NestedConfigurationProperty
	private final Rollout rollout = new Rollout();

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

	public Security getSecurity() {
		return security;
	}

	public Rollout getRollout() {
		return rollout;
	}

	/**
	 * Reversible Phase 4/5 cutover infrastructure.
	 *
	 * <p>Both switches deliberately default to the legacy behaviour and are
	 * independent so one policy can be rolled back without moving the other.
	 * Phase 9A only binds these values; no decision call site reads them yet.
	 */
	public static class Rollout {
		private boolean leadershipScopeEnabled = false;
		private boolean complianceEligibilityEnabled = false;
		private boolean fileEntitlementEnabled = false;

		public boolean isLeadershipScopeEnabled() {
			return leadershipScopeEnabled;
		}

		public void setLeadershipScopeEnabled(boolean leadershipScopeEnabled) {
			this.leadershipScopeEnabled = leadershipScopeEnabled;
		}

		public boolean isComplianceEligibilityEnabled() {
			return complianceEligibilityEnabled;
		}

		public void setComplianceEligibilityEnabled(boolean complianceEligibilityEnabled) {
			this.complianceEligibilityEnabled = complianceEligibilityEnabled;
		}

		/**
		 * DEC-P01 enforcement for /uploads/{filename}: serve a file only when
		 * content the caller may read references it.
		 *
		 * <p>Off means shadow: the decision is still computed and logged, and
		 * the file is still served. That order matters here more than usual.
		 * The index this rule reads is maintained by every content save, and a
		 * save path that forgot to maintain it would not fail loudly -- it
		 * would quietly make pictures vanish from articles that are otherwise
		 * fine. Shadow first turns that from an outage into a log line.
		 */
		public boolean isFileEntitlementEnabled() {
			return fileEntitlementEnabled;
		}

		public void setFileEntitlementEnabled(boolean fileEntitlementEnabled) {
			this.fileEntitlementEnabled = fileEntitlementEnabled;
		}
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
		@NestedConfigurationProperty
		private final Session session = new Session();

		public Jwt getJwt() {
			return jwt;
		}

		public Cookie getCookie() {
			return cookie;
		}

		public Session getSession() {
			return session;
		}
	}

	public static class Session {
		private long idleMinutes = 30;
		private long maximumMinutes = 480;

		public long getIdleMinutes() { return idleMinutes; }
		public void setIdleMinutes(long idleMinutes) { this.idleMinutes = idleMinutes; }
		public long getMaximumMinutes() { return maximumMinutes; }
		public void setMaximumMinutes(long maximumMinutes) { this.maximumMinutes = maximumMinutes; }
	}

	public static class Jwt {
		private String secret;
		private String algorithm = "HS256";
		private long accessTokenExpireMinutes = 480;

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
		private String sameSite = "strict";

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
