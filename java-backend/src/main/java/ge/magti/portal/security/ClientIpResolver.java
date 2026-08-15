package ge.magti.portal.security;

import ge.magti.portal.config.PortalProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out who actually sent a request, for rate limiting and for the audit
 * trail (audit SEC-04, PR-04).
 *
 * <h2>The problem</h2>
 *
 * {@code AuthController} keyed both the login rate limiter and every failed
 * login's audit row on {@code httpRequest.getRemoteAddr()}, while
 * {@code server.forward-headers-strategy} was never set. Behind this
 * project's own nginx -- which does send {@code X-Real-IP} and
 * {@code X-Forwarded-For} (nginx.conf.template:23-24) -- Spring was not
 * configured to read either, so {@code getRemoteAddr()} returned the
 * <i>proxy's</i> address for every user in the company. Two consequences:
 *
 * <ul>
 *   <li><b>10 login attempts per minute was the ceiling for everybody.</b>
 *       One person with a stale saved password could lock out all ~600
 *       staff, and it would look like an outage, not a rate limit.
 *   <li><b>The audit trail could not identify anyone.</b> Every
 *       {@code LOGIN_FAILED} row recorded the same proxy IP, so the one
 *       record that exists to answer "where did this come from" answered
 *       "from the load balancer".
 * </ul>
 *
 * <h2>Why not simply {@code forward-headers-strategy=framework}</h2>
 *
 * That is the usual one-line answer, and it would be a security regression
 * here. Spring's {@code ForwardedHeaderFilter} trusts {@code X-Forwarded-For}
 * <i>unconditionally</i>: anyone who can reach the application directly --
 * another pod, anything inside the cluster network, or the internet if the
 * service is ever exposed without the proxy in front -- can then set the
 * header to whatever they like and get a fresh rate-limit bucket per
 * request, plus a forged IP in the audit log. That is strictly worse than
 * today's "everyone shares one bucket".
 *
 * <p>So the header is only believed when the machine that actually opened
 * the connection is one we said to trust. {@code portal.security.trusted-proxies}
 * is empty by default, which means <b>headers are ignored entirely and this
 * behaves exactly like {@code getRemoteAddr()}</b> -- the safe direction to
 * fail. A deployment behind nginx sets it to the proxy's address or subnet
 * and gets real client IPs; a deployment that forgets loses granularity but
 * can never be spoofed.
 *
 * <h2>Which entry of X-Forwarded-For</h2>
 *
 * The header is client-controlled and append-only: {@code
 * X-Forwarded-For: <spoofed>, <real client>, <proxy1>}. Taking the leftmost
 * entry -- the common mistake -- takes whatever the client typed. This walks
 * from the right, skipping addresses that are themselves trusted proxies,
 * and returns the first untrusted one: the closest hop we have no reason to
 * believe, which is the best available identification of the caller.
 */
@Component
public class ClientIpResolver {

    private static final Logger logger = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<IpAddressMatcher> trustedProxies;
    private final boolean trustConfigured;

    public ClientIpResolver(PortalProperties properties) {
        List<String> configured = properties.getSecurity().getTrustedProxies();
        this.trustedProxies = compile(configured);
        this.trustConfigured = !this.trustedProxies.isEmpty();
        if (!trustConfigured) {
            logger.info("No portal.security.trusted-proxies configured: X-Forwarded-For is ignored and the "
                    + "socket peer address is used. Correct when the app is reached directly; behind a reverse "
                    + "proxy, set TRUSTED_PROXIES or every user shares one rate-limit bucket and one audit IP.");
        }
    }

    private static List<IpAddressMatcher> compile(List<String> cidrs) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String cidr : cidrs) {
            String trimmed = cidr == null ? "" : cidr.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                matchers.add(new IpAddressMatcher(trimmed));
            } catch (IllegalArgumentException e) {
                // Deliberately skipped rather than fatal: a typo in this list
                // must not take the application down, and skipping fails
                // CLOSED (that proxy stops being trusted), so the failure
                // mode is lost granularity, never a spoofable header.
                logger.warn("Ignoring unparseable portal.security.trusted-proxies entry '{}': {}", trimmed, e.getMessage());
            }
        }
        return List.copyOf(matchers);
    }

    /** Never null; falls back to the socket peer, and to "unknown" only if even that is absent. */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!trustConfigured || peer == null || !isTrustedProxy(peer)) {
            return peer == null ? "unknown" : peer;
        }

        String header = request.getHeader(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return peer;
        }

        String[] hops = header.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        // Every hop is a proxy we trust -- the request never came from a
        // client we can name. The peer is the most truthful thing left.
        return peer;
    }

    private boolean isTrustedProxy(String address) {
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // A malformed address in the header itself (it is attacker
                // input) must not throw out of the request.
                return false;
            }
        }
        return false;
    }
}
