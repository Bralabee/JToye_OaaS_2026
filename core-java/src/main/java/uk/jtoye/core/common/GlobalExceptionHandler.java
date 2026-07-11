package uk.jtoye.core.common;

import com.stripe.exception.StripeException;
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.InsufficientStockException;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
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
