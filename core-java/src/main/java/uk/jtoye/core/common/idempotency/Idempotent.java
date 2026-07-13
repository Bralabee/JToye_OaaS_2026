package uk.jtoye.core.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler method as honoring the uniform
 * {@code Idempotency-Key} HTTP header contract (Issue #204 / AI-2).
 *
 * <p>Two things key off this marker:
 * <ul>
 *   <li>{@code IdempotencyHeaderCustomizer} (a springdoc
 *       {@code OperationCustomizer}) advertises the {@code Idempotency-Key}
 *       header in the OpenAPI spec on EXACTLY the annotated operations.</li>
 *   <li>The annotated controller method routes its create through
 *       {@link IdempotencyService#execute} when the header is present.</li>
 * </ul>
 *
 * <p>The single {@link #endpoint()} attribute is the LOGICAL operation id
 * (e.g. {@code "orders.create"}), NOT the URL — so the dedup key survives
 * future API versioning (a {@code /api/v2} move keeps the same logical id).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /**
     * The logical operation id stored in the {@code idempotency_keys.endpoint}
     * column, e.g. {@code "orders.create"}. Deliberately not the URL.
     */
    String endpoint();
}
