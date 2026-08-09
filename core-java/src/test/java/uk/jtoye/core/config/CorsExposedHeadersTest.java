package uk.jtoye.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #412 — the four rate-limit headers must reach browser JS.
 *
 * <p><b>Why this test drives the real {@link CorsFilter} rather than inspecting the
 * config object.</b> Asserting that a list contains six strings proves the list; it
 * does not prove Spring emits them. The defect this guards against was exactly that
 * shape — {@code RateLimitInterceptor} genuinely set {@code Retry-After} on every 429,
 * {@code curl} genuinely showed it, and no browser could read it. So the assertions
 * below run a request through the filter and read the response header that a browser
 * actually consults.
 *
 * <p><b>This test still cannot see the browser.</b> It proves the header is emitted; it
 * cannot prove the browser then exposes the value to script. That half is proved by
 * driving a real Chromium at the running stack — see the probe recorded on the PR.
 * Both directions were measured against the live stack on 2026-08-01: before the fix
 * {@code response.headers.get('Retry-After')} was {@code null} in-browser while curl
 * showed {@code Retry-After: 50} on the same response.
 */
class CorsExposedHeadersTest {

    private static final String EXPOSE = "Access-Control-Expose-Headers";
    private static final String ORIGIN = "http://localhost:3000";

    /** The four headers RateLimitInterceptor sets and clients depend on. */
    private static final List<String> RATE_LIMIT_HEADERS = List.of(
            "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");

    private CorsFilter filter;

    @BeforeEach
    void setUp() {
        filter = buildFilter(
                "Authorization,Content-Type,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,X-RateLimit-Reset");
    }

    private CorsFilter buildFilter(String exposedHeadersCsv) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", List.of(ORIGIN));
        ReflectionTestUtils.setField(config, "exposedHeaders", Arrays.asList(exposedHeadersCsv.split(",")));
        return config.corsFilter();
    }

    /** Runs a cross-origin GET through the filter and returns the response. */
    private MockHttpServletResponse actualRequest(CorsFilter f) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/public/shops");
        request.addHeader("Origin", ORIGIN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /** Runs a CORS preflight through the filter and returns the response. */
    private MockHttpServletResponse preflightRequest(CorsFilter f) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/public/shops");
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /** Lowercased view of a name list, so casing drift cannot pass an assertion. */
    private static List<String> lowercased(List<String> names) {
        return names.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    /** Header names are case-insensitive; compare lowercased so casing drift cannot pass. */
    private static List<String> exposedNames(MockHttpServletResponse response) {
        String header = response.getHeader(EXPOSE);
        if (header == null) return List.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Test
    @DisplayName("#412: an actual cross-origin response exposes all four rate-limit headers")
    void actualResponseExposesRateLimitHeaders() throws Exception {
        List<String> exposed = exposedNames(actualRequest(filter));

        assertThat(exposed)
                .as("the header a browser consults before handing Retry-After to script")
                .containsAll(RATE_LIMIT_HEADERS.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }

    @Test
    @DisplayName("#412: the preflight response carries the same allowlist")
    void preflightExposesRateLimitHeaders() throws Exception {
        List<String> exposed = exposedNames(preflightRequest(filter));

        assertThat(exposed)
                .containsAll(RATE_LIMIT_HEADERS.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }

    @Test
    @DisplayName("#412: the pre-existing Authorization/Content-Type exposures are not displaced")
    void preExistingExposuresRetained() throws Exception {
        // Incremental betterment: this list REPLACED two addExposedHeader calls.
        // Dropping either would be a regression by omission that no other test sees.
        assertThat(exposedNames(actualRequest(filter))).contains("authorization", "content-type");
    }

    @Test
    @DisplayName("#412 fail-direction: the pre-fix allowlist omits all four, and the floor is what now supplies them")
    void preFixAllowlistFailsTheAssertion() throws Exception {
        // The exact configuration that shipped before this change. If the assertions
        // above cannot fail, they are not evidence — so exercise the broken input and
        // confirm it genuinely omits all four names.
        //
        // WR-04 MOVED WHERE THIS IS ASSERTED, AND NOTHING ELSE. This arm used to read the
        // omission off the FILTER, which is no longer possible or desirable: the filter now
        // applies MANDATORY_EXPOSED_HEADERS, so a deployment carrying the pre-fix list can no
        // longer reproduce the #412 outage at all. The broken input is unchanged and its
        // brokenness is still asserted — on the input, where it is a permanent fact — and the
        // arm gains the second half it could not have before: proof that the floor is what
        // repairs it. Strictly more than it asserted, not less.
        List<String> preFixConfigured = Arrays.asList("Authorization", "Content-Type");

        assertThat(preFixConfigured.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList())
                .as("the pre-fix CONFIGURED list — the broken input #412 was measured against")
                .containsExactlyInAnyOrder("authorization", "content-type")
                .doesNotContainAnyElementsOf(lowercased(RATE_LIMIT_HEADERS));

        List<String> exposed = exposedNames(actualRequest(buildFilter("Authorization,Content-Type")));

        assertThat(exposed)
                .as("that same broken deployment can no longer strip the four names")
                .containsAll(lowercased(RATE_LIMIT_HEADERS));
        assertThat(exposed)
                .as("and its own two names are still there — the floor extends, it does not replace")
                .contains("authorization", "content-type");
    }

    @Test
    @DisplayName("#412: the SHIPPED default in application.yml names all four")
    void shippedDefaultNamesAllFourHeaders() throws Exception {
        // Every other test here injects the list, so none of them can see a regression
        // in the value a deployment actually gets. application.yml defines the key, so
        // its default — not the @Value fallback — is what wins at runtime.
        Map<String, Object> yaml;
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            // application.yml is a multi-document file; the cors block is in the first.
            @SuppressWarnings("unchecked")
            Map<String, Object> firstDocument = (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
            yaml = firstDocument;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cors = (Map<String, Object>) yaml.get("cors");
        assertThat(cors).as("cors block present in application.yml").isNotNull();

        String expression = String.valueOf(cors.get("exposed-headers"));
        assertThat(expression)
                .as("must stay env-overridable, not a bare literal")
                .startsWith("${CORS_EXPOSED_HEADERS:");

        for (String header : RATE_LIMIT_HEADERS) {
            assertThat(expression)
                    .as("shipped default must name %s", header)
                    .contains(header);
        }
        assertThat(expression).contains("Authorization").contains("Content-Type");
    }

    // --- 33-08 / #619 -----------------------------------------------------------------
    //
    // W-5: these live in their OWN methods and deliberately do NOT extend
    // shippedDefaultNamesAllFourHeaders. That method is named and documented for #412's four
    // rate-limit headers; widening it would blur what it guards and make a future failure
    // ambiguous about which regression fired.

    @Test
    @DisplayName("33-08: the SHIPPED default also names X-Search-Interpretation")
    void shippedDefaultAlsoNamesSearchInterpretationHeader() throws Exception {
        // Same source of truth as #412's guard — application.yml, not the @Value fallback,
        // because the yml defines the key and therefore wins at runtime.
        Map<String, Object> yaml;
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstDocument = (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
            yaml = firstDocument;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> cors = (Map<String, Object>) yaml.get("cors");
        assertThat(cors).as("cors block present in application.yml").isNotNull();

        String expression = String.valueOf(cors.get("exposed-headers"));
        assertThat(expression)
                .as("must stay env-overridable, not a bare literal")
                .startsWith("${CORS_EXPOSED_HEADERS:");
        assertThat(expression)
                .as("shipped default must name the search-interpretation header")
                .contains("X-Search-Interpretation");
    }

    @Test
    @DisplayName("33-08: an actual cross-origin response exposes X-Search-Interpretation to script")
    void actualResponseExposesSearchInterpretationHeader() throws Exception {
        // The yml assertion above proves the CONFIGURED name. This proves the filter emits it —
        // the same distinction #412 turned on, where the header was genuinely set and genuinely
        // invisible to every browser. Whether a real browser then hands it to script is 33-09's
        // CA-H; neither this test nor curl can answer that.
        CorsFilter shipped = buildFilter(
                "Authorization,Content-Type,Retry-After,X-RateLimit-Limit,X-RateLimit-Remaining,"
                        + "X-RateLimit-Reset,X-Search-Interpretation");

        assertThat(exposedNames(actualRequest(shipped))).contains("x-search-interpretation");
    }

    @Test
    @DisplayName("33-08 fail-direction: the pre-33-08 allowlist omits the name, and the floor is what now supplies it")
    void pre3308AllowlistOmitsSearchInterpretationHeader() throws Exception {
        // The exact list that shipped before this change. Without this arm the assertion above
        // could not be shown to fail, and #412's whole lesson is that this class of header is
        // invisible until something looks for its absence.
        //
        // WR-04, same move as preFixAllowlistFailsTheAssertion: the omission is asserted on the
        // CONFIGURED list, which is where it is a permanent fact, and the arm then proves the
        // floor repairs it at the filter. Reading it off the filter is no longer possible, and
        // that impossibility IS the fix.
        List<String> pre3308Configured = Arrays.asList(
                "Authorization", "Content-Type", "Retry-After",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");

        assertThat(lowercased(pre3308Configured))
                .as("the #412-era CONFIGURED list must NOT already contain the new name, or this "
                        + "arm proves nothing")
                .doesNotContain("x-search-interpretation");

        assertThat(exposedNames(actualRequest(filter)))
                .as("and a deployment still carrying that list is nonetheless told to expose it")
                .contains("x-search-interpretation");
    }

    @Test
    @DisplayName("#412: the allowlist is config-injected, not hardcoded")
    void allowlistIsConfigurable() throws Exception {
        // GLOBAL_RULE_6 / ARCHITECTURE_RULE_8: a deployment must be able to change this
        // without a rebuild. Proven by driving a value no default contains.
        //
        // WR-04 widened the expected set by exactly MANDATORY_EXPOSED_HEADERS and kept the
        // assertion EXACT. `contains("x-trace-id")` would have been the easy edit and a weaker
        // one — it would no longer notice a stray name creeping into the emitted list.
        CorsFilter custom = buildFilter("X-Trace-Id");

        List<String> expected = new ArrayList<>(List.of("x-trace-id"));
        expected.addAll(lowercased(CorsConfig.MANDATORY_EXPOSED_HEADERS));

        assertThat(exposedNames(actualRequest(custom)))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    // --- WR-04 -----------------------------------------------------------------------------
    //
    // setExposedHeaders REPLACES the list rather than merging it, so before this change any
    // deployment that set CORS_EXPOSED_HEADERS silently deleted whatever it did not repeat —
    // including X-Search-Interpretation, whose absence makes the storefront state the WRONG
    // reading of q with no server-side symptom at all. These arms are about the FLOOR: what an
    // override may extend, and what it may not remove.

    @Test
    @DisplayName("WR-04: an override that omits X-Search-Interpretation still exposes it")
    void anOverrideOmittingTheInterpretationHeaderStillExposesIt() throws Exception {
        // The realistic accident: an operator copies the pre-33-08 six-name list out of an older
        // runbook. Every servlet test stays green, curl shows the header on the wire, and
        // /shop?q=SE22 renders "3 kitchens for SE22" over a distance-ordered, radius-filtered
        // result set. Only a browser can see it, so only a floor can prevent it.
        CorsFilter override = buildFilter(
                "Authorization,Content-Type,Retry-After,X-RateLimit-Limit,"
                        + "X-RateLimit-Remaining,X-RateLimit-Reset");

        assertThat(exposedNames(actualRequest(override))).contains("x-search-interpretation");
    }

    @Test
    @DisplayName("WR-04: no override can remove ANY mandatory name — including an empty one")
    void noOverrideCanRemoveAMandatoryName() throws Exception {
        for (String override : List.of("X-Trace-Id", "Authorization", "Content-Type")) {
            assertThat(exposedNames(actualRequest(buildFilter(override))))
                    .as("override %s", override)
                    .containsAll(lowercased(CorsConfig.MANDATORY_EXPOSED_HEADERS));
        }
        // The degenerate cases the CSV binding can actually produce.
        assertThat(CorsConfig.withMandatoryExposures(List.of()))
                .containsExactlyElementsOf(CorsConfig.MANDATORY_EXPOSED_HEADERS);
        assertThat(CorsConfig.withMandatoryExposures(null))
                .containsExactlyElementsOf(CorsConfig.MANDATORY_EXPOSED_HEADERS);
    }

    @Test
    @DisplayName("WR-04: the floor EXTENDS the operator's list, it does not replace it")
    void theFloorDoesNotDisplaceTheOperatorsOwnNames() throws Exception {
        // Incremental betterment: a floor that silently discarded the operator's own names would
        // trade one invisible removal for another.
        assertThat(exposedNames(actualRequest(buildFilter("X-Trace-Id,X-Correlation-Id"))))
                .contains("x-trace-id", "x-correlation-id");
    }

    @Test
    @DisplayName("WR-04: the floor is case-insensitive, so it cannot be defeated OR duplicated by casing")
    void theFloorIsCaseInsensitive() {
        // HTTP header names are case-insensitive. A floor that compared with equals() would
        // append a second copy of a name the operator already listed as `retry-after` — which is
        // not a security problem but is a config an operator cannot reconcile with what they set.
        List<String> merged = CorsConfig.withMandatoryExposures(
                Arrays.asList("retry-after", "x-search-interpretation"));

        assertThat(merged).as("no duplicate under a different casing")
                .containsExactly("retry-after", "x-search-interpretation",
                        "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");
    }

    @Test
    @DisplayName("WR-04 CONTROL: the SHIPPED default needs no appending — the floor is not doing today's work")
    void theShippedDefaultAlreadySatisfiesTheFloor() throws Exception {
        // Non-vacuity in the other direction. Every arm above proves the floor ADDS something;
        // this one proves it adds NOTHING to the list that actually ships, so a regression in
        // application.yml cannot hide behind the floor and read as "still fine".
        //
        // Read out of application.yml rather than copied, for the same reason
        // shippedDefaultNamesAllFourHeaders does it: a literal copy here would keep passing
        // after the yml lost a name, which is precisely the regression it exists to catch.
        List<String> shipped = Arrays.asList(shippedExposedHeadersDefault().split(","));

        assertThat(shipped)
                .as("sanity: the yml default parsed into individual names, not one blob")
                .hasSizeGreaterThan(1);
        assertThat(CorsConfig.withMandatoryExposures(shipped))
                .as("the shipped default is already complete; the floor changes nothing")
                .containsExactlyElementsOf(shipped);
    }

    /** The CSV inside {@code ${CORS_EXPOSED_HEADERS:...}} as application.yml actually ships it. */
    private static String shippedExposedHeadersDefault() throws Exception {
        Map<String, Object> yaml;
        try (InputStream in = new ClassPathResource("application.yml").getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> firstDocument = (Map<String, Object>) new Yaml().loadAll(in).iterator().next();
            yaml = firstDocument;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cors = (Map<String, Object>) yaml.get("cors");
        String expression = String.valueOf(cors.get("exposed-headers"));
        assertThat(expression).startsWith("${CORS_EXPOSED_HEADERS:").endsWith("}");
        return expression.substring("${CORS_EXPOSED_HEADERS:".length(), expression.length() - 1);
    }
}
