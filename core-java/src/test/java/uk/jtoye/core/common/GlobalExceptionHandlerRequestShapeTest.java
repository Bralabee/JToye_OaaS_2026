package uk.jtoye.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * </ul>
 *
 * Before these handlers existed both fell through to {@code handleGenericException}
 * → HTTP 500 with a full stacktrace logged at ERROR per request.
 */
class GlobalExceptionHandlerRequestShapeTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MappingJackson2HttpMessageConverter jackson =
                new MappingJackson2HttpMessageConverter(new ObjectMapper());
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
    }
}
