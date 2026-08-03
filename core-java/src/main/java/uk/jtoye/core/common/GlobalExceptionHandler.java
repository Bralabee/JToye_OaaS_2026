package uk.jtoye.core.common;

import com.stripe.exception.StripeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.jtoye.core.exception.IdempotencyConflictException;
import uk.jtoye.core.exception.IdempotencyPayloadMismatchException;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.LastGroupAdminException;
import uk.jtoye.core.exception.MissingTenantContextException;
import uk.jtoye.core.exception.ReservedSlugException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.media.exception.DecompressionBombException;
import uk.jtoye.core.media.exception.MediaRedriveRejectedException;
import uk.jtoye.core.media.exception.PayloadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralized exception handling for all REST controllers.
 * Returns RFC 7807 Problem Details for consistent error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("https://jtoye.uk/errors/not-found"));
        return problem;
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid State Transition");
        problem.setType(URI.create("https://jtoye.uk/errors/invalid-state-transition"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Argument");
        problem.setType(URI.create("https://jtoye.uk/errors/invalid-argument"));
        return problem;
    }

    /**
     * IN-08: a missing tenant context is a SERVER security-configuration fault
     * (the JWT/tenant filter chain failed to establish a tenant), so it maps to
     * 500 per the documented convention — not the generic
     * {@code IllegalStateException} 400 below, which blamed the client for a
     * server misconfiguration. Detail stays generic; the specifics go to the
     * ERROR log.
     */
    @ExceptionHandler(MissingTenantContextException.class)
    public ProblemDetail handleMissingTenantContext(MissingTenantContextException ex) {
        log.error("Tenant context missing on a tenant-scoped operation: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Tenant context is not established");
        problem.setTitle("Missing Tenant Context");
        problem.setType(URI.create("https://jtoye.uk/errors/missing-tenant-context"));
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid State");
        problem.setType(URI.create("https://jtoye.uk/errors/invalid-state"));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://jtoye.uk/errors/validation"));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String message = "Data integrity constraint violated";
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("idx_products_tenant_sku")) {
                message = "Product SKU already exists for this tenant";
            } else if (ex.getMessage().contains("idx_shops_tenant_name")) {
                message = "Shop name already exists for this tenant";
            } else if (ex.getMessage().contains("uq_onboarding_tenant")) {
                message = "An onboarding already exists for this tenant";
            }
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, message);
        problem.setTitle("Duplicate Entry");
        problem.setType(URI.create("https://jtoye.uk/errors/duplicate"));
        return problem;
    }

    /**
     * Map CQ-01 stock race exception to HTTP 409 Conflict (RFC 9110 §15.5.10).
     * Thrown by StockService.decrementForOrder on exhaustion (insufficient stock
     * OR @Recover after 3 optimistic-lock retries).
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient Stock");
        problem.setType(URI.create("https://jtoye.uk/errors/insufficient-stock"));
        return problem;
    }

    /**
     * Map missing required @RequestParam to HTTP 400 (rather than letting it fall
     * through to the catch-all 500 handler).
     *
     * <p>AUDIT-W0-02 (Phase 16.1): the customer-orders endpoint now requires
     * {@code verify} as a non-optional param to prevent email-based order
     * enumeration. Spring raises this exception on absence; we return 400 so the
     * client sees a request-shape error, not a server fault.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingRequestParam(MissingServletRequestParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Missing Required Parameter");
        problem.setType(URI.create("https://jtoye.uk/errors/missing-parameter"));
        return problem;
    }

    /**
     * QA-council L2 — a missing required {@code @RequestHeader} (e.g. the
     * absent {@code Stripe-Signature} on the payments webhook) is a client
     * request-shape error, so return 400 rather than letting it fall through
     * to the catch-all 500. Signature verification of a *present* header is
     * unchanged (invalid signature still 400 and the event is not processed).
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingRequestHeader(MissingRequestHeaderException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Missing Required Header");
        problem.setType(URI.create("https://jtoye.uk/errors/missing-header"));
        return problem;
    }

    /**
     * QA-council L1 — an unmapped/unversioned path (e.g. the bare {@code /shops}
     * against the versioned {@code /api/v1} API) must return 404, not 500.
     * Spring raises {@link NoResourceFoundException} when no handler/static
     * resource matches; without an explicit, more-specific handler it fell
     * through to {@code handleGenericException}, which returned 500 AND logged a
     * full stacktrace at ERROR for every unmapped request (5xx-alert noise). This
     * handler is more specific than the {@code ResponseStatusException} handler,
     * so it wins and yields a clean 404 with no stacktrace log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        problem.setTitle("Not Found");
        problem.setType(URI.create("https://jtoye.uk/errors/not-found"));
        return problem;
    }

    /**
     * Map any controller-thrown {@link ResponseStatusException} to its embedded
     * status + reason, preserving the status the controller intended.
     *
     * <p>AUDIT-W0-02 (Phase 16.1): used by the customer-orders endpoint to reject
     * blank {@code verify} with 400. Without this handler, the catch-all
     * {@code handleGenericException} would swallow the exception as 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://jtoye.uk/errors/response-status"));
        return problem;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed");
        problem.setTitle("Unauthorized");
        problem.setType(URI.create("https://jtoye.uk/errors/unauthorized"));
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://jtoye.uk/errors/forbidden"));
        return problem;
    }

    /**
     * Phase 23 VSA-02 (D-01/D-13) — the in-tenant shop-role gate
     * ({@code ShopAccessService.require}) denial. Returns 403 with a type URI
     * that is DELIBERATELY DISTINCT from BOTH the RLS 404
     * ({@code handleResourceNotFound}, {@code .../not-found}) AND the generic
     * admin 403 ({@code handleAccessDenied}, {@code .../forbidden}). Blurring the
     * shop-403 with the RLS-404 would leak the tenant-boundary signal (SPEC
     * §D-01); a distinct type also lets the frontend key its access-required
     * state on the shop gate specifically. {@code ShopAccessDeniedException}
     * intentionally does NOT extend {@code AccessDeniedException}, so this
     * more-specific handler (not {@code handleAccessDenied}) is what fires.
     *
     * <p>Carries machine-parseable {@code shopId} + {@code requiredRole}
     * properties (agent-readiness contract: typed/stable codes).
     */
    @ExceptionHandler(ShopAccessDeniedException.class)
    public ProblemDetail handleShopAccessDenied(ShopAccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Shop access denied");
        problem.setTitle("Shop Access Denied");
        problem.setType(URI.create("https://jtoye.uk/errors/shop-access-denied"));
        if (ex.getShopId() != null) {
            problem.setProperty("shopId", ex.getShopId());
        }
        if (ex.getRequiredRole() != null) {
            problem.setProperty("requiredRole", ex.getRequiredRole());
        }
        return problem;
    }

    /**
     * Phase 23 VSA-04 (D-11) — a staff revoke/downgrade that would remove the
     * last GROUP_ADMIN in a tenant. 409 Conflict with a stable, distinct type
     * (mirrors {@code handleIdempotencyConflict}).
     */
    @ExceptionHandler(LastGroupAdminException.class)
    public ProblemDetail handleLastGroupAdmin(LastGroupAdminException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Last Group Admin");
        problem.setType(URI.create("https://jtoye.uk/errors/last-group-admin"));
        return problem;
    }

    /**
     * Phase 17 VOPS-02 — map any {@link StripeException} (the SDK base type
     * that {@code InvalidRequestException}, {@code ApiException} et al. extend)
     * to HTTP 502. We are the gateway between the vendor and Stripe; a Stripe
     * failure is a bad-gateway from the client's perspective.
     *
     * <p>Body surfaces only {@code ex.getMessage()} and {@code stripeCode} —
     * the full stack trace is logged server-side at WARN, not returned to
     * the client (T-17-14).
     */
    @ExceptionHandler(StripeException.class)
    public ProblemDetail handleStripeException(StripeException ex) {
        log.warn("Stripe API error: code={} message={}", ex.getCode(), ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "Payment provider error: " + ex.getMessage());
        problem.setTitle("Payment Provider Error");
        problem.setType(URI.create("https://jtoye.uk/errors/payment-provider"));
        if (ex.getCode() != null) {
            problem.setProperty("stripeCode", ex.getCode());
        }
        return problem;
    }

    /**
     * QA-council BE-04 — classify common request-shape faults as their correct
     * 4xx status instead of letting them fall through to the catch-all 500.
     * Bodies stay generic RFC-7807 (no internal detail leaked).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://jtoye.uk/errors/unreadable-request"));
        return problem;
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Unsupported Media Type");
        problem.setType(URI.create("https://jtoye.uk/errors/unsupported-media-type"));
        return problem;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problem.setTitle("Method Not Allowed");
        problem.setType(URI.create("https://jtoye.uk/errors/method-not-allowed"));
        return problem;
    }

    /**
     * Bad path/query variable type (e.g. a non-UUID id) — a client error, 400,
     * not a server fault.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'");
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://jtoye.uk/errors/type-mismatch"));
        return problem;
    }

    /**
     * PPDS / Natasha's Law (Issue #82 P0-6) — a product that lacks the required
     * compliance data (business identity, shelf life, durability type) cannot be
     * turned into a compliant allergen label. The request is well-formed but
     * semantically unprocessable, so return 422 with a message naming the missing
     * field(s), rather than emitting a non-compliant PDF or falling through to 500.
     */
    @ExceptionHandler(IncompleteLabelDataException.class)
    public ProblemDetail handleIncompleteLabelData(IncompleteLabelDataException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Incomplete Label Data");
        problem.setType(URI.create("https://jtoye.uk/errors/incomplete-label-data"));
        return problem;
    }

    /**
     * Issue #204 (AI-2) — a concurrent same-{@code Idempotency-Key} request that
     * arrives while the first request is still in-flight (the reserved row has a
     * NULL {@code response_status}). 409 Conflict is the honest, race-safe answer
     * (matching Stripe): a later retry, once the first request commits, replays
     * the stored response.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Idempotency Conflict");
        problem.setType(URI.create("https://jtoye.uk/errors/idempotency-conflict"));
        return problem;
    }

    /**
     * Issue #204 (AI-2) — the same {@code Idempotency-Key} reused with a
     * DIFFERENT request body (stored {@code request_hash} mismatch). The request
     * is well-formed but semantically conflicts with the key's prior use, so it
     * is neither replayed nor executed afresh: 422 Unprocessable Entity.
     */
    /**
     * A vendor-supplied shop slug collided with a static storefront route segment.
     * 422 rather than 400: the request is well-formed and the value is individually
     * valid, it just cannot be accepted in this position.
     */
    @ExceptionHandler(ReservedSlugException.class)
    public ProblemDetail handleReservedSlug(ReservedSlugException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Reserved Shop Slug");
        problem.setType(URI.create("https://jtoye.uk/errors/reserved-shop-slug"));
        return problem;
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ProblemDetail handleIdempotencyPayloadMismatch(IdempotencyPayloadMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Idempotency Key Reused");
        problem.setType(URI.create("https://jtoye.uk/errors/idempotency-payload-mismatch"));
        return problem;
    }

    /**
     * Phase 24 (IMG-02 / T-24-09) — the reject-early oversize guard on the media accept.
     * {@code MediaUploadController.accept} throws this when the declared {@code Content-Length}
     * exceeds {@code jtoye.media.max-upload-bytes} BEFORE buffering the body. 413 with a stable
     * {@code .../errors/payload-too-large} type (D-06 machine-parseable errors).
     */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
        problem.setTitle("Payload Too Large");
        problem.setType(URI.create("https://jtoye.uk/errors/payload-too-large"));
        return problem;
    }

    /**
     * Phase 24 (IMG-02) — the SECOND size gate: Spring/Tomcat aborts a genuinely oversize
     * multipart body (past {@code spring.servlet.multipart.max-file-size}) with this. Map it
     * to the SAME RFC 7807 413 as the Content-Length guard so the client sees one stable
     * oversize contract regardless of which gate fired.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the maximum permitted size");
        problem.setTitle("Payload Too Large");
        problem.setType(URI.create("https://jtoye.uk/errors/payload-too-large"));
        return problem;
    }

    /**
     * Issue #445 — the decompression-bomb guard now runs on the SYNCHRONOUS upload endpoints
     * ({@code POST /products/{id}/images}, {@code /shops/{id}/logo}, {@code /shops/{id}/banner})
     * as well as inside the async worker, so for the first time it can escape to a request thread.
     *
     * <p>422 rather than 400 or 413: the request is well-formed and the file is genuinely under
     * the byte cap — it is the DECODED raster that would be enormous, which is a semantic
     * rejection, not a syntax or size one. The stable {@code .../errors/decompression-bomb} type
     * lets a machine client branch without parsing prose (D-06 / agent-readiness).
     *
     * <p>On the async path this exception never reaches here: {@code MediaProcessingWorker}
     * catches it and maps it to {@code status=FAILED} + a vendor-visible failure reason.
     */
    @ExceptionHandler(DecompressionBombException.class)
    public ProblemDetail handleDecompressionBomb(DecompressionBombException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Image Rejected");
        problem.setType(URI.create("https://jtoye.uk/errors/decompression-bomb"));
        problem.setProperty("code", "DECOMPRESSION_BOMB");
        return problem;
    }

    /**
     * Phase 27 (27-01 / D-04) — the three preconditions that reject a manual re-drive
     * ({@code POST /api/v1/media/{assetId}/reprocess}): bytes not retained, the asset is already
     * ACTIVE, or the re-drive budget is exhausted (T-27-03). All three are 409 — the request is
     * well-formed and the caller is authorized; the asset's state is simply incompatible.
     *
     * <p>ONE handler for the whole {@link MediaRedriveRejectedException} family rather than three
     * near-identical methods: each subclass carries its own stable {@code type} slug and
     * {@code code}, which are read off the exception here. An agent branches on {@code code}
     * without parsing prose (D-06 / agent-readiness).
     */
    @ExceptionHandler(MediaRedriveRejectedException.class)
    public ProblemDetail handleMediaRedriveRejected(MediaRedriveRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Media Re-process Rejected");
        problem.setType(URI.create("https://jtoye.uk/errors/" + ex.getTypeSlug()));
        problem.setProperty("code", ex.getCode());
        return problem;
    }

    /**
     * QA council {@code disc-20260802-121732} F-M1 / INT-03 — two writers reached the same row and
     * the JPA {@code @Version} check lost the race, so the UPDATE matched 0 rows. Until this handler
     * existed, {@link OptimisticLockingFailureException} matched none of the other handlers and fell
     * to the {@code Exception.class} catch-all below: an opaque 500 {@code .../errors/internal},
     * "An unexpected error occurred".
     *
     * <p><b>Why 409 and not 500.</b> Nothing failed. The measured behaviour is that data integrity
     * HOLDS — 8 barrier-synchronised {@code confirm}s produced exactly one transition and a
     * consistent final state; 7 callers simply lost. That is the definition of a conflict, and the
     * same contention run SEQUENTIALLY already returns a typed 400. Reporting the concurrent case as
     * a server fault made an identical, correct outcome look like a crash.
     *
     * <p><b>Why it mattered operationally.</b> A KDS is a shared shop screen, so two staff bumping
     * one ticket is the normal case, not an edge case — and the frontend api-client auto-retries on
     * 5xx. A 500 therefore turned ordinary contention into a retry storm against a row whose write
     * had already succeeded. 4xx stops that: the caller re-reads and decides.
     *
     * <p>Caught at the {@link OptimisticLockingFailureException} superclass, not at
     * {@code ObjectOptimisticLockingFailureException}, so the Hibernate-specific subclass, a bare
     * {@code StaleObjectStateException} translated by Spring, and any future
     * {@code @Version}-carrying entity all land here rather than only the two endpoints where this
     * was observed. This is ONE root cause with two reported symptoms (the concurrent transitions of
     * INT-03 and the cross-tenant delete of the security lane's A1-del), and one handler closes both.
     *
     * <p>The detail is a FIXED string. The provider message is
     * {@code "Batch update returned unexpected row count ... where id=? and version=?"}, which leaks
     * table shape and the optimistic-locking column to an unauthenticated-reachable error body; it
     * is logged instead. {@code code} is set for the same reason as
     * {@link MediaRedriveRejectedException}: an agent branches on it without parsing prose (D-06).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        // WARN, not ERROR: expected contention on a shared screen, not a fault to page on. Logged
        // rather than returned, because the provider message names the table and the version column.
        log.warn("Optimistic lock conflict — concurrent write lost the version check: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "This record was modified by another request. Re-read it and retry.");
        problem.setTitle("Concurrent Modification");
        problem.setType(URI.create("https://jtoye.uk/errors/concurrent-modification"));
        problem.setProperty("code", "concurrent-modification");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        // Log the full exception for debugging (avoid exposing internal details to client)
        log.error("Unexpected exception occurred: {}", ex.getMessage(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://jtoye.uk/errors/internal"));
        return problem;
    }
}
