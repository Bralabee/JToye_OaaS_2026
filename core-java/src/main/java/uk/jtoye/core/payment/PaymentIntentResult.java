package uk.jtoye.core.payment;

/**
 * The two values a caller needs back from a created Stripe PaymentIntent
 * (issue #538).
 *
 * <p>{@code createPaymentIntent} used to return the client secret alone, which
 * meant the PaymentIntent id was never persisted on the order. Two things broke
 * as a result:
 *
 * <ul>
 *   <li>The WR-02 idempotent-retry path in {@code PublicStorefrontService}
 *       re-fetches a client secret only when {@code order.paymentReference} is
 *       set — and nothing on the create path ever set it, so the branch was
 *       unreachable and a retried card checkout returned a null client secret.</li>
 *   <li>There was no local column linking an order row to its Stripe object
 *       until the webhook landed, so an order whose payment never completed was
 *       not reconcilable against Stripe at all.</li>
 * </ul>
 *
 * <p><b>{@code clientSecret} is the only field that may be sent to a browser.</b>
 * {@code paymentIntentId} ({@code pi_...}) is a server-side identifier; handing
 * it to Stripe Elements mounts an unusable value and discloses the raw id to the
 * customer (the exact defect WR-02 fixed).
 *
 * @param paymentIntentId the Stripe PaymentIntent id ({@code pi_...}) — persist
 *                        it, never return it to a client
 * @param clientSecret    the client secret ({@code pi_..._secret_...}) — the
 *                        only value the frontend may receive
 */
public record PaymentIntentResult(String paymentIntentId, String clientSecret) {
}
