package uk.jtoye.core.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.PropertyPath;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA-council L1 + L2 regression guards for {@link GlobalExceptionHandler}
 * request-shape mappings. Standalone MockMvc, no Spring context / Testcontainers.
 *
 * <ul>
 *   <li><b>L1</b> — {@link NoResourceFoundException} (raised for an unmapped/
 *       unversioned path) must map to 404, not the catch-all 500.</li>
 *   <li><b>L2</b> — a missing required {@code @RequestHeader} (e.g. the absent
 *       {@code Stripe-Signature} on the payments webhook) must map to 400, not 500.</li>
 *   <li><b>API-7</b> (QA council 20260902-134741) — a malformed {@code ?sort=} value makes
 *       Spring Data raise {@link org.springframework.data.mapping.PropertyReferenceException};
 *       client-supplied input must map to 400, not the catch-all 500.</li>
 * </ul>
 *
 * Before these handlers existed both fell through to {@code handleGenericException}
 * → HTTP 500 with a full stacktrace logged at ERROR per request.
 */
class GlobalExceptionHandlerRequestShapeTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // API-7: a BARE `new ObjectMapper()` does not register ProblemDetailJacksonMixin, so a
        // ProblemDetail's extra members serialise NESTED under "properties" — a shape production
        // never emits. Jackson2ObjectMapperBuilder is what Spring Boot builds its auto-configured
        // mapper with, so asserting `$.property` here asserts what a real client receives
        // (same reasoning as RateLimitInterceptorTest, issue #413).
        MappingJackson2HttpMessageConverter jackson =
                new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jackson)
                .build();
    }

    @Test
    void noResourceFoundReturns404() throws Exception {
        mockMvc.perform(get("/unmapped-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/not-found"));
    }

    @Test
    void missingRequiredHeaderReturns400() throws Exception {
        // Call the endpoint WITHOUT the required X-Required-Header — Spring's
        // argument resolver raises MissingRequestHeaderException.
        mockMvc.perform(get("/needs-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Missing Required Header"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/missing-header"));
    }

    /**
     * API-7 (QA council 20260902-134741). {@code GET /api/v1/products?sort=;DROP} returned
     * <b>500 errors/internal</b> live: no handler matched
     * {@link org.springframework.data.mapping.PropertyReferenceException}, so client input
     * reached {@code handleGenericException}. A 5xx from a well-formed request the client
     * simply got wrong pollutes the error budget and tells the caller nothing actionable.
     *
     * <p>The exception here is raised by Spring Data's OWN resolution
     * ({@link PropertyPath#from(String, TypeInformation)}) rather than constructed by hand,
     * so the test exercises the real mechanism the live 500 came from.
     */
    @Test
    void malformedSortPropertyReturns400() throws Exception {
        mockMvc.perform(get("/sorted").param("sort", ";DROP"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Invalid Sort Property"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-sort"))
                .andExpect(jsonPath("$.property").value(";DROP"));
    }

    /**
     * The 400 body names the REJECTED property and nothing else. Spring Data's own
     * {@code getMessage()} is "No property ';DROP' found for type 'FakeProduct'" and,
     * when a near match exists, appends "Did you mean ..." — echoing it would hand an
     * unauthenticated caller the entity name and a list of its real property names.
     * Asserted in both directions: the property IS present, the internals are NOT.
     */
    @Test
    void malformedSortBodyLeaksNoEntityInternals() throws Exception {
        String body = mockMvc.perform(get("/sorted").param("sort", "nam"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains("nam"),
                "the rejected property must be named so the caller can fix the request: " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("FakeProduct"),
                "the entity type must not leak into the client-visible body: " + body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("Did you mean"),
                "Spring Data's property-name hints must not leak into the body: " + body);
    }

    /**
     * The regression arm. A resolvable sort property must still reach the handler and
     * return 200 — without this, "everything is a 400" would satisfy the test above.
     */
    @Test
    void validSortPropertyStillSucceeds() throws Exception {
        mockMvc.perform(get("/sorted").param("sort", "name"))
                .andExpect(status().isOk());
    }


    @RestController
    static class ThrowingController {
        @GetMapping("/unmapped-resource")
        public String throwNoResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/unmapped-resource");
        }

        @GetMapping("/needs-header")
        public String needsHeader(@RequestHeader("X-Required-Header") String header) {
            return header;
        }

        /**
         * Resolves the requested sort property exactly as Spring Data's
         * {@code PageableHandlerMethodArgumentResolver} chain ultimately does — an
         * unknown property raises the real {@code PropertyReferenceException}.
         */
        @GetMapping("/sorted")
        public String sorted(@org.springframework.web.bind.annotation.RequestParam String sort) {
            PropertyPath.from(sort, TypeInformation.of(FakeProduct.class));
            return "ok";
        }
    }

    /** Stand-in for the real {@code Product} entity the live 500 was raised against. */
    @SuppressWarnings("unused")
    static class FakeProduct {
        private String name;

        public String getName() {
            return name;
        }
    }
}
