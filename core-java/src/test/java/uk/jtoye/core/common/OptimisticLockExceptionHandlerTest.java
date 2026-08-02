package uk.jtoye.core.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA council {@code disc-20260802-121732} F-M1 / INT-03 — pins the 409 contract for a lost
 * optimistic-lock race.
 *
 * <p>Before the handler existed, {@link ObjectOptimisticLockingFailureException} matched none of
 * {@link GlobalExceptionHandler}'s handlers and fell to the {@code Exception.class} catch-all: an
 * opaque 500 {@code .../errors/internal}. Measured then: 8 barrier-synchronised {@code confirm}s on
 * one order gave {@code {200: 1, 500: 7}} while the data stayed consistent (exactly one transition
 * applied). The same contention run sequentially already returned a typed 400.
 *
 * <p>Same standalone-MockMvc shape as {@link InsufficientStockExceptionHandlerTest}: no Spring
 * context and no Testcontainers, so it runs in the default unit suite.
 */
class OptimisticLockExceptionHandlerTest {

    /**
     * The real provider message, copied verbatim from the app log captured in the finding
     * ({@code evidence/int-findings.md} INT-03). It is the input to the leak assertion below —
     * a paraphrase would not prove anything about what actually reaches a client.
     */
    private static final String PROVIDER_MESSAGE =
            "Batch update returned unexpected row count from update [0]; actual row count: 0; "
                    + "expected: 1; statement was [update orders set status=?, version=? "
                    + "where id=? and version=?]";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup omits Boot's Jackson converter, so ProblemDetail would serialize as a
        // String rather than an object. Plug it back in — same reason as the sibling handler test.
        //
        // Jackson2ObjectMapperBuilder, NOT a bare `new ObjectMapper()`: the builder is what Boot's
        // auto-configured mapper is built with, and it is what registers ProblemDetailJacksonMixin
        // — the mixin that flattens setProperty() members to the top level instead of nesting them
        // under "properties". With a bare mapper the `code` assertion below fails with
        // PathNotFoundException against a handler that is perfectly correct in production. Same
        // reasoning, and the same fix, as RateLimitInterceptorTest (issue #413).
        MappingJackson2HttpMessageConverter jackson =
                new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json().build());
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(jackson)
                .build();
    }

    /** The symptom the finding actually observed: the Hibernate-specific subclass. */
    @Test
    void objectOptimisticLockingFailureReturnsTyped409() throws Exception {
        mockMvc.perform(get("/test-object-optlock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent Modification"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/concurrent-modification"))
                .andExpect(jsonPath("$.code").value("concurrent-modification"));
    }

    /**
     * The handler is declared on the {@link OptimisticLockingFailureException} SUPERCLASS on
     * purpose, so a bare Spring-translated lock failure — not just Hibernate's subclass — is also
     * covered. Catching only the subclass would have left this one falling through to the 500.
     */
    @Test
    void bareOptimisticLockingFailureAlsoReturnsTyped409() throws Exception {
        mockMvc.perform(get("/test-bare-optlock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/concurrent-modification"));
    }

    /**
     * The provider message names the table and the {@code version} column. It is logged, never
     * returned. Asserting the fixed detail alone would pass just as well if the raw message were
     * appended to it, so the absence of the leaked tokens is asserted directly.
     */
    @Test
    void detailIsFixedAndDoesNotLeakTheProviderMessage() throws Exception {
        mockMvc.perform(get("/test-object-optlock"))
                .andExpect(jsonPath("$.detail")
                        .value("This record was modified by another request. Re-read it and retry."))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("version"))))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Batch update"))))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("update orders set"))));
    }

    /**
     * CONTROL ARM — without this the suite above cannot distinguish "optimistic-lock failures are
     * now typed 409" from "the advice started answering 409 to everything". A genuinely unexpected
     * exception must still reach the {@code Exception.class} catch-all as a 500.
     */
    @Test
    void unrelatedExceptionStillFallsThroughTo500() throws Exception {
        mockMvc.perform(get("/test-unrelated"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/internal"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test-object-optlock")
        public String throwObjectOptLock() {
            throw new ObjectOptimisticLockingFailureException(
                    PROVIDER_MESSAGE, new RuntimeException("stale state"));
        }

        @GetMapping("/test-bare-optlock")
        public String throwBareOptLock() {
            throw new OptimisticLockingFailureException(PROVIDER_MESSAGE);
        }

        @GetMapping("/test-unrelated")
        public String throwUnrelated() {
            throw new RuntimeException("something genuinely unexpected");
        }
    }
}
