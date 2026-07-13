package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import uk.jtoye.core.common.idempotency.Idempotent;

/**
 * Issue #204 (AI-2) — advertises the {@code Idempotency-Key} request header in
 * the OpenAPI spec on EXACTLY the operations that honor it, i.e. controller
 * methods annotated with {@link Idempotent}.
 *
 * <p>springdoc auto-detects beans of type {@link OperationCustomizer}, so this
 * {@code @Component} is applied to every scanned operation. Keying the header
 * off the annotation (rather than a {@code @RequestHeader} in each method
 * signature) couples the advertisement to the contract marker, so a future
 * endpoint that adopts {@code @Idempotent} gets the documented header for free.
 */
@Component
public class IdempotencyHeaderCustomizer implements OperationCustomizer {

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (handlerMethod.hasMethodAnnotation(Idempotent.class)) {
            operation.addParametersItem(new Parameter()
                    .in("header")
                    .name("Idempotency-Key")
                    .required(false)
                    .description("Client-supplied key; same key replays the original response, never duplicates.")
                    .schema(new StringSchema().maxLength(64)));
        }
        return operation;
    }
}
