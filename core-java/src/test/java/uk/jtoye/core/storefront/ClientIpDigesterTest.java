package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 37-28 (D-27, RWO-041): the per-IP cash-order cap keys on a KEYED digest of the client
 * address, never the address and never a plain hash — a plain SHA-256 of the IPv4 space (2^32
 * values) is reversed by enumeration in minutes, so without the key the column would be the address.
 *
 * <p>The contract pinned here: the same key and the same client give the same 64-character
 * lower-case hex digest (so the count is a query); a different key gives a different digest for the
 * same client (so the column is meaningless without the key); two spellings of one client
 * ({@code ::ffff:1.2.3.4} and {@code 1.2.3.4}; two IPv6 spellings) are one digest (so a client cannot
 * dodge the cap by respelling what the proxy wrote); and the key is validated at construction, the
 * {@code DsarCipher} precedent, with a refusal that names the property and never the value.
 */
class ClientIpDigesterTest {

    /** 64 hex characters, as `openssl rand -hex 32` produces; a FIXTURE, not a credential. */
    private static final String KEY_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String KEY_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    @DisplayName("same key + same IP -> the same 64-character lower-case hex digest, equal to HMAC-SHA256(key, canonical IP text)")
    void sameKeyAndIp_giveTheSameHexDigest_whichIsHmacSha256OverTheCanonicalText() {
        ClientIpDigester digester = new ClientIpDigester(KEY_A);

        String first = digester.digest("198.51.100.7");
        String second = digester.digest("198.51.100.7");

        assertThat(first).isNotNull().hasSize(64).matches("[0-9a-f]{64}");
        assertThat(second).isEqualTo(first);
        assertThat(first).as("the construction is HMAC-SHA256 under the key, over the canonical dotted quad")
                .isEqualTo(hmacSha256Hex(KEY_A, "198.51.100.7"));
    }

    @Test
    @DisplayName("a different key -> a different digest for the same IP (the column is meaningless without the key)")
    void differentKey_givesADifferentDigestForTheSameIp() {
        String underA = new ClientIpDigester(KEY_A).digest("198.51.100.7");
        String underB = new ClientIpDigester(KEY_B).digest("198.51.100.7");

        assertThat(underA).isNotNull();
        assertThat(underB).isNotNull().isNotEqualTo(underA);
    }

    @Test
    @DisplayName("different IPs -> different digests (the cap counts one client, not everyone)")
    void differentIps_giveDifferentDigests() {
        ClientIpDigester digester = new ClientIpDigester(KEY_A);
        assertThat(digester.digest("198.51.100.7")).isNotNull().isNotEqualTo(digester.digest("198.51.100.8"));
    }

    @Test
    @DisplayName("::ffff:198.51.100.7 and 198.51.100.7 are one client -> one digest")
    void ipv4MappedIpv6_digestsEqualToTheDottedQuad() {
        ClientIpDigester digester = new ClientIpDigester(KEY_A);
        assertThat(digester.digest("::ffff:198.51.100.7")).isNotNull().isEqualTo(digester.digest("198.51.100.7"));
    }

    @Test
    @DisplayName("two spellings of one IPv6 address (2001:db8::1 and 2001:0DB8:0000:0000:0000:0000:0000:0001) -> one digest")
    void ipv6Spellings_digestEqual() {
        ClientIpDigester digester = new ClientIpDigester(KEY_A);
        assertThat(digester.digest("2001:db8::1")).isNotNull()
                .isEqualTo(digester.digest("2001:0DB8:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    @DisplayName("null or blank -> null (nothing to digest), never a digest of the empty string")
    void nullOrBlank_digestToNull() {
        ClientIpDigester digester = new ClientIpDigester(KEY_A);
        assertThat(digester.digest(null)).isNull();
        assertThat(digester.digest("   ")).isNull();
    }

    @ParameterizedTest(name = "key {0} is refused at construction")
    @ValueSource(strings = {
            "",                                                                     // absent (the empty inline default)
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",      // 63 characters
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",    // 65 characters
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",     // not hex
            "CHANGE_ME"
    })
    @DisplayName("an absent, short, long or non-hex key refuses to construct, naming the property and the generation hint, never the value")
    void invalidKey_isRefusedAtConstruction_namingThePropertyNotTheValue(String badKey) {
        assertThatThrownBy(() -> new ClientIpDigester(badKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ClientIpDigester.PROPERTY)
                .hasMessageContaining("64 hexadecimal characters")
                .hasMessageContaining("openssl rand -hex 32")
                .satisfies(e -> {
                    if (!badKey.isEmpty()) {
                        assertThat(e.getMessage()).as("a partially-correct key in a log is a partially-leaked key")
                                .doesNotContain(badKey);
                    }
                });
    }

    @Test
    @DisplayName("a null key (no property source at all) is refused the same way")
    void nullKey_isRefused() {
        assertThatThrownBy(() -> new ClientIpDigester(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ClientIpDigester.PROPERTY);
    }

    /** The independent reference: javax.crypto directly, so the test does not trust the class under test. */
    static String hmacSha256Hex(String hexKey, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(hexKey), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
