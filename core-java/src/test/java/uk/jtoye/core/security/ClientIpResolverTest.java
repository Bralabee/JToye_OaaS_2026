package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientIpResolver} (37-24, D-19; originally issue #88 [P1-6]).
 *
 * <p>Plain JUnit 5 + Spring's {@link MockHttpServletRequest} — no Spring context, no
 * Testcontainers. The default resolver trusts NO proxy, so {@code X-Forwarded-For} from any peer
 * is inert and the socket address is the client; the resolver never returns null (a null bucket
 * key would NPE the public rate-limit branch).
 *
 * <p>The three "XFF wins" assertions this file held until 37-24 encoded the UXT-006 defect (first
 * hop, from anyone). They are REPLACED here by their inverse under trust-none, not weakened: the
 * same requests now resolve to the socket peer.
 */
class ClientIpResolverTest {

    private static final ClientIpResolver TRUST_NONE = new ClientIpResolver(List.of());

    @Test
    void noXffHeader_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void xffSingleValue_fromAnUntrustedPeer_isIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void xffMultipleHops_fromAnUntrustedPeer_isIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void xffWithWhitespace_fromAnUntrustedPeer_isIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 70.41.3.18 ");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void blankXff_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void emptyXff_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "");

        assertThat(TRUST_NONE.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void remoteAddrAlsoNull_returnsUnknownSentinel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        String resolved = TRUST_NONE.resolve(request);
        assertThat(resolved).isEqualTo("unknown");
        assertThat(resolved).isNotNull();
    }
}
