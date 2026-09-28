package ge.magti.portal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Angular's XSRF cookie, given the lifetime of the session it protects.
 *
 * <p>{@link CookieCsrfTokenRepository} writes a session cookie, so it is gone
 * when the browser closes. The access token is not: it is persistent and good
 * for {@code portal.security.jwt.access-token-expire-minutes}. A workstation
 * whose browser restarted mid-shift therefore came back still signed in and
 * with no CSRF token at all, and the first POST the app makes -- the
 * idle-session heartbeat, fired as it starts -- had no header to send. That is
 * refused, the refusal reaches the SPA as 401, and
 * {@code unauthorized.interceptor.ts} signs the user out. The two cookies now
 * expire together, so "signed in but without a CSRF token" is not a state the
 * browser can be in.
 *
 * <p><b>Two delegates rather than one customizer</b>, because of the order
 * CookieCsrfTokenRepository writes in: it sets {@code Max-Age} itself -- the
 * lifetime for a real token, zero to delete one -- and applies the customizer
 * afterwards (verified in the 7.1.0 bytecode: {@code maxAge} then
 * {@code Consumer.accept}). A customizer that always set a lifetime would
 * therefore turn a deletion into an empty cookie that outlives the session,
 * and an empty cookie reads back as "no token" -- which would have the server
 * mint a fresh one on every request, the very thing
 * {@code SecurityConfig} stops. Deletions go to a delegate that adds no
 * lifetime of its own and keeps Spring's zero.
 */
final class SessionLifetimeCsrfTokenRepository implements CsrfTokenRepository {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CookieCsrfTokenRepository issuing;
    private final CookieCsrfTokenRepository deleting;

    SessionLifetimeCsrfTokenRepository(String cookieName, boolean secure, Duration lifetime) {
        this.issuing = configured(cookieName, secure, (cookie) -> cookie.maxAge(lifetime));
        this.deleting = configured(cookieName, secure, (cookie) -> { });
    }

    private static CookieCsrfTokenRepository configured(
            String cookieName, boolean secure, Consumer<ResponseCookie.ResponseCookieBuilder> lifetime) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(cookieName);
        repository.setCookieCustomizer((cookie) -> {
            cookie.path("/").sameSite("Strict").secure(secure);
            lifetime.accept(cookie);
        });
        return repository;
    }

    /**
     * ASVS V11.5.1: 256 bits from SecureRandom. Spring's own generator uses a
     * UUID, 122 random bits, below the 128 the standard asks for.
     */
    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        CsrfToken template = this.issuing.generateToken(request);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return new DefaultCsrfToken(template.getHeaderName(), template.getParameterName(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(random));
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return this.issuing.loadToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        (token == null ? this.deleting : this.issuing).saveToken(token, request, response);
    }
}
