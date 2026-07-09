package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ClientIpResolver} (issue #88 [P1-6]).
 *
 * <p>Plain JUnit 5 + Spring's {@link MockHttpServletRequest} — no Spring context,
 * no Testcontainers. Covers the X-Forwarded-For-first resolution with a
 * {@code getRemoteAddr()} fallback and a non-null {@code "unknown"} sentinel so
 * the resolver never returns null (a null bucket key would NPE the public
 * rate-limit branch).
 */
class ClientIpResolverTest {

    @Test
    void noXffHeader_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void xffSingleValue_returnsThatValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void xffMultipleHops_returnsFirstHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void xffWithWhitespace_isTrimmed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 70.41.3.18 ");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void blankXff_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "   ");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void emptyXff_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        request.addHeader("X-Forwarded-For", "");

        assertThat(ClientIpResolver.resolveClientIp(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void remoteAddrAlsoNull_returnsUnknownSentinel() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(null);

        String resolved = ClientIpResolver.resolveClientIp(request);
        assertThat(resolved).isEqualTo("unknown");
        assertThat(resolved).isNotNull();
    }
}
