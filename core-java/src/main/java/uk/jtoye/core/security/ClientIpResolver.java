package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;

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
 * <p><b>Behind a trusted proxy</b> the hops are walked from the RIGHT (the hop the trusted proxy
 * itself appended), every hop that is itself a trusted proxy is skipped, and the first address
 * that is not is the client. The leftmost value is the one an attacker controls and is never
 * preferred. Two cases end the walk early, and both answer with the SOCKET PEER:
 * <ul>
 *   <li>a hop that is not an IP literal (text, a hostname, anything a proxy would never append):
 *       the walk stops, because nothing to its left is vouched for by a trusted hop;</li>
 *   <li>every hop is a trusted proxy (the chain is exhausted).</li>
 * </ul>
 * Not "the last trusted hop" in either case, deliberately: a client behind the proxy can spell
 * in-range addresses ({@code 10.0.0.1, 10.0.0.2, ...}) as easily as any other text, so any
 * header-derived fallback would let it choose its bucket after all. The socket peer is the only
 * value it cannot write.
 *
 * <p><b>Literals only, no DNS.</b> Every address — configured range, peer, hop — is parsed with
 * {@link InetAddress#ofLiteral}, which never resolves a name. A hop such as {@code evil.example}
 * must not turn a rate-limit check into a DNS lookup the attacker chose.
 *
 * <p><b>One client, one spelling.</b> An IPv4-mapped IPv6 literal ({@code ::ffff:198.51.100.7})
 * is folded to its dotted quad, and an IPv6 address is rendered in its RFC 5952 canonical form, so
 * the same client cannot key several buckets by varying the spelling the proxy or it wrote.
 *
 * <p><b>Refusals at startup.</b> {@code 0.0.0.0/0}, {@code ::/0}, a prefix length outside the
 * address size, or an entry that is not an IP literal fail the bean with a message naming
 * {@code jtoye.security.trusted-proxies} and the entry: "trust everyone" is exactly UXT-006 again,
 * and {@code k8s/scripts/check-render-invariants.sh} refuses the same shapes in a rendered overlay
 * before they reach a cluster.
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
     * @throws IllegalArgumentException on a {@code /0} range, a prefix outside the address size,
     *                                  or an entry that is not an IP literal — naming the property
     */
    public ClientIpResolver(@Value("${" + TRUSTED_PROXIES_PROPERTY + ":}") List<String> trustedProxyCidrs) {
        List<String> declared = trustedProxyCidrs == null ? List.of() : trustedProxyCidrs;
        this.trustedProxies = declared.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(ClientIpResolver::matcherFor)
                .toList();
    }

    /**
     * @param request the current request (non-null)
     * @return the socket peer address unless that peer is a trusted proxy, in which case the
     *         right-most {@code X-Forwarded-For} hop that is not itself a trusted proxy (the socket
     *         peer again when a hop is malformed or every hop is trusted); the {@code "unknown"}
     *         sentinel only when the request carries no peer address at all. IPv4-mapped literals
     *         are folded to the dotted quad and IPv6 is canonical. Never {@code null} or empty.
     */
    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return UNKNOWN;
        }
        Optional<InetAddress> peerAddress = parseLiteral(peer.trim());
        if (peerAddress.isEmpty()) {
            // Not an IP literal (a local socket name, say): keyed as written, and never trusted.
            return peer.trim();
        }
        String socketPeer = canonical(peerAddress.get());
        if (!isTrusted(peerAddress.get())) {
            return socketPeer;
        }
        String forwarded = request.getHeader(XFF_HEADER);
        if (forwarded == null || forwarded.isBlank()) {
            return socketPeer;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            Optional<InetAddress> hopAddress = parseLiteral(hop);
            if (hopAddress.isEmpty()) {
                return socketPeer; // malformed: nothing to its left is vouched for
            }
            if (isTrusted(hopAddress.get())) {
                continue;
            }
            return canonical(hopAddress.get());
        }
        return socketPeer; // every hop was a trusted proxy
    }

    /**
     * @return whether {@code address} (an IP literal; a non-literal is never trusted) lies inside
     *         a configured trusted range
     */
    public boolean isTrustedProxy(String address) {
        return address != null && parseLiteral(address.trim()).map(this::isTrusted).orElse(false);
    }

    private boolean isTrusted(InetAddress address) {
        String literal = canonical(address);
        for (IpAddressMatcher matcher : trustedProxies) {
            if (matcher.matches(literal)) {
                return true;
            }
        }
        return false;
    }

    private static IpAddressMatcher matcherFor(String entry) {
        int slash = entry.indexOf('/');
        String addressText = slash < 0 ? entry : entry.substring(0, slash);
        InetAddress address = parseLiteral(addressText)
                .orElseThrow(() -> refuse(entry, "is not an IP literal (a hostname is never resolved)"));
        String suffix = "";
        if (slash >= 0) {
            int bits = address.getAddress().length * 8;
            int prefix;
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw refuse(entry, "has a prefix length that is not a number");
            }
            if (prefix == 0) {
                throw refuse(entry, "is the whole address space (/0) and would trust every client, "
                        + "re-opening UXT-006");
            }
            if (prefix < 0 || prefix > bits) {
                throw refuse(entry, "has a prefix length outside 1.." + bits);
            }
            suffix = "/" + prefix;
        }
        return new IpAddressMatcher(canonical(address) + suffix);
    }

    private static IllegalArgumentException refuse(String entry, String why) {
        return new IllegalArgumentException(TRUSTED_PROXIES_PROPERTY + ": entry '" + entry + "' " + why
                + ". Declare only the private ranges of the proxies that append X-Forwarded-For "
                + "(the ingress controller's pod CIDR), or leave the list empty to trust none.");
    }

    /** An IP literal, or empty; never a name lookup. Mapped IPv4 arrives as {@link Inet4Address}. */
    private static Optional<InetAddress> parseLiteral(String text) {
        try {
            return Optional.of(InetAddress.ofLiteral(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The one spelling of a client address this platform keys on (37-28, D-27): an IP literal in the
     * canonical form {@link #resolve} answers with — dotted quad for IPv4, mapped IPv4 included; RFC 5952
     * for IPv6 — and any other text (the {@link #UNKNOWN} sentinel, a non-literal peer name) trimmed, as
     * written. {@code ClientIpDigester} digests THIS, so a client cannot hold two cash-order counts by
     * respelling what the proxy wrote. Never a name lookup.
     *
     * @param text a client address in any spelling; non-null
     * @return the canonical literal, or the trimmed text when it is not an IP literal
     */
    public static String canonicalLiteral(String text) {
        String trimmed = text.trim();
        return parseLiteral(trimmed).map(ClientIpResolver::canonical).orElse(trimmed);
    }

    /** Dotted quad for IPv4 (mapped included); RFC 5952 (lower-case, longest zero run as ::) for IPv6. */
    static String canonical(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        byte[] bytes = address.getAddress();
        int[] groups = new int[8];
        for (int g = 0; g < 8; g++) {
            groups[g] = ((bytes[2 * g] & 0xff) << 8) | (bytes[2 * g + 1] & 0xff);
        }
        int bestStart = -1;
        int bestLength = 0;
        for (int g = 0; g < 8; ) {
            if (groups[g] != 0) {
                g++;
                continue;
            }
            int start = g;
            while (g < 8 && groups[g] == 0) {
                g++;
            }
            if (g - start > bestLength) {
                bestStart = start;
                bestLength = g - start;
            }
        }
        if (bestLength < 2) {
            bestStart = -1; // a single zero group is written out, never compressed
        }
        StringBuilder out = new StringBuilder(39);
        for (int g = 0; g < 8; g++) {
            if (g == bestStart) {
                out.append("::");
                g += bestLength - 1;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ':') {
                out.append(':');
            }
            out.append(Integer.toHexString(groups[g]));
        }
        return out.toString();
    }
}
