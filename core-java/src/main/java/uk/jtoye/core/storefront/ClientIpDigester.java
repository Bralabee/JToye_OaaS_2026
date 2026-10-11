package uk.jtoye.core.storefront;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RED scaffold (Phase 37-28, D-27): compiles so the failing tests can run; digests nothing and
 * validates nothing yet. The GREEN commit replaces this body.
 */
@Component
public class ClientIpDigester {

    /** The Spring property the key is read from; named in every refusal message, never its value. */
    public static final String PROPERTY = "jtoye.storefront.client-ip-hmac-key";

    @SuppressWarnings("unused")
    private final String hexKey;

    public ClientIpDigester(@Value("${" + PROPERTY + ":}") String hexKey) {
        this.hexKey = hexKey;
    }

    /**
     * @param ip the client address as resolved by {@code ClientIpResolver}
     * @return the keyed digest, or {@code null} when there is nothing to digest
     */
    public String digest(String ip) {
        return null;
    }
}
