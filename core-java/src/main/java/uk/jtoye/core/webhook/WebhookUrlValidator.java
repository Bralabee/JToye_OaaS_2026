package uk.jtoye.core.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Validates a vendor-supplied webhook {@code target_url} before it becomes an
 * egress target (COMMS-04, threat T-22-03-01 SSRF).
 *
 * <p>RED-phase stub — implemented in the GREEN commit.
 */
@Component
public class WebhookUrlValidator {

    private final boolean blockPrivateRanges;

    public WebhookUrlValidator(
            @Value("${webhook.target.block-private-ranges:true}") boolean blockPrivateRanges) {
        this.blockPrivateRanges = blockPrivateRanges;
    }

    public void validate(String url) {
        // RED: not yet implemented.
    }
}
