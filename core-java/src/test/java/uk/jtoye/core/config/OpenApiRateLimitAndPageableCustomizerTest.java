package uk.jtoye.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #879 / UXT-123: the two customizers that make the published contract describe paging and throttling.
 * {@code OpenApiContractTest} holds the whole snapshot to the result; these pin the mechanism, with
 * their own controls, so a regression names the rule that broke.
 */
class OpenApiRateLimitAndPageableCustomizerTest {

    @SuppressWarnings("unused")
    static class Handlers {
        public void withDefaults(
                @PageableDefault(size = 50, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) { }

        public void bare(Pageable pageable) { }

        public void notPaged(String q) { }
    }

    private static HandlerMethod handler(String name) throws Exception {
        Method m = null;
        for (Method c : Handlers.class.getDeclaredMethods()) {
            if (c.getName().equals(name)) {
                m = c;
            }
        }
        return new HandlerMethod(new Handlers(), m);
    }

    /** What springdoc emits for an un-hidden Pageable argument: one required object-typed query parameter. */
    private static Operation springdocOperation() {
        Operation op = new Operation();
        op.addParametersItem(new Parameter().in("query").name("q").schema(new StringSchema()));
        op.addParametersItem(new Parameter().in("query").name("pageable").required(true).schema(new StringSchema()));
        return op;
    }

    private static PageableParametersCustomizer pageable(int max) {
        PageableParametersCustomizer c = new PageableParametersCustomizer();
        ReflectionTestUtils.setField(c, "maxPageSize", max);
        return c;
    }

    @Test
    void aPageableArgumentBecomesPageSizeAndSortWithTheDeclaredDefaults() throws Exception {
        Operation op = pageable(100).customize(springdocOperation(), handler("withDefaults"));

        assertThat(op.getParameters()).extracting(Parameter::getName).containsExactly("q", "page", "size", "sort");
        Parameter size = op.getParameters().get(2);
        assertThat(size.getSchema().getDefault()).isEqualTo(50);
        assertThat(size.getSchema().getMaximum()).isEqualByComparingTo("100");
        assertThat(op.getParameters().get(3).getSchema().getDefault()).isEqualTo(List.of("name,asc"));
        assertThat(op.getParameters()).extracting(Parameter::getName).doesNotContain("pageable");
    }

    @Test
    void anUnannotatedPageableFallsBackToSpringDatasTwentyAndTheConfiguredMaximum() throws Exception {
        Operation op = pageable(40).customize(springdocOperation(), handler("bare"));

        Parameter size = op.getParameters().stream().filter(p -> p.getName().equals("size")).findFirst().orElseThrow();
        assertThat(size.getSchema().getDefault()).isEqualTo(20); // the configured default-page-size (field default 20)
        assertThat(size.getSchema().getMaximum()).isEqualByComparingTo("40");
    }

    @Test
    void anUnannotatedPageablePublishesTheCONFIGUREDDefaultPageSize() throws Exception {
        PageableParametersCustomizer c = pageable(100);
        ReflectionTestUtils.setField(c, "defaultPageSize", 35);
        Operation op = c.customize(springdocOperation(), handler("bare"));

        Parameter size = op.getParameters().stream().filter(p -> p.getName().equals("size")).findFirst().orElseThrow();
        assertThat(size.getSchema().getDefault()).isEqualTo(35);
    }

    @Test
    void anOperationWithoutAPageableIsLeftAlone() throws Exception {
        Operation before = springdocOperation();
        Operation op = pageable(100).customize(before, handler("notPaged"));

        assertThat(op.getParameters()).extracting(Parameter::getName).containsExactly("q", "pageable"); // control: untouched
    }

    @Test
    void anExplicitPageParameterIsNotDuplicated() throws Exception {
        Operation op = springdocOperation();
        op.addParametersItem(new Parameter().in("query").name("page").description("hand-written"));
        pageable(100).customize(op, handler("bare"));

        assertThat(op.getParameters().stream().filter(p -> p.getName().equals("page"))).hasSize(1);
        assertThat(op.getParameters().stream().filter(p -> p.getName().equals("page")).findFirst().orElseThrow()
                .getDescription()).isEqualTo("hand-written");
    }

    private static OpenAPI specWith(String path, ApiResponses responses) {
        Operation op = new Operation().responses(responses);
        return new OpenAPI().paths(new Paths().addPathItem(path, new PathItem().get(op)));
    }

    @Test
    void everyGuardedOperationGainsA429WithRetryAfterAndAProblemBody() {
        OpenAPI api = specWith("/api/v1/shops", new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));
        new RateLimitResponseCustomizer().customise(api);

        ApiResponse r = api.getPaths().get("/api/v1/shops").getGet().getResponses().get("429");
        assertThat(r).isNotNull();
        assertThat(r.getHeaders()).containsKeys("Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");
        assertThat(r.getContent()).containsKey("application/problem+json");
    }

    @Test
    void everyPathTheInterceptorIsRegisteredToSkipIsNotToldItCanBeThrottled() {
        // The registration list (WebConfig) and the spec share RateLimitInterceptor.EXCLUDED_PATH_PATTERNS, so
        // a path that only the registration used to exclude (/swagger-ui.html) no longer slips through (#1127).
        for (String path : new String[] {"/health", "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs",
                "/v3/api-docs/x", "/v3/api-docs.yaml", "/actuator/health"}) {
            OpenAPI api = specWith(path, new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));
            new RateLimitResponseCustomizer().customise(api);
            assertThat(api.getPaths().get(path).getGet().getResponses()).as(path).doesNotContainKey("429");
        }
        // CONTROL: a path that is not excluded still gains it.
        OpenAPI api = specWith("/api/v1/shops", new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));
        new RateLimitResponseCustomizer().customise(api);
        assertThat(api.getPaths().get("/api/v1/shops").getGet().getResponses()).containsKey("429");
    }

    @Test
    void theGenericDescriptionClaimsRateLimitedOnlyAndAdmitsTheNoTenantPassThrough() {
        assertThat(RateLimitResponseCustomizer.DESCRIPTION)
                .contains("errors/rate-limited only")
                .contains("not throttled");
    }

    @Test
    void theExcludedPathsAreNotToldTheyCanBeThrottled() {
        OpenAPI api = specWith("/health", new ApiResponses().addApiResponse("200", new ApiResponse().description("ok")));
        new RateLimitResponseCustomizer().customise(api);

        assertThat(api.getPaths().get("/health").getGet().getResponses()).doesNotContainKey("429");
    }

    @Test
    void anOperationsOwn429DescriptionIsKeptAndGainsTheHeaders() {
        OpenAPI api = specWith("/api/v1/public/gdpr/dsar",
                new ApiResponses().addApiResponse("429", new ApiResponse().description("Too many requests from this client")));
        new RateLimitResponseCustomizer().customise(api);

        ApiResponse r = api.getPaths().get("/api/v1/public/gdpr/dsar").getGet().getResponses().get("429");
        assertThat(r.getDescription()).startsWith("Too many requests from this client");
        assertThat(r.getHeaders()).containsKey("Retry-After");
        // running twice does not stack the sentence
        new RateLimitResponseCustomizer().customise(api);
        assertThat(r.getDescription().split("The rate limiter also answers", -1)).hasSize(2);
    }
}
