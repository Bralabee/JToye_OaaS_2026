package uk.jtoye.core.notification.consent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-crypto unit test for the stateless HMAC unsubscribe token (COMMS-03,
 * threat T-22-02-01). No Spring context — the service is constructed directly
 * with a test signing secret.
 */
class UnsubscribeTokenServiceTest {

    private static final String SECRET = "test-unsubscribe-signing-secret-0123456789";
    private final UnsubscribeTokenService service = new UnsubscribeTokenService(SECRET);

    private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String email = "customer@example.com";

    @Test
    void roundTrip_verifiesTrue() {
        String token = service.tokenFor(tenant, email, NotificationCategory.ORDERS);
        assertThat(service.verify(tenant, email, NotificationCategory.ORDERS, token)).isTrue();
    }

    @Test
    void tokenIsCategoryScoped_verifyWithOtherCategoryFails() {
        String ordersToken = service.tokenFor(tenant, email, NotificationCategory.ORDERS);
        assertThat(service.verify(tenant, email, NotificationCategory.MARKETING, ordersToken))
                .as("a token minted for ORDERS must not unsubscribe MARKETING")
                .isFalse();
    }

    @Test
    void tokenFor_isDeterministicForSameInputs() {
        assertThat(service.tokenFor(tenant, email, NotificationCategory.ORDERS))
                .isEqualTo(service.tokenFor(tenant, email, NotificationCategory.ORDERS));
    }

    @Test
    void tokenFor_differsAcrossCategories() {
        assertThat(service.tokenFor(tenant, email, NotificationCategory.ORDERS))
                .isNotEqualTo(service.tokenFor(tenant, email, NotificationCategory.MARKETING));
    }

    @Test
    void singleCharFlippedToken_verifiesFalse() {
        String token = service.tokenFor(tenant, email, NotificationCategory.ORDERS);
        char[] chars = token.toCharArray();
        chars[0] = (chars[0] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThat(tampered).isNotEqualTo(token);
        assertThat(service.verify(tenant, email, NotificationCategory.ORDERS, tampered)).isFalse();
    }

    @Test
    void nullToken_verifiesFalse() {
        assertThat(service.verify(tenant, email, NotificationCategory.ORDERS, null)).isFalse();
    }
}
