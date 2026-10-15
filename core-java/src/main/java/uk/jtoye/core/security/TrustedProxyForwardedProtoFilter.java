package uk.jtoye.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Restores the request scheme behind a TLS-terminating proxy, believing {@code X-Forwarded-Proto}
 * ONLY from a peer {@link ClientIpResolver} trusts (T-37-59).
 *
 * <p><b>The good this keeps.</b> Until T-37-59 every k8s overlay ran Tomcat's {@code RemoteIpValve}
 * (deduced ON by Boot from the Kubernetes platform), which, among the damage it did, also set the
 * scheme to {@code https} from the ingress controller's {@code X-Forwarded-Proto}. Two things read
 * that: the absolute {@code Location} header every create endpoint builds with
 * {@code ServletUriComponentsBuilder.fromCurrentRequest()}, and Spring Security's HSTS writer, which
 * writes only on a secure request. With the valve gone ({@code forward-headers-strategy: none}) both
 * would see plain {@code http} behind the ingress. This filter gives that one fact back, from the
 * same single trust list the client address uses — never from the valve's every-private-range list.
 *
 * <p><b>The rule.</b> Applied only when the socket peer is inside
 * {@code jtoye.security.trusted-proxies} AND the request carries exactly one
 * {@code X-Forwarded-Proto} value that is exactly {@code http} or {@code https}. Anything else — an
 * untrusted peer, a missing, repeated, comma-joined or unknown value — leaves the request as the
 * socket delivered it. Compose declares no trusted proxy, so there it never applies. The host is
 * NOT taken from {@code X-Forwarded-Host}: the ingress forwards the client's {@code Host} as-is, and
 * a header-chosen host is exactly what put {@code evil.example} into the request under the valve.
 * The port follows the scheme only when the {@code Host} header named none (Tomcat then reports the
 * connector's default, 80); an explicit port is kept.
 */
public class TrustedProxyForwardedProtoFilter extends OncePerRequestFilter {

    static final String PROTO_HEADER = "X-Forwarded-Proto";

    private final ClientIpResolver clientIpResolver;

    public TrustedProxyForwardedProtoFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String proto = trustedForwardedProto(request);
        chain.doFilter(proto == null ? request : new ForwardedSchemeRequest(request, proto), response);
    }

    /** @return {@code http} or {@code https} when believed and different from the socket's scheme, else null */
    String trustedForwardedProto(HttpServletRequest request) {
        if (!clientIpResolver.isTrustedProxy(request.getRemoteAddr())) {
            return null;
        }
        List<String> values = request.getHeaders(PROTO_HEADER) == null
                ? List.of() : Collections.list(request.getHeaders(PROTO_HEADER));
        if (values.size() != 1) {
            return null;
        }
        String proto = values.get(0).trim().toLowerCase(Locale.ROOT);
        if (!proto.equals("https") && !proto.equals("http")) {
            return null;
        }
        return proto.equals(request.getScheme()) ? null : proto;
    }

    static final class ForwardedSchemeRequest extends HttpServletRequestWrapper {
        private final String scheme;

        ForwardedSchemeRequest(HttpServletRequest request, String scheme) {
            super(request);
            this.scheme = scheme;
        }

        @Override
        public String getScheme() {
            return scheme;
        }

        @Override
        public boolean isSecure() {
            return "https".equals(scheme);
        }

        @Override
        public int getServerPort() {
            int port = super.getServerPort();
            // Tomcat reports the socket scheme's default when the Host header named no port.
            if (port == defaultPort(super.getScheme())) {
                return defaultPort(scheme);
            }
            return port;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer(scheme).append("://").append(getServerName());
            int port = getServerPort();
            if (port != defaultPort(scheme)) {
                url.append(':').append(port);
            }
            return url.append(getRequestURI());
        }

        private static int defaultPort(String scheme) {
            return "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
    }
}
