package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Resolves the client address a request is attributed to, from a config-declared trusted-proxy
 * list (Phase 37, plan 37-24; D-19; RWO-006 / UXT-006).
 *
 * <p>Used to key the public (tenant-less) rate-limit bucket in {@code RateLimitInterceptor} and
 * the tighter DSAR-intake bucket in {@code DsarIntakeRateLimiter}; the cash-order per-IP cap
 * (37-28, D-27) keys on the same resolution. One bean, so there is exactly one answer to "what is
 * this client", and the trust rule below is true of every caller at once.
 *
 * <p><b>The rule (MDN's trusted-proxy-list method).</b> {@code X-Forwarded-For} is read ONLY when
 * the socket peer ({@code getRemoteAddr()}) is inside one of the CIDRs in
 * {@code jtoye.security.trusted-proxies}. Otherwise the socket peer IS the client, and the header
 * is inert: a client can write any header it likes, but it cannot forge the address its packets
 * come from. The default is EMPTY — trust none — because a resolver that believed the header from
 * anyone let the persona run (UXT-006) mint a fresh bucket per request just by rotating it, which
 * was the regression of closed #88.
 *
 * <p><b>Behind a trusted proxy</b> the header is walked from the RIGHT (the hop the trusted proxy
 * itself appended) and the first address that is not a trusted proxy is the client; the leftmost
 * value is the one an attacker controls and is never preferred. (This revision takes the
 * right-most hop; plan 37-24 Task 2 completes the walk: skipping trusted hops, IPv6 and
 * IPv4-mapped peers, malformed hops, and the refusal of a {@code /0} range at startup.)
 *
 * <p>Never returns {@code null} or empty: a null bucket key would NPE the Redis bucket build, so
 * a request with no peer address at all keys on the literal {@code "unknown"} sentinel.
 *
 * <p>{@code server.forward-headers-strategy} is deliberately NOT set anywhere: Tomcat's
 * {@code RemoteIpValve} would rewrite {@code getRemoteAddr()} from its own internal-proxy regex,
 * a second, silent trust list that this bean could not see.
 */
@Component
public class ClientIpResolver {

    /** The Spring property the trusted ranges are read from; named in every refusal message. */
    public static final String TRUSTED_PROXIES_PROPERTY = "jtoye.security.trusted-proxies";

    static final String XFF_HEADER = "X-Forwarded-For";
    static final String UNKNOWN = "unknown";

    private final List<IpAddressMatcher> trustedProxies;

    /**
     * @param trustedProxyCidrs the CIDR ranges whose {@code X-Forwarded-For} is believed
     *                          ({@code jtoye.security.trusted-proxies}, env
     *                          {@code TRUSTED_PROXY_CIDRS}); empty or absent = trust none
     */
    public ClientIpResolver(@Value("${" + TRUSTED_PROXIES_PROPERTY + ":}") List<String> trustedProxyCidrs) {
        List<String> declared = trustedProxyCidrs == null ? List.of() : trustedProxyCidrs;
        this.trustedProxies = declared.stream()
                .map(String::trim)
                .filter(cidr -> !cidr.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
    }

    /**
     * @param request the current request (non-null)
     * @return the socket peer address, unless that peer is a trusted proxy, in which case the
     *         right-most {@code X-Forwarded-For} hop; the {@code "unknown"} sentinel only when the
     *         request carries no peer address at all. Never {@code null} or empty.
     */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN;
        }
        if (!isTrustedProxy(peer)) {
            return peer;
        }
        String forwarded = request.getHeader(XFF_HEADER);
        if (forwarded == null || forwarded.isBlank()) {
            return peer;
        }
        String[] hops = forwarded.split(",");
        String rightmost = hops[hops.length - 1].trim();
        return rightmost.isEmpty() ? peer : rightmost;
    }

    /**
     * @return whether {@code address} (an IP literal) lies inside a configured trusted range
     */
    public boolean isTrustedProxy(String address) {
        for (IpAddressMatcher matcher : trustedProxies) {
            if (matcher.matches(address)) {
                return true;
            }
        }
        return false;
    }
}
