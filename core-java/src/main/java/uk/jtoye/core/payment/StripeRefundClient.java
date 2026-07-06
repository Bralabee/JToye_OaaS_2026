package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.springframework.stereotype.Component;

/**
 * Thin seam over the static {@link Refund#create(RefundCreateParams, RequestOptions)}
 * call so unit tests can mock without bytecode-rewrite (Mockito-inline). Two reasons:
 * <ol>
 *   <li>The Stripe SDK exposes a static factory; mocking statics requires
 *       mockito-inline which adds toolchain weight. A 4-line wrapper is cheaper.</li>
 *   <li>Future enhancement: circuit breaker, retry, metrics — all wrap here
 *       without changing RefundService.</li>
 * </ol>
 */
@Component
public class StripeRefundClient {

    public Refund create(RefundCreateParams params, RequestOptions opts) throws StripeException {
        return Refund.create(params, opts);
    }
}
