package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-10 (QA council 20260902-134741) through the REAL filter chain.
 *
 * <p>{@link ProblemDetailAuthenticationEntryPointTest} proves the component; this proves it
 * is WIRED. An entry point that exists as a bean and is never registered on the chain is
 * indistinguishable from the defect — the live 401 came out of Spring Security's default,
 * not out of any class in this package.
 *
 * <p>Both doors into a 401 are exercised, because they are handled by different filters:
 * a request with NO Authorization header is refused by {@code ExceptionTranslationFilter},
 * and a request carrying a garbage bearer is refused by
 * {@code BearerTokenAuthenticationFilter}. Wiring only one leaves the other empty-bodied.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class UnauthenticatedProblemDetailIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingBearerReturnsRfc7807ProblemDocument() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void garbageBearerReturnsRfc7807ProblemDocument() throws Exception {
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/unauthorized"))
                .andExpect(jsonPath("$.status").value(401));
    }

    /**
     * The control that makes the two assertions above mean something: a PERMITTED route
     * must still be reachable anonymously with its own, non-problem body. Without it,
     * "every anonymous request returns an unauthorized problem document" would satisfy
     * the pair — including a chain that had started refusing the public storefront.
     */
    @Test
    void permittedRouteStillAnswersAnonymously() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
}
