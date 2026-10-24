package uk.jtoye.core.payment;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resilience4j {@code record-failure-predicate} for the Stripe circuit breakers
 * ({@code stripe}, {@code stripe-refund}, {@code stripe-lookup} — issue #108,
 * PR #1018 review finding 1, T-30-126).
 *
 * <p>Counts only transport / provider failures toward opening a breaker:
 * <ul>
 *   <li>{@link ApiConnectionException} (connect/read timeout, socket error) → {@code true}</li>
 *   <li>{@link RateLimitException} (HTTP 429 provider throttling) → {@code true}</li>
 *   <li>{@link ApiException} → {@code true} when HTTP status is {@code null} or {@code >= 500}</li>
 *   <li>Any other {@link StripeException} ({@code CardException},
 *       {@code InvalidRequestException}, {@code IdempotencyException},
 *       {@code AuthenticationException}, {@code PermissionException}) →
 *       {@code true} only when HTTP status is non-null and {@code >= 500}</li>
 *   <li>An {@link IOException} in the cause chain with no {@link StripeException} → {@code true}</li>
 *   <li>Everything else → {@code false}</li>
 * </ul>
 */
public class StripeBreakerFailurePredicate implements Predicate<Throwable> {

    public StripeBreakerFailurePredicate() {
    }

    @Override
    public boolean test(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        boolean sawIoException = false;
        Set<Throwable> visited = new HashSet<>();
        for (Throwable cur = throwable; cur != null && visited.add(cur); cur = cur.getCause()) {
            if (cur instanceof StripeException stripeEx) {
                Integer status = stripeEx.getStatusCode();
                if (stripeEx instanceof ApiConnectionException || stripeEx instanceof RateLimitException) {
                    return true;
                }
                if (stripeEx instanceof ApiException) {
                    return status == null || status >= 500;
                }
                return status != null && status >= 500;
            }
            if (cur instanceof IOException) {
                sawIoException = true;
            }
        }
        return sawIoException;
    }
}
