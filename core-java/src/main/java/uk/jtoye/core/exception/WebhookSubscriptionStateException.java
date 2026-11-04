package uk.jtoye.core.exception;

/**
 * A webhook-subscription action that its current state does not allow (#879): a test event for an endpoint that
 * is not ACTIVE, or a delete of one that has not been revoked.
 *
 * <p>Maps to HTTP 409 via {@code GlobalExceptionHandler.handleWebhookSubscriptionState} with the stable type
 * {@code https://jtoye.uk/errors/webhook-subscription-state}, code {@code WEBHOOK_SUBSCRIPTION_STATE} and the
 * property {@code subscriptionStatus} ({@code status} stays the HTTP code). The detail names the next action.
 */
public class WebhookSubscriptionStateException extends RuntimeException {

    private final String subscriptionStatus;

    public WebhookSubscriptionStateException(String message, String subscriptionStatus) {
        super(message);
        this.subscriptionStatus = subscriptionStatus;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }
}
