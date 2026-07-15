package uk.jtoye.core.webhook;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.webhook.dto.CreateWebhookSubscriptionRequest;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped lifecycle for {@link WebhookSubscription} (COMMS-04).
 *
 * <p>RED-phase stub — implemented in the GREEN commit.
 */
@Service
@Transactional
public class WebhookSubscriptionService {

    private final WebhookSubscriptionRepository repository;
    private final WebhookUrlValidator urlValidator;

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,
                                      WebhookUrlValidator urlValidator) {
        this.repository = repository;
        this.urlValidator = urlValidator;
    }

    public WebhookSubscriptionDto.WithSecret create(CreateWebhookSubscriptionRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Transactional(readOnly = true)
    public List<WebhookSubscriptionDto> list() {
        throw new UnsupportedOperationException("not implemented");
    }

    @Transactional(readOnly = true)
    public WebhookSubscriptionDto getById(UUID id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public WebhookSubscriptionDto.WithSecret rotateSecret(UUID id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public WebhookSubscriptionDto pause(UUID id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public WebhookSubscriptionDto resume(UUID id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public WebhookSubscriptionDto revoke(UUID id) {
        throw new UnsupportedOperationException("not implemented");
    }
}
