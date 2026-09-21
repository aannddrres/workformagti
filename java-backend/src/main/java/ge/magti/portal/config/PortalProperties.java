package ge.magti.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

	/**
	 * The environments that are NOT production. Everything else is.
	 *
	 * <p>Deliberately the complement of what you would expect: listing the
	 * production spellings instead ({@code production}, {@code prod},
	 * {@code prd}, ...) can never be finished, and every name missing from it
	 * fails OPEN. {@code APP_ENV=produciton} is not a hypothetical -- it is
	 * the same typo class as the {@code APP_ENV=prod} this field's comment
	 * has named since SEC-01, and under a production allowlist both of them
	 * silently turn off every check.
	 *
	 * <p>Listed this way an unrecognised value fails SAFE, which is the rule
	 * the default above and the blank handling below already follow: the
	 * insecure mode is the one that must be asked for, by name.
	 *
	 * <p><b>Only local-machine names are here.</b> {@code staging},
	 * {@code qa}, {@code uat}, {@code sandbox} and {@code preprod} are
	 * deployed, shared environments reachable by people other than the
	 * developer who started them, so the guard applies to them exactly as it
	 * does to production. That is a change from the old behaviour, where
	 * every string except "production" skipped every check.
	 */
	private static final Set<String> DEVELOPMENT_ENVIRONMENTS =
			Set.of("development", "dev", "local", "test");

	/**
	 * True unless APP_ENV explicitly names a local development environment.
	 *
	 * <p>This used to be {@code equalsIgnoreCase("production")} and nothing
	 * else, which made every other value -- a stray space, a typo, an alias,
	 * an empty string -- non-production. Both callers turn on that answer:
	 * {@link ProductionSafetyGuard} returns before its first check, and
	 * {@code AuthenticationService:80} gates the password-less dev login on
	 * {@code !isProduction()}. So any of those values disabled the boot-time
	 * guard AND made the bypass eligible in the same step -- both halves of
	 * the SEC-01 fix, undone by a character nobody could see (DEC-P04) or a
	 * shorthand somebody thought was equivalent (DEC-P05).
	 *
	 * <p>Now the only way to reach the insecure posture is to name a
	 * development environment, spelled correctly. Whitespace and casing are
	 * forgiven because they are never intent; the word itself is not, because
	 * it always is.
	 *
	 * <p>A deployment that gets this wrong now fails loudly -- the guard
	 * refuses to boot and names the variable -- instead of starting with the
	 * dev login live.
	 */
	public boolean isProduction() {
		if (appEnv == null) {
			return true;
		}
		return !DEVELOPMENT_ENVIRONMENTS.contains(appEnv.strip().toLowerCase(Locale.ROOT));
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
		@NestedConfigurationProperty
		private final Corporate corporate = new Corporate();

		public Corporate getCorporate() {
			return corporate;
		}

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

	/**
	 * The company's OAuth2 token endpoint, {@code ldap_auth} grant: the portal
	 * sends the employee's credentials and the directory answers with who
	 * they are and which InfoPortal roles they hold.
	 *
	 * <p>The keys mirror the names in IT's configuration sheet
	 * (OAUTH_SERVICE_URI, OAUTH_CLIENT_ID, OAUTH_SECRET, OAUTH_GRANT_TYPE,
	 * OAUTH_DOMAIN) so that sheet can be copied into the environment line for
	 * line. Measured against the live service on 2026-09-21; see
	 * docs/QUESTIONS_FOR_IT.md No.13.
	 *
	 * <p><b>Off unless asked for.</b> With it off, a production deployment
	 * lets nobody in -- there is no local-password fallback (PO-25).
	 */
	public static class Corporate {
		private boolean enabled = false;
		/** Base URI of the authorization server; the token endpoint is {@code oauth/token} under it. */
		private String serviceUri;
		private String clientId;
		/**
		 * The value IT delivers as OAUTH_SECRET: base64 of {@code client:secret},
		 * sent as-is after {@code Basic}. A secret -- environment only, never
		 * source control.
		 */
		private String clientCredential;
		private String grantType = "ldap_auth";
		/** The one address domain the portal accepts at sign-in, e.g. {@code @magticom.ge}. */
		private String domain = "@magticom.ge";
		/**
		 * How the typed address travels as {@code username}: {@code email} as
		 * typed (IT's sample), or {@code login} with the domain removed (what
		 * the WebAdmin client accepted during the 2026-09-21 measurement).
		 */
		private String usernameFormat = "email";
		/**
		 * Directory authority -> portal role, comma-separated
		 * {@code AUTHORITY=role}. The highest mapped role wins; none mapped
		 * means operator. The defaults are the names proposed to IT.
		 */
		private String roleMap = "INFOPORTAL_ADMIN=admin,INFOPORTAL_CONTENT_ADMIN=content_admin,"
				+ "INFOPORTAL_MANAGER=manager,INFOPORTAL_OPERATOR=operator";
		/** Token claim carrying the department, when IT adds one. Never used to blank a department. */
		private String departmentClaim = "department";
		/** Token claim carrying the full name, when IT adds one. */
		private String nameClaim = "full_name";
		private int connectTimeoutSeconds = 5;
		private int readTimeoutSeconds = 10;

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean enabled) { this.enabled = enabled; }
		public String getServiceUri() { return serviceUri; }
		public void setServiceUri(String serviceUri) { this.serviceUri = serviceUri; }
		public String getClientId() { return clientId; }
		public void setClientId(String clientId) { this.clientId = clientId; }
		public String getClientCredential() { return clientCredential; }
		public void setClientCredential(String clientCredential) { this.clientCredential = clientCredential; }
		public String getGrantType() { return grantType; }
		public void setGrantType(String grantType) { this.grantType = grantType; }
		public String getDomain() { return domain; }
		public void setDomain(String domain) { this.domain = domain; }
		public String getUsernameFormat() { return usernameFormat; }
		public void setUsernameFormat(String usernameFormat) { this.usernameFormat = usernameFormat; }
		public String getRoleMap() { return roleMap; }
		public void setRoleMap(String roleMap) { this.roleMap = roleMap; }
		public String getDepartmentClaim() { return departmentClaim; }
		public void setDepartmentClaim(String departmentClaim) { this.departmentClaim = departmentClaim; }
		public String getNameClaim() { return nameClaim; }
		public void setNameClaim(String nameClaim) { this.nameClaim = nameClaim; }
		public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
		public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
		public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
		public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }

		/** Roles come from the directory on every sign-in whenever this is on (owner decision, 2026-09-21). */
		public boolean rolesManagedByDirectory() { return enabled; }
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
