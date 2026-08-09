package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import uk.jtoye.core.storefront.SearchInterpretation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

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
     *
     * <p>33-08 (#619) appends the search-interpretation header — the last name in
     * the default below, and declared canonically as
     * {@code SearchInterpretation.HEADER}. Same hazard, same reason it is listed
     * rather than assumed: the storefront talks to core cross-origin (:3000 to
     * :9090), so unlisted it is on the wire and {@code null} to every {@code fetch}.
     * It is an ADDITION — none of the six names above may be displaced, which
     * {@code preExistingExposuresRetained} and
     * {@code shippedDefaultNamesAllFourHeaders} both guard.
     */
    @Value("${cors.exposed-headers:Authorization,Content-Type,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset,X-Search-Interpretation}")
    private List<String> exposedHeaders;

    /**
     * Names an operator override may EXTEND but never REMOVE (WR-03's sibling, WR-04).
     *
     * <p><strong>Why a floor exists at all.</strong> {@code setExposedHeaders} REPLACES the list;
     * it does not merge. {@code CORS_EXPOSED_HEADERS} is advertised as operator-tunable and is
     * asserted to be so, so any deployment that sets it — including one that innocently copies the
     * pre-33-08 six-name list out of an older runbook — silently deleted
     * {@code X-Search-Interpretation}. Nothing reports that: the header is genuinely on the wire,
     * {@code curl} shows it, and every servlet-level test stays green. Only a browser can see the
     * omission, and what it produces is not a degraded hint but a FALSE STATEMENT ON THE PAGE —
     * {@code headers.get()} returns {@code null}, the parser degrades to {@code text}, and
     * {@code /shop?q=SE22} renders "3 kitchens for SE22" over a distance-ordered, radius-filtered
     * result set with the exclusion disclosure suppressed.
     *
     * <p>The four rate-limit names are here for #412's own measured reason: their omission put
     * {@code lib/public-fetch-retry.ts} permanently on its exponential-backoff fallback and left
     * the checkout unable to quantify a throttled shopper's wait, invisibly, for months.
     *
     * <p>{@code Authorization} and {@code Content-Type} are deliberately NOT in the floor. They
     * are exposures an operator may legitimately want to narrow, and neither causes the client to
     * assert something untrue when absent.
     */
    static final List<String> MANDATORY_EXPOSED_HEADERS = List.of(
            "Retry-After",
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset",
            SearchInterpretation.HEADER);

    /**
     * The configured list, plus any mandatory name it omits — extend-but-never-remove.
     *
     * <p>Order is preserved and the operator's own names come first, so a deployment reading its
     * own config back still sees what it wrote. Comparison is case-insensitive because HTTP header
     * names are, and a floor defeated by {@code retry-after} vs {@code Retry-After} would be a
     * floor in name only.
     *
     * <p>Package-private and static so both directions can be driven directly in a test.
     */
    static List<String> withMandatoryExposures(List<String> configured) {
        List<String> merged = new ArrayList<>();
        if (configured != null) {
            for (String name : configured) {
                if (name != null && !name.isBlank()) {
                    merged.add(name.trim());
                }
            }
        }
        List<String> appended = new ArrayList<>();
        for (String mandatory : MANDATORY_EXPOSED_HEADERS) {
            if (merged.stream().noneMatch(mandatory::equalsIgnoreCase)) {
                merged.add(mandatory);
                appended.add(mandatory);
            }
        }
        if (!appended.isEmpty()) {
            // An operator-VISIBLE record of an otherwise invisible omission. Without this the
            // append would be silent magic, and the config an operator wrote would differ from
            // the config that runs with no trace of why.
            log.warn("cors.exposed-headers omits {} — appending. A browser cannot read these "
                    + "without being told to, and their absence is invisible server-side: curl "
                    + "still shows them on the wire while the storefront reports a proximity "
                    + "result as a plain text search and cannot quantify a rate-limit wait.",
                    appended);
        }
        return List.copyOf(merged);
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(true);
        config.setAllowedOrigins(allowedOrigins);

        config.addAllowedHeader("*");
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

        // NOT `setExposedHeaders(exposedHeaders)` — that is a full replacement, and the names in
        // MANDATORY_EXPOSED_HEADERS are ones a client asserts something FALSE without.
        config.setExposedHeaders(withMandatoryExposures(exposedHeaders));

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
