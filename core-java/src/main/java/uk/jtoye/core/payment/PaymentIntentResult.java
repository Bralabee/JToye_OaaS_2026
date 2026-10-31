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
 * <p><b>{@code paymentIntentId} never goes to a browser.</b> It ({@code pi_...})
 * is a server-side identifier; handing it to Stripe Elements mounts an unusable
 * value and discloses the raw id to the customer (the exact defect WR-02 fixed).
 * {@code clientSecret} and {@code stripeAccountId} are the two values a browser
 * needs, and the only two it may receive.
 *
 * <p><b>{@code stripeAccountId} (30-17, UI-SPEC § S1).</b> A DIRECT_CHARGE_VENDOR_MOR
 * or SPLIT_DEPOSIT_VENDOR_MOR intent is created on the vendor's connected account
 * (the {@code Stripe-Account} header), so Stripe.js must be initialised with
 * {@code { stripeAccount }} or {@code confirmPayment} cannot find the intent. An
 * {@code acct_...} id is public by Stripe's design: it grants nothing without the
 * platform's secret key. It is {@code null} unless the intent lives on a connected
 * account — a destination charge and a platform charge both live on the platform,
 * so a destination intent carries {@code null}, never its {@code transfer_data}
 * destination. It is never a secret and never the intent id.
 *
 * @param paymentIntentId the Stripe PaymentIntent id ({@code pi_...}) — persist
 *                        it, never return it to a client
 * @param clientSecret    the client secret ({@code pi_..._secret_...}) — for the
 *                        frontend
 * @param stripeAccountId the connected account that owns the intent, or
 *                        {@code null} for a platform-account intent — for the
 *                        frontend's Stripe.js initialisation
 */
public record PaymentIntentResult(String paymentIntentId, String clientSecret, String stripeAccountId) {
}
