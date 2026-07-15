package uk.jtoye.core.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import uk.jtoye.core.webhook.WebhookEventType;

import java.util.List;

/**
 * Request body to register a webhook subscription (COMMS-04).
 *
 * <p>{@code targetUrl} is HTTPS-gated at the DTO layer ({@code @Pattern}) for a
 * fast machine-parseable 400, and again — with full SSRF checks — by
 * {@code WebhookUrlValidator} in the service. {@code eventTypes} must select at
 * least one {@link WebhookEventType} family; unknown values are rejected by
 * Jackson enum deserialization (400).
 */
public class CreateWebhookSubscriptionRequest {

    @NotBlank(message = "targetUrl is required")
    @Pattern(regexp = "^https://.*", message = "targetUrl must be an HTTPS URL")
    private String targetUrl;

    @NotEmpty(message = "at least one event type is required")
    private List<WebhookEventType> eventTypes;

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public List<WebhookEventType> getEventTypes() {
        return eventTypes;
    }

    public void setEventTypes(List<WebhookEventType> eventTypes) {
        this.eventTypes = eventTypes;
    }
}
