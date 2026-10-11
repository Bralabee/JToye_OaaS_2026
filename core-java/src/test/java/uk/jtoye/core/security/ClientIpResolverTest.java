package uk.jtoye.core.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ClientIpResolver} (37-24, D-19; originally issue #88 [P1-6]).
 *
 * <p>Plain JUnit 5 + Spring's {@link MockHttpServletRequest} — no Spring context, no
 * Testcontainers. The cases are the plan's {@code <feature>} table: the socket peer is the client
 * unless it is a trusted proxy; behind a trusted proxy the {@code X-Forwarded-For} hops are walked
 * from the RIGHT, trusted hops are skipped, the first untrusted valid address is the client, and a
 * malformed hop stops the walk with the socket peer as the answer — never a value the client wrote.
 *
 * <p>The three "XFF wins" assertions this file held until 37-24 encoded the UXT-006 defect (first
 * hop, from anyone). They are REPLACED by their inverse under trust-none, not weakened: the same
 * requests now resolve to the socket peer.
 */
class ClientIpResolverTest {

    private static final ClientIpResolver TRUST_NONE = new ClientIpResolver(List.of());
    private static final ClientIpResolver TRUST_10_8 = new ClientIpResolver(List.of("10.0.0.0/8"));

    private static MockHttpServletRequest request(String peer, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return request;
    }

    // ------------------------------------------------------------------
    // Trust none (the default): the header is inert, the socket peer is the client
    // ------------------------------------------------------------------

    @Test
    void noXffHeader_fallsBackToRemoteAddr() {
        assertThat(TRUST_NONE.resolve(request("198.51.100.9", null))).isEqualTo("198.51.100.9");
    }

    @Test
    void xffSingleValue_fromAnUntrustedPeer_isIgnored() {
        assertThat(TRUST_NONE.resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    void xffMultipleHops_fromAnUntrustedPeer_isIgnored() {
        assertThat(TRUST_NONE.resolve(request("10.0.0.1", "203.0.113.7, 70.41.3.18, 150.172.238.178")))
                .isEqualTo("10.0.0.1");
    }

    @Test
    void xffWithWhitespace_fromAnUntrustedPeer_isIgnored() {
        assertThat(TRUST_NONE.resolve(request("10.0.0.1", " 203.0.113.7 , 70.41.3.18 "))).isEqualTo("10.0.0.1");
    }

    @Test
    void blankXff_fallsBackToRemoteAddr() {
        assertThat(TRUST_NONE.resolve(request("198.51.100.9", "   "))).isEqualTo("198.51.100.9");
    }

    @Test
    void emptyXff_fallsBackToRemoteAddr() {
        assertThat(TRUST_NONE.resolve(request("198.51.100.9", ""))).isEqualTo("198.51.100.9");
    }

    @Test
    void remoteAddrAlsoNull_returnsUnknownSentinel() {
        String resolved = TRUST_NONE.resolve(request(null, null));
        assertThat(resolved).isEqualTo("unknown");
        assertThat(resolved).isNotNull();
    }

    @Test
    void peerOutsideTheTrustedRange_isTheClient_evenWithAHeader() {
        // 10.0.0.0/8 is trusted; 203.0.113.9 is not, so its header is inert.
        assertThat(TRUST_10_8.resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    // ------------------------------------------------------------------
    // Behind a trusted proxy: the right-most-untrusted walk
    // ------------------------------------------------------------------

    @Nested
    class BehindATrustedProxy {

        @Test
        void rightmostUntrustedHopWins() {
            // The trusted peer appended 198.51.100.7 (the real client); 1.2.3.4 is whatever the
            // client wrote itself.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "1.2.3.4, 198.51.100.7")))
                    .isEqualTo("198.51.100.7");
        }

        @Test
        void trustedHopsAreSkipped_theFirstUntrustedFromTheRightWins() {
            // 10.9.9.9 is a second trusted proxy in the chain; walk past it to the client.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "1.2.3.4, 10.9.9.9"))).isEqualTo("1.2.3.4");
        }

        @Test
        void singleHopFromATrustedPeer_isTheClient() {
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
        }

        @Test
        void garbageToTheLeftOfTheClient_isNeverReached() {
            // The walk stops at the first untrusted VALID address; what lies further left is irrelevant.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "garbage, 198.51.100.7")))
                    .isEqualTo("198.51.100.7");
        }

        @Test
        void aMalformedHopStopsTheWalk_andTheSocketPeerIsTheAnswer() {
            // The right-most hop is not an address. Nothing to the left of it can be trusted, so the
            // answer is the only value the client could not have written: the socket peer. Never
            // the leftmost value, and never the malformed text itself.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "198.51.100.7, garbage"))).isEqualTo("10.1.2.3");
        }

        @Test
        void aMalformedHopAfterATrustedHop_stillYieldsTheSocketPeer_neverATrustedHopTheClientCouldSpell() {
            // Falling back to "the last trusted hop" would let a client behind the proxy choose its
            // bucket by writing in-range addresses (10.0.0.1, 10.0.0.2, ...). The peer is the only
            // non-forgeable fallback.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "1.2.3.4, garbage, 10.9.9.9"))).isEqualTo("10.1.2.3");
        }

        @Test
        void everyHopTrusted_yieldsTheSocketPeer() {
            // Same reasoning: an in-range leftmost value is attacker-spellable; the peer is not.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "10.5.5.5, 10.6.6.6"))).isEqualTo("10.1.2.3");
        }

        @Test
        void emptyHops_areSkippedLikeTrustedOnes() {
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "198.51.100.7, , "))).isEqualTo("198.51.100.7");
        }

        @Test
        void aHostnameHop_isMalformed_notResolved() {
            // A hop that is not an IP literal must never be handed to a DNS lookup: it stops the walk.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "198.51.100.7, evil.example"))).isEqualTo("10.1.2.3");
        }
    }

    // ------------------------------------------------------------------
    // IPv6 and IPv4-mapped IPv6
    // ------------------------------------------------------------------

    @Nested
    class Ipv6 {

        private final ClientIpResolver trustUla = new ClientIpResolver(List.of("fd00::/8"));

        @Test
        void ipv6TrustedPeer_rightmostUntrustedIpv6HopWins() {
            assertThat(trustUla.resolve(request("fd00::1", "2001:db8::5"))).isEqualTo("2001:db8::5");
        }

        @Test
        void ipv6PeerOutsideTheRange_isTheClient() {
            assertThat(trustUla.resolve(request("2001:db8::9", "2001:db8::5"))).isEqualTo("2001:db8::9");
        }

        @Test
        void ipv4MappedIpv6Peer_matchesAnIpv4TrustedRange() {
            // ::ffff:10.1.2.3 IS 10.1.2.3 on a dual-stack socket; the IPv4 CIDR must recognise it.
            assertThat(TRUST_10_8.resolve(request("::ffff:10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
        }

        @Test
        void ipv4MappedIpv6Hop_isNormalisedToDottedQuad() {
            // One client, one bucket: the mapped spelling and the dotted quad must key the same.
            assertThat(TRUST_10_8.resolve(request("10.1.2.3", "::ffff:198.51.100.7"))).isEqualTo("198.51.100.7");
        }

        @Test
        void ipv4MappedIpv6UntrustedPeer_isNormalisedToDottedQuad() {
            assertThat(TRUST_NONE.resolve(request("::ffff:203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
        }

        @Test
        void ipv4TrustedRange_doesNotTrustAnIpv6Peer() {
            assertThat(TRUST_10_8.resolve(request("fd00::1", "198.51.100.7"))).isEqualTo("fd00::1");
        }
    }

    // ------------------------------------------------------------------
    // Configuration refusals (defence in depth beside the k8s render invariant)
    // ------------------------------------------------------------------

    @Nested
    class Configuration {

        @Test
        void ipv4SlashZero_isRefusedAtConstruction_namingTheKey() {
            assertThatThrownBy(() -> new ClientIpResolver(List.of("0.0.0.0/0")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jtoye.security.trusted-proxies")
                    .hasMessageContaining("0.0.0.0/0");
        }

        @Test
        void ipv6SlashZero_isRefusedAtConstruction_namingTheKey() {
            assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/8", "::/0")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jtoye.security.trusted-proxies")
                    .hasMessageContaining("::/0");
        }

        @Test
        void aNonCidrEntry_isRefusedAtConstruction_namingTheKey() {
            assertThatThrownBy(() -> new ClientIpResolver(List.of("ingress.example/8")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("jtoye.security.trusted-proxies")
                    .hasMessageContaining("ingress.example/8");
        }

        @Test
        void aBareAddress_isAccepted_asAHostRange() {
            // "10.1.2.3" (no prefix) trusts exactly that host, the IpAddressMatcher convention.
            ClientIpResolver one = new ClientIpResolver(List.of("10.1.2.3"));
            assertThat(one.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
            assertThat(one.resolve(request("10.1.2.4", "198.51.100.7"))).isEqualTo("10.1.2.4");
        }

        @Test
        void blankEntriesAndWhitespace_areIgnored() {
            ClientIpResolver padded = new ClientIpResolver(List.of(" 10.0.0.0/8 ", "", "  "));
            assertThat(padded.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
        }
    }
}
