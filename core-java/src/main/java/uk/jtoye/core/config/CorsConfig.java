package uk.jtoye.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    /**
     * issue #412: response headers the browser is allowed to hand to JS.
     *
     * <p>A cross-origin response exposes ONLY the CORS-safelisted headers
     * (cache-control, content-language, content-length, content-type, expires,
     * last-modified, pragma) unless the server names the rest here. Every other
     * header is on the wire and invisible to script.
     *
     * <p>This list previously carried {@code Authorization, Content-Type} only, so
     * {@code RateLimitInterceptor}'s four throttling headers were silently stripped
     * from every browser client. Measured in a real browser 2026-08-01 against a
     * 429 from {@code /api/v1/public/shops}: {@code Retry-After} and all three
     * {@code X-RateLimit-*} read {@code null}, while {@code curl} on the same
     * response showed {@code Retry-After: 50}. Two client paths depended on them
     * and both degraded silently — {@code lib/public-fetch-retry.ts} always took
     * its exponential-backoff fallback, and the checkout could not quantify the
     * wait for a throttled shopper.
     *
     * <p>{@code curl} cannot answer this question: it shows what was SENT, not what
     * the browser exposes. Verify in a browser.
     */
    @Value("${cors.exposed-headers:Authorization,Content-Type,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset}")
    private List<String> exposedHeaders;

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);
        config.setAllowedOrigins(allowedOrigins);

        config.addAllowedHeader("*");
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        config.setExposedHeaders(exposedHeaders);

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
