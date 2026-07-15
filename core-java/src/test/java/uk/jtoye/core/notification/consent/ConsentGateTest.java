package uk.jtoye.core.notification.consent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit test for the may-we-send gate (COMMS-03, D-03). Mocks
 * {@link SuppressionService} so the gate's policy logic is exercised in
 * isolation: transactional categories default-on with per-category suppression;
 * marketing requires explicit opt-in.
 */
@ExtendWith(MockitoExtension.class)
class ConsentGateTest {

    @Mock
    private SuppressionService suppressionService;

    private ConsentGate consentGate;

    private final UUID tenant = UUID.randomUUID();
    private final String recipient = "customer@example.com";

    private ConsentGate gate() {
        if (consentGate == null) {
            consentGate = new ConsentGate(suppressionService);
        }
        return consentGate;
    }

    @Test
    void transactional_allowedWhenNotSuppressed() {
        when(suppressionService.isSuppressed(tenant, recipient, NotificationCategory.ORDERS)).thenReturn(false);
        assertThat(gate().allows(tenant, recipient, NotificationCategory.ORDERS)).isTrue();
    }

    @Test
    void transactional_refusedWhenSuppressed() {
        when(suppressionService.isSuppressed(tenant, recipient, NotificationCategory.ORDERS)).thenReturn(true);
        assertThat(gate().allows(tenant, recipient, NotificationCategory.ORDERS)).isFalse();
    }

    @Test
    void marketing_refusedWithNoOptIn() {
        when(suppressionService.isSuppressed(tenant, recipient, NotificationCategory.MARKETING)).thenReturn(false);
        when(suppressionService.hasMarketingOptIn(tenant, recipient)).thenReturn(false);
        assertThat(gate().allows(tenant, recipient, NotificationCategory.MARKETING)).isFalse();
    }

    @Test
    void marketing_allowedWithOptInAndNoSuppression() {
        when(suppressionService.isSuppressed(tenant, recipient, NotificationCategory.MARKETING)).thenReturn(false);
        when(suppressionService.hasMarketingOptIn(tenant, recipient)).thenReturn(true);
        assertThat(gate().allows(tenant, recipient, NotificationCategory.MARKETING)).isTrue();
    }

    @Test
    void marketing_refusedWhenSuppressedEvenWithOptIn() {
        when(suppressionService.isSuppressed(tenant, recipient, NotificationCategory.MARKETING)).thenReturn(true);
        assertThat(gate().allows(tenant, recipient, NotificationCategory.MARKETING)).isFalse();
    }
}
