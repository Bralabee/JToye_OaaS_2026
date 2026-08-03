package uk.jtoye.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #448 — the published contract must describe error responses as RFC 7807, not as the
 * success DTO springdoc infers from the handler's return type.
 *
 * <p><strong>What makes each arm falsifiable.</strong> The fixture below is built in the exact
 * shape the unfixed snapshot had: a 404 declaring {@code ProductDto} under {@code *}/{@code *}.
 * The 4xx arm therefore fails if the customizer merges instead of replaces, and the 2xx arm fails
 * if it over-reaches — an over-broad rewrite that clobbered success bodies would be a far worse
 * regression than the defect, and nothing else in the suite would catch it.
 */
class ProblemDetailResponseCustomizerTest {

    private final ProblemDetailResponseCustomizer customizer = new ProblemDetailResponseCustomizer();

    @Test
    @DisplayName("registers the ProblemDetail schema, which appeared 0 times before")
    void registersSchema() {
        OpenAPI api = document();

        customizer.customise(api);

        Schema<?> problem = api.getComponents().getSchemas()
                .get(ProblemDetailResponseCustomizer.SCHEMA_NAME);
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsKeys("type", "title", "status", "detail", "instance");
        assertThat(problem.getAdditionalProperties())
                .as("handlers attach extension members (shopId, requiredRole, errors), so the "
                        + "shape must be open rather than claiming a closed set")
                .isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("a 404 that claimed a success DTO now references ProblemDetail, and ONLY that")
    void rewritesErrorResponses() {
        OpenAPI api = document();

        customizer.customise(api);

        Content notFound = api.getPaths().get("/products/{id}").getGet()
                .getResponses().get("404").getContent();

        assertThat(notFound.keySet())
                .as("leaving the inferred success media type alongside would still tell a client "
                        + "a 404 might carry a ProductDto")
                .containsExactly(ProblemDetailResponseCustomizer.PROBLEM_JSON);
        assertThat(notFound.get(ProblemDetailResponseCustomizer.PROBLEM_JSON).getSchema().get$ref())
                .isEqualTo("#/components/schemas/ProblemDetail");
    }

    @Test
    @DisplayName("5xx is rewritten too; 2xx and the description are left alone")
    void rewritesServerErrorsButNotSuccesses() {
        OpenAPI api = document();

        customizer.customise(api);

        ApiResponses responses = api.getPaths().get("/products/{id}").getGet().getResponses();

        assertThat(responses.get("500").getContent().keySet())
                .containsExactly(ProblemDetailResponseCustomizer.PROBLEM_JSON);
        assertThat(responses.get("200").getContent().keySet())
                .as("the success body is the contract and must survive untouched")
                .containsExactly("application/json");
        assertThat(responses.get("404").getDescription())
                .as("the hand-written prose is often the only statement of the trigger condition")
                .isEqualTo("Product not found");
    }

    /** Reproduces the pre-fix snapshot shape: a 404 whose declared body is the success DTO. */
    private OpenAPI document() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("200", new ApiResponse()
                        .description("Product found")
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProductDto")))))
                .addApiResponse("404", new ApiResponse()
                        .description("Product not found")
                        .content(new Content().addMediaType("*/*",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProductDto")))))
                .addApiResponse("500", new ApiResponse()
                        .description("Server error")
                        .content(new Content().addMediaType("*/*",
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProductDto")))));

        Paths paths = new Paths();
        paths.addPathItem("/products/{id}", new PathItem().get(new Operation().responses(responses)));
        return new OpenAPI().paths(paths);
    }
}
