package uk.jtoye.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.exception.InsufficientStockException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc test pinning the CQ-01 HTTP 409 ProblemDetail contract:
 * a controller that throws {@link InsufficientStockException} is rendered by
 * {@link GlobalExceptionHandler} into an RFC 7807 Problem response with
 * status 409, title "Insufficient Stock", and the configured type URI.
 *
 * <p>No Spring context, no Testcontainers — runs in the default unit test
 * suite (not tagged {@code testcontainers}). Fast regression guard.
 */
class InsufficientStockExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Register a Jackson-backed HTTP message converter explicitly so the
        // ProblemDetail returned from @ExceptionHandler serializes to JSON as an
        // object (not a String). Boot's default auto-config provides this, but
        // standaloneSetup deliberately omits it — plug it back in for the test.
        MappingJackson2HttpMessageConverter jackson =
                new MappingJackson2HttpMessageConverter(new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jackson)
                .build();
    }

    @Test
    void throwInsufficientStockReturns409() throws Exception {
        mockMvc.perform(get("/test-throw"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Insufficient Stock"))
                .andExpect(jsonPath("$.detail").value("test-detail"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/insufficient-stock"));
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test-throw")
        public String throwIt() {
            throw new InsufficientStockException("test-detail");
        }
    }
}
