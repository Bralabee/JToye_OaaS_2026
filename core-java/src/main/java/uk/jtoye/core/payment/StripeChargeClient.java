package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.net.RequestOptions;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;

/**
 * Thin seam over {@link Charge#retrieve} for webhook card-brand / last4 enrichment,
 * isolated behind the {@code stripe-lookup} circuit breaker (issue #108, PR #1018
 * review finding 1).
 */
@Component
public class StripeChargeClient {

    @CircuitBreaker(name = "stripe-lookup")
    public Charge retrieve(String chargeId, String stripeAccountOrNull) throws StripeException {
        if (stripeAccountOrNull != null && !stripeAccountOrNull.isBlank()) {
            return Charge.retrieve(
                    chargeId,
                    RequestOptions.builder().setStripeAccount(stripeAccountOrNull).build());
        }
        return Charge.retrieve(chargeId);
    }
}
