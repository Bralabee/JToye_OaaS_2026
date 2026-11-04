package uk.jtoye.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.RateLimitInterceptor;

import java.util.Map;

/**
 * #879 / UXT-123 (P2-RAV-15): every operation the rate limiter guards says so in the contract.
 *
 * <p>{@link RateLimitInterceptor} sits on {@code /**}: an authenticated caller draws on its tenant's
 * bucket, a {@code /public} caller on its client address's, and both answer 429 with an RFC 7807 body
 * ({@code https://jtoye.uk/errors/rate-limited}, a typed {@code retryAfterSeconds}) and a
 * {@code Retry-After} header. The spec documented that on 7 of 207 operations, so an integrator
 * generating a client from it was never told to back off on the response it is most likely to meet.
 *
 * <p>One rule in one place, the {@link ProblemDetailResponseCustomizer} way: the paths the limiter never
 * guards are {@link RateLimitInterceptor#EXCLUDED_PATH_PATTERNS}, the same array {@code WebConfig} registers
 * the interceptor with, so a path added to the exclusion stops being documented here too. What cannot be
 * known from a path is the interceptor's other pass-through (a non-{@code /public} request with no tenant
 * claim is not throttled); the text says so rather than claiming every call draws on a bucket.
 *
 * <p>An operation that already documents its own 429 (the per-IP limits on the public intake endpoints)
 * keeps that description and gains the headers; the limiter can answer on those paths as well.
 */
@Component
public class RateLimitResponseCustomizer implements OpenApiCustomizer {

    static final String RETRY_AFTER = "Retry-After";

    static final String DESCRIPTION = "Rate limit exceeded (errors/rate-limited). Wait Retry-After seconds, then retry "
            + "the same request; retryAfterSeconds in the body carries the same number. Authenticated calls draw on "
            + "the tenant's bucket (a call that carries no tenant claim is not throttled), /public calls on the "
            + "client address's. This describes errors/rate-limited only: an operation that can answer 429 for "
            + "another typed reason says so in its own text, and that reason is not retried by waiting.";

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || openApi.getPaths() == null) {
            return;
        }
        for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
            if (path.getValue() == null || RateLimitInterceptor.isExcludedPath(path.getKey())) {
                continue;
            }
            for (Operation operation : path.getValue().readOperations()) {
                document(operation);
            }
        }
    }

    private void document(Operation operation) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        ApiResponse r429 = responses.get("429");
        if (r429 == null) {
            r429 = new ApiResponse().description(DESCRIPTION);
            responses.addApiResponse("429", r429);
        } else if (r429.getDescription() == null || !r429.getDescription().contains(RETRY_AFTER)) {
            String own = r429.getDescription() == null || r429.getDescription().isBlank() ? "" : r429.getDescription() + ". ";
            r429.setDescription(own + "The rate limiter also answers 429 here (errors/rate-limited) with "
                    + RETRY_AFTER + " set to the seconds to wait.");
        }
        if (r429.getContent() == null || r429.getContent().isEmpty()) {
            r429.setContent(new Content().addMediaType(ProblemDetailResponseCustomizer.PROBLEM_JSON,
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/"
                            + ProblemDetailResponseCustomizer.SCHEMA_NAME))));
        }
        header(r429, RETRY_AFTER, "Seconds to wait before retrying (errors/rate-limited only; absent on any other 429)");
        header(r429, "X-RateLimit-Limit", "Capacity of the bucket that was exceeded");
        header(r429, "X-RateLimit-Remaining", "Tokens left in the bucket (0 on a 429)");
        header(r429, "X-RateLimit-Reset", "Unix time, in seconds, at which the bucket has refilled");
    }

    private void header(ApiResponse response, String name, String description) {
        if (response.getHeaders() != null && response.getHeaders().containsKey(name)) {
            return;
        }
        response.addHeaderObject(name, new Header()
                .description(description)
                .schema(new IntegerSchema().format("int64")));
    }
}
