package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Ports security.py's create_access_token/get_current_user token handling.
 * Same claim shape ({@code sub}=email, {@code exp}=expiry) and the same
 * HS256 secret/algorithm/expiry config keys -- a token minted by the Python
 * app and one minted here carry identical claims, so either side can verify
 * the other's token during the coexistence period (Phase 1/2).
 *
 * <p>Cross-stack risk worth flagging (not silently papered over): JJWT
 * enforces HS256's 256-bit (32-byte) minimum key length and throws
 * WeakKeyException below that; python-jose does not enforce this. If the
 * real production SECRET_KEY is shorter than 32 bytes, Python accepts it
 * but this service refuses to start signing tokens. Verify the real secret's
 * length before cutting over.
 */
@Service
public class JwtService {

	private final PortalProperties.Jwt jwtConfig;
	private final SecretKey signingKey;

	public JwtService(PortalProperties properties) {
		this.jwtConfig = properties.getSecurity().getJwt();
		this.signingKey = Keys.hmacShaKeyFor(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * SEC-14. The claim carrying {@link ge.magti.portal.domain.User#getTokenVersion()}
	 * at minting time. Two characters because it is on every request of every
	 * user; short claim names are the one place JWT size actually matters.
	 */
	public static final String TOKEN_VERSION_CLAIM = "tv";

	/** Mirrors create_access_token(data={"sub": email, "role": role}). */
	public String createAccessToken(Map<String, Object> claims) {
		return createAccessToken(claims, Duration.ofMinutes(jwtConfig.getAccessTokenExpireMinutes()));
	}

	/**
	 * The token minted at login: subject, role, and the user's current token
	 * version. Centralised here rather than assembled at each call site
	 * because a token minted without the {@code tv} claim reads as version 0
	 * and would quietly survive a logout -- the exact bug this claim exists
	 * to close.
	 */
	public String createAccessTokenFor(ge.magti.portal.domain.User user) {
		return createAccessToken(Map.of(
				"sub", user.getEmail(),
				"role", user.getRole().value(),
				TOKEN_VERSION_CLAIM, user.getTokenVersion()));
	}

	/**
	 * The token version a token asserts. A token minted before SEC-14 carries
	 * no such claim; it reads as 0, which is every existing row's value, so
	 * deploying this does not log anybody out.
	 */
	public static long tokenVersionOf(Claims claims) {
		Object raw = claims.get(TOKEN_VERSION_CLAIM);
		return raw instanceof Number number ? number.longValue() : 0L;
	}

	public String createAccessToken(Map<String, Object> claims, Duration expiresIn) {
		Instant now = Instant.now();
		return Jwts.builder()
				.claims(claims)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(expiresIn)))
				.signWith(signingKey)
				.compact();
	}

	/**
	 * Mirrors get_current_user's decode step -- returns empty on any
	 * validation failure (bad signature, expired, malformed) rather than
	 * throwing, since the caller's job is a clean 401, not a stack trace.
	 */
	public Optional<Claims> parseAndValidate(String token) {
		try {
			Claims claims = Jwts.parser()
					.verifyWith(signingKey)
					.build()
					.parseSignedClaims(token)
					.getPayload();
			return Optional.of(claims);
		} catch (JwtException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Mirrors the "sub" claim being the user's email everywhere in security.py. */
	public Optional<String> extractSubject(String token) {
		return parseAndValidate(token).map(Claims::getSubject);
	}
}
