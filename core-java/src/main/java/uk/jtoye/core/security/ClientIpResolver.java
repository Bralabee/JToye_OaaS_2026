package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for a request, X-Forwarded-For first with a
 * {@code getRemoteAddr()} fallback (issue #88 [P1-6]).
 *
 * <p>Used to key the public (tenant-less) rate-limit bucket in
 * {@code RateLimitInterceptor}, and the tighter DSAR-intake bucket in
 * {@code DsarIntakeRateLimiter} (Phase 31). Never returns {@code null} or empty — a null key
 * would NPE the Redis bucket build — falling back to the literal {@code "unknown"}
 * sentinel as a last resort.
 *
 * <p><b>Visibility (Phase 31, plan 31-05):</b> widened from package-private to public so the
 * second tenant-less limiter can key on the SAME resolution rather than reimplementing it.
 * Duplicating the X-Forwarded-For handling would mean two places that can disagree about what a
 * client is, and the spoofing caveat below would then have to be true of both.
 *
 * <p><strong>Spoofing caveat (issue #88 T-88-02, accepted &amp; documented):</strong>
 * {@code X-Forwarded-For} is <em>client-controllable</em>. A hostile guest can set
 * an arbitrary first hop to dodge or poison the IP bucket, unless a trusted proxy
 * in front of Core (edge-go / the ingress / load balancer) <em>overwrites</em> —
 * not appends to — the header with the real peer address. Keying on the first hop
 * is the pragmatic Core-layer choice for bounding guest abuse: it Just Works behind
 * a correctly-configured trusted proxy and degrades to {@code getRemoteAddr()} when
 * no header is present. Operators wanting hard anti-abuse guarantees MUST ensure the
 * ingress overwrites XFF rather than trusting whatever the client sent.
 */
public final class ClientIpResolver {

    private static final String XFF_HEADER = "X-Forwarded-For";
    private static final String UNKNOWN = "unknown";

    private ClientIpResolver() {
    }

    /**
     * @param request the current request (non-null)
     * @return the first X-Forwarded-For hop when present and non-blank, otherwise
     *         {@code request.getRemoteAddr()}, otherwise the {@code "unknown"}
     *         sentinel; never {@code null} or empty.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(XFF_HEADER);
        if (forwarded != null && !forwarded.isBlank()) {
            String firstHop = forwarded.split(",", 2)[0].trim();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : UNKNOWN;
    }
}
