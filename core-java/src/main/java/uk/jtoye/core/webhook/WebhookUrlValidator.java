package uk.jtoye.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * Validates a vendor-supplied webhook {@code target_url} before it becomes an
 * egress target in the delivery engine (COMMS-04, threat T-22-03-01 SSRF).
 *
 * <p>Two guards, both raising {@link IllegalArgumentException} (mapped to an
 * RFC 7807 400 by {@code GlobalExceptionHandler}):
 * <ol>
 *   <li><b>HTTPS-only</b> — always enforced (D-05). A non-HTTPS scheme is
 *       rejected before any DNS lookup.</li>
 *   <li><b>Anti-SSRF</b> — when {@code webhook.target.block-private-ranges} is on
 *       (default), the host is resolved and every returned address is rejected if
 *       it is loopback / any-local / link-local (incl. the {@code 169.254.169.254}
 *       cloud-metadata endpoint) / RFC1918 site-local / multicast / IPv6
 *       unique-local. Resolution failures fail closed.</li>
 * </ol>
 * The toggle exists so hermetic tests and offline dev can skip DNS while HTTPS
 * stays enforced; it is never a way to reach internal hosts in production.
 */
@Component
public class WebhookUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlValidator.class);

    private final boolean blockPrivateRanges;

    public WebhookUrlValidator(
            @Value("${webhook.target.block-private-ranges:true}") boolean blockPrivateRanges) {
        this.blockPrivateRanges = blockPrivateRanges;
    }

    /**
     * @throws IllegalArgumentException if the URL is malformed, not HTTPS, or
     *                                  (when SSRF blocking is on) resolves to a
     *                                  private/loopback/link-local address.
     */
    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook target_url is required");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Webhook target_url is not a valid URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException("Webhook target_url must use HTTPS");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook target_url must include a host");
        }

        if (!blockPrivateRanges) {
            return; // SSRF checks disabled (dev/test); HTTPS is still enforced above.
        }

        // URI#getHost() returns the bracketed form for IPv6 literals; strip it.
        String resolveHost = host;
        if (resolveHost.startsWith("[") && resolveHost.endsWith("]")) {
            resolveHost = resolveHost.substring(1, resolveHost.length() - 1);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(resolveHost);
        } catch (UnknownHostException e) {
            // Fail closed: an unresolvable host cannot be proven safe.
            throw new IllegalArgumentException("Webhook target_url host could not be resolved");
        }

        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                // Log the host only — never the secret or the full row.
                log.warn("event=webhook_url_rejected reason=ssrf_blocked_range host={}", host);
                throw new IllegalArgumentException(
                        "Webhook target_url resolves to a disallowed (private, loopback, or link-local) address");
            }
        }
    }

    private boolean isBlocked(InetAddress addr) {
        if (addr.isLoopbackAddress()        // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()      // 0.0.0.0, ::
                || addr.isLinkLocalAddress()     // 169.254.0.0/16 (incl. 169.254.169.254 metadata), fe80::/10
                || addr.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
                || addr.isMulticastAddress()) {  // 224.0.0.0/4, ff00::/8
            return true;
        }
        // IPv6 unique-local fc00::/7 — Java's isSiteLocalAddress() only covers the
        // deprecated fec0::/10, so inspect the leading byte explicitly.
        byte[] bytes = addr.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return first == 0xfc || first == 0xfd;
        }
        return false;
    }
}
