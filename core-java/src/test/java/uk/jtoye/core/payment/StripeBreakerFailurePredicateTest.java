package uk.jtoye.core.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StripeBreakerFailurePredicate} (issue #108, T-30-126).
 *
 * <p>Only provider / transport failures (network errors, HTTP 429 rate limits,
 * HTTP 5xx or status-less {@link ApiException}, and raw {@link IOException}s)
 * may count toward opening a Stripe circuit breaker. Business / caller 4xx
 * rejections ({@link CardException}, {@link InvalidRequestException},
 * {@link IdempotencyException}, {@link AuthenticationException},
 * {@link PermissionException}) and local runtime bugs must return {@code false}.
 */
class StripeBreakerFailurePredicateTest {

    private final StripeBreakerFailurePredicate predicate = new StripeBreakerFailurePredicate();

    @Test
    @DisplayName("CardException (402 card declined) is a caller/card rejection, not a provider failure")
    void cardException_returnsFalse() {
        CardException ex = new CardException(
                "Your card was declined.", "req_card", "card_declined",
                null, "generic_decline", null, 402, null);
        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("InvalidRequestException (400 bad parameter / already refunded) returns false")
    void invalidRequestException400_returnsFalse() {
        InvalidRequestException ex = new InvalidRequestException(
                "Charge ch_123 has already been refunded.", "charge",
                "req_inv", "charge_already_refunded", 400, null);
        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("IdempotencyException (400 key conflict) returns false")
    void idempotencyException_returnsFalse() {
        IdempotencyException ex = new IdempotencyException(
                "Keys for idempotent requests can only be used with the same parameters",
                "req_idem", "idempotency_key_in_use", 400);
        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("AuthenticationException (401 bad API key) returns false")
    void authenticationException_returnsFalse() {
        AuthenticationException ex = new AuthenticationException(
                "Invalid API Key provided", "req_auth", "api_key_invalid", 401);
        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("PermissionException (403 forbidden) returns false")
    void permissionException_returnsFalse() {
        PermissionException ex = new PermissionException(
                "You do not have permissions to perform this request",
                "req_perm", "account_invalid", 403);
        assertThat(predicate.test(ex)).isFalse();
    }

    @Test
    @DisplayName("Non-Stripe local runtime exceptions (IllegalStateException, null) return false")
    void illegalStateException_returnsFalse() {
        assertThat(predicate.test(new IllegalStateException("local validation failure"))).isFalse();
        assertThat(predicate.test(null)).isFalse();
    }

    @Test
    @DisplayName("ApiConnectionException (transport / timeout) returns true")
    void apiConnectionException_returnsTrue() {
        ApiConnectionException ex = new ApiConnectionException(
                "IOException during API request to Stripe", new SocketTimeoutException("Read timed out"));
        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("RateLimitException (429 provider throttling) returns true")
    void rateLimitException_returnsTrue() {
        RateLimitException ex = new RateLimitException(
                "Too many requests", null, "req_rl", "rate_limit", 429, null);
        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("ApiException with HTTP 500 status returns true")
    void apiException500_returnsTrue() {
        ApiException ex = new ApiException(
                "An error occurred with our API", "req_500", "api_error", 500, null);
        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("ApiException with null HTTP status returns true")
    void apiExceptionNullStatus_returnsTrue() {
        ApiException ex = new ApiException(
                "Unclassified API failure", "req_null", null, null, null);
        assertThat(predicate.test(ex)).isTrue();
    }

    @Test
    @DisplayName("RuntimeException caused by an IOException (with no StripeException) returns true")
    void runtimeExceptionCausedByIoException_returnsTrue() {
        RuntimeException wrapped = new RuntimeException("transport wrapper", new IOException("connection reset"));
        assertThat(predicate.test(wrapped)).isTrue();
    }

    @Test
    @DisplayName("First StripeException in cause chain decides even if an IOException sits deeper")
    void firstStripeExceptionInChainDecides() {
        InvalidRequestException stripe400 = new InvalidRequestException(
                "Bad request", "param", "req_400", "invalid_request", 400, new IOException("ignored deeper cause"));
        RuntimeException outer = new RuntimeException("outer wrapper", stripe400);
        assertThat(predicate.test(outer)).isFalse();
    }
}
