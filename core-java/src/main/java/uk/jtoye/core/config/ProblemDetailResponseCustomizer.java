package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Issue #448 (QA council {@code disc-20260802-121732}, F-M5-PROBLEMDETAIL) —
 * makes the published contract describe the errors this API actually returns.
 *
 * <p><b>The defect.</b> {@code GlobalExceptionHandler} has returned RFC 7807
 * {@link org.springframework.http.ProblemDetail} bodies for ~30 exception types
 * for a long time, but springdoc cannot see a {@code @RestControllerAdvice}: for
 * a response code it has no schema for, it falls back to the handler method's
 * OWN return type. So the declared 404 on {@code GET /products/{id}} claimed to
 * return a {@code ProductDto}, and {@code application/problem+json} appeared
 * nowhere in the document. Measured on the snapshot before this class existed:
 * 105 declared 4xx/5xx responses, of which 96 pointed at a success DTO and 9 had
 * no body at all; {@code ProblemDetail} was referenced 0 times.
 *
 * <p>A generated client or an LLM agent reading that spec is told to deserialise
 * a {@code ProductDto} out of a 404. That is the second half of the standing
 * agent-readiness contract ("the OpenAPI/machine-readable contract matches live
 * responses") being false in 105 places.
 *
 * <p><b>Why a blanket rewrite is correct here and not over-broad.</b> Every
 * non-2xx response this service emits is produced by {@code GlobalExceptionHandler},
 * and every one of its handlers returns {@code ProblemDetail}. Verified before
 * writing this: no controller under {@code core-java/src/main} builds a 4xx/5xx
 * with an explicit non-ProblemDetail body. So there is no endpoint whose error
 * shape this misdescribes — the rewrite replaces springdoc's inference, which was
 * never a deliberate declaration in the first place.
 *
 * <p><b>Why a customizer rather than ~105 {@code @ApiResponse} annotations.</b>
 * Annotating each site would leave the next endpoint to be added wrong by default
 * and drift silently. Deriving the declaration from the status code keeps one rule
 * in one place, and a new 4xx anywhere in the API is described correctly for free.
 *
 * <p>springdoc auto-detects {@link OpenApiCustomizer} beans, matching the house
 * pattern already set by {@link IdempotencyHeaderCustomizer}.
 */
@Component
public class ProblemDetailResponseCustomizer implements OpenApiCustomizer {

    /** Component schema name; also the {@code $ref} target written onto each 4xx/5xx. */
    static final String SCHEMA_NAME = "ProblemDetail";

    /** RFC 7807 media type. The whole point of the issue is that this appeared 0 times. */
    static final String PROBLEM_JSON = "application/problem+json";

    private static final String SCHEMA_REF = "#/components/schemas/" + SCHEMA_NAME;

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null) {
            return;
        }
        registerSchema(openApi);
        rewriteErrorResponses(openApi);
    }

    /**
     * Registers the RFC 7807 body shape once, in components.
     *
     * <p>Mirrors Spring's {@code ProblemDetail}: the five standard members, plus
     * {@code additionalProperties} because handlers attach machine-readable
     * extension members to it — {@code shopId} and {@code requiredRole} on the shop
     * gate, {@code errors} on a validation failure. Declaring the extensions open
     * rather than enumerating them keeps this honest: a consumer is told they exist
     * without the spec claiming a fixed set it cannot guarantee.
     */
    private void registerSchema(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        Map<String, Schema> schemas = components.getSchemas();
        if (schemas != null && schemas.containsKey(SCHEMA_NAME)) {
            return;
        }
        ObjectSchema problem = new ObjectSchema();
        problem.setName(SCHEMA_NAME);
        problem.setDescription("RFC 7807 problem detail. Every non-2xx response from this API uses "
                + "this shape, produced centrally by GlobalExceptionHandler. Handlers may attach "
                + "additional machine-readable members (e.g. shopId, requiredRole, errors).");
        problem.addProperty("type", new StringSchema()
                .format("uri")
                .description("Stable, dereferenceable identifier for the error class, "
                        + "e.g. https://jtoye.uk/errors/not-found. Match on this, not on the prose."));
        problem.addProperty("title", new StringSchema()
                .description("Short, human-readable summary of the error class."));
        problem.addProperty("status", new IntegerSchema()
                .format("int32")
                .description("HTTP status code, repeated in the body per RFC 7807."));
        problem.addProperty("detail", new StringSchema()
                .description("Human-readable explanation specific to this occurrence."));
        problem.addProperty("instance", new StringSchema()
                .format("uri")
                .description("URI reference identifying the specific occurrence, typically the request path."));
        problem.setAdditionalProperties(Boolean.TRUE);
        components.addSchemas(SCHEMA_NAME, problem);
    }

    /**
     * Points every declared 4xx/5xx at the problem schema.
     *
     * <p>Deliberately replaces (not merges) the content: the incumbent entry is
     * springdoc's inferred success DTO under {@code *}/{@code *}, which is exactly
     * the wrong declaration this issue exists to remove. Leaving it alongside would
     * still tell a client a 404 might carry a {@code ProductDto}.
     *
     * <p>Any existing response <em>description</em> is preserved — that prose is the
     * hand-written part and is often the only place the trigger condition is stated.
     */
    private void rewriteErrorResponses(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem pathItem : openApi.getPaths().values()) {
            if (pathItem == null) {
                continue;
            }
            for (Operation operation : pathItem.readOperations()) {
                if (operation == null) {
                    continue;
                }
                ApiResponses responses = operation.getResponses();
                if (responses == null) {
                    continue;
                }
                for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
                    if (isErrorStatus(entry.getKey()) && entry.getValue() != null) {
                        entry.getValue().setContent(problemContent());
                    }
                }
            }
        }
    }

    /**
     * True for 4xx and 5xx. Anything else — 2xx, 3xx, or the {@code default} key —
     * is left untouched: {@code default} may legitimately describe a success shape,
     * and silently rewriting it would be a wider change than this issue asks for.
     */
    private boolean isErrorStatus(String code) {
        return code != null && code.length() == 3
                && (code.charAt(0) == '4' || code.charAt(0) == '5');
    }

    private Content problemContent() {
        return new Content().addMediaType(PROBLEM_JSON,
                new MediaType().schema(new Schema<>().$ref(SCHEMA_REF)));
    }
}
