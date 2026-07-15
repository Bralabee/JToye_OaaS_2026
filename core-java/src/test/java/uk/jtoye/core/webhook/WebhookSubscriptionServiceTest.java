package uk.jtoye.core.webhook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.webhook.dto.CreateWebhookSubscriptionRequest;
import uk.jtoye.core.webhook.dto.WebhookSubscriptionDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit proof of the webhook-subscription lifecycle (COMMS-04): SecureRandom secret
 * rotation invalidates the old secret, and a non-HTTPS target is rejected before
 * persist. Uses a mocked repository and the REAL {@link WebhookUrlValidator} so
 * the scheme check is exercised for real.
 */
class WebhookSubscriptionServiceTest {

    private static final String OLD_SECRET = "OLD-SECRET-VALUE-do-not-keep";

    private WebhookSubscriptionRepository repository;
    private WebhookSubscriptionService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookSubscriptionRepository.class);
        service = new WebhookSubscriptionService(repository, new WebhookUrlValidator(true));
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId);
        when(repository.save(any(WebhookSubscription.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rotateSecret_replacesStoredSecretSoOldSignaturesStopVerifying() {
        UUID id = UUID.randomUUID();
        WebhookSubscription existing = new WebhookSubscription();
        existing.setTenantId(tenantId);
        existing.setTargetUrl("https://93.184.216.34/hook");
        existing.setEventTypes(List.of(WebhookEventType.ORDER_STATE_CHANGED.name()));
        existing.setSigningSecret(OLD_SECRET);
        existing.setStatus(WebhookSubscription.Status.ACTIVE);
        when(repository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.of(existing));

        WebhookSubscriptionDto.WithSecret rotated = service.rotateSecret(id);

        assertThat(rotated.signingSecret())
                .as("rotate returns a brand-new plaintext secret")
                .isNotBlank()
                .isNotEqualTo(OLD_SECRET);
        assertThat(existing.getSigningSecret())
                .as("the stored secret is replaced — the old value is gone")
                .isNotEqualTo(OLD_SECRET)
                .isEqualTo(rotated.signingSecret());
    }

    @Test
    void create_generatesASecretAndPersistsActive() {
        CreateWebhookSubscriptionRequest req = new CreateWebhookSubscriptionRequest();
        req.setTargetUrl("https://93.184.216.34/hook");
        req.setEventTypes(List.of(WebhookEventType.ORDER_STATE_CHANGED, WebhookEventType.ORDER_REFUNDED));

        WebhookSubscriptionDto.WithSecret created = service.create(req);

        assertThat(created.signingSecret()).as("secret shown once on create").isNotBlank();
        assertThat(created.subscription().status()).isEqualTo("ACTIVE");
        assertThat(created.subscription().eventTypes())
                .containsExactly(WebhookEventType.ORDER_STATE_CHANGED, WebhookEventType.ORDER_REFUNDED);
    }

    @Test
    void create_rejectsNonHttpsTargetBeforePersist() {
        CreateWebhookSubscriptionRequest req = new CreateWebhookSubscriptionRequest();
        req.setTargetUrl("http://insecure.example.com/hook");
        req.setEventTypes(List.of(WebhookEventType.ORDER_STATE_CHANGED));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
