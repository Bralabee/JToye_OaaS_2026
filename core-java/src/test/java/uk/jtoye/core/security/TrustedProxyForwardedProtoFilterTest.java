package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-37-59: with the RemoteIpValve gone, {@link TrustedProxyForwardedProtoFilter} gives back the
 * {@code https} scheme behind a TLS-terminating ingress — the {@code Location} header and the HSTS
 * writer read it — and believes {@code X-Forwarded-Proto} only from a peer in
 * {@code jtoye.security.trusted-proxies}. Every "believed" arm has an "ignored" arm beside it.
 */
class TrustedProxyForwardedProtoFilterTest {

    private static final String INGRESS_POD = "10.244.0.17";
    private static final String CLIENT = "203.0.113.9";

    private final TrustedProxyForwardedProtoFilter filter =
            new TrustedProxyForwardedProtoFilter(new ClientIpResolver(List.of("10.244.0.0/16")));

    @Test
    void trustedIngress_https_restoresSchemeSecurePortUrlAndLocation() throws Exception {
        HttpServletRequest seen = run(request(INGRESS_POD, "https"));

        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getServerPort()).isEqualTo(443);
        assertThat(seen.getRequestURL().toString()).isEqualTo("https://api.olajay.co.uk/api/v1/shops");
        assertThat(ServletUriComponentsBuilder.fromRequest(seen).path("/{id}").buildAndExpand("42").toUriString())
                .as("the create endpoints' Location header, as ShopController builds it")
                .isEqualTo("https://api.olajay.co.uk/api/v1/shops/42");
        assertThat(seen.getServerName()).as("the host is never taken from a header").isEqualTo("api.olajay.co.uk");
    }

    @Test
    void untrustedPeer_https_isIgnored() throws Exception {
        HttpServletRequest seen = run(request(CLIENT, "https"));

        assertThat(seen.getScheme()).isEqualTo("http");
        assertThat(seen.isSecure()).isFalse();
        assertThat(seen.getServerPort()).isEqualTo(80);
        assertThat(ServletUriComponentsBuilder.fromRequest(seen).path("/{id}").buildAndExpand("42").toUriString())
                .isEqualTo("http://api.olajay.co.uk/api/v1/shops/42");
    }

    @Test
    void trustedIngress_withAnUnusableValue_isIgnored() throws Exception {
        for (String value : new String[] {"https, http", "ftp", "", "HTTPS-ish"}) {
            assertThat(run(request(INGRESS_POD, value)).getScheme()).as("value '%s'", value).isEqualTo("http");
        }
        MockHttpServletRequest repeated = request(INGRESS_POD, "https");
        repeated.addHeader(TrustedProxyForwardedProtoFilter.PROTO_HEADER, "https");
        assertThat(run(repeated).getScheme()).as("a repeated header is ambiguous, so ignored").isEqualTo("http");
        assertThat(run(request(INGRESS_POD, null)).getScheme()).as("no header").isEqualTo("http");
    }

    @Test
    void trustedIngress_upperCaseHttps_isBelieved_andAnExplicitHostPortIsKept() throws Exception {
        MockHttpServletRequest request = request(INGRESS_POD, "HTTPS");
        request.setServerPort(8443);
        HttpServletRequest seen = run(request);

        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.getServerPort()).isEqualTo(8443);
        assertThat(seen.getRequestURL().toString()).isEqualTo("https://api.olajay.co.uk:8443/api/v1/shops");
    }

    @Test
    void defaultResolver_trustsNoOne_soComposeIsUntouched() throws Exception {
        TrustedProxyForwardedProtoFilter compose = new TrustedProxyForwardedProtoFilter(new ClientIpResolver(List.of()));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = request("127.0.0.1", "https");
        compose.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the very same request object passes through").isSameAs(request);
    }

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest request(String peer, String proto) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.setScheme("http");
        request.setServerName("api.olajay.co.uk");
        request.setServerPort(80);
        request.setRemoteAddr(peer);
        if (proto != null) {
            request.addHeader(TrustedProxyForwardedProtoFilter.PROTO_HEADER, proto);
        }
        return request;
    }
}
