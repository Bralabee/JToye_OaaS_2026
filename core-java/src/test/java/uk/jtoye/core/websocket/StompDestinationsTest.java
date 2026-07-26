package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the broker constraint behind #266 at its source.
 *
 * <p>{@link StompDestinations} is the one place a destination is built, so the invariant that
 * makes the relay work — <b>no {@code '/'} after the {@code /topic/} prefix</b> — is asserted
 * here rather than inferred from a passing end-to-end run. Verified against the live RabbitMQ
 * STOMP plugin on 2026-07-26: the slashed form draws {@code ERROR: Invalid destination}, the
 * shape produced here draws a RECEIPT.
 */
class StompDestinationsTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SHOP = UUID.fromString("97d95aa4-f6e8-4bb6-b9ad-525e49c61ef6");

    @Test
    void kitchenDestinationCarriesNoSlashAfterThePrefix() {
        String destination = StompDestinations.kitchen(TENANT, SHOP);

        String routingKey = destination.substring(StompDestinations.TOPIC_PREFIX.length());
        assertThat(routingKey)
                .as("everything after /topic/ is an AMQP routing key and may not contain '/'")
                .doesNotContain("/");
    }

    @Test
    void kitchenDestinationIsPrefixedFeatureTenantShop() {
        String destination = StompDestinations.kitchen(TENANT, SHOP);

        assertThat(destination).isEqualTo("/topic/kitchen." + TENANT + "." + SHOP);
    }

    @Test
    void tenantIsTheSecondWordSoTheTenantWallCanFindIt() {
        String routingKey = StompDestinations.kitchen(TENANT, SHOP)
                .substring(StompDestinations.TOPIC_PREFIX.length());

        String[] words = routingKey.split("\\.", -1);

        assertThat(words[StompDestinations.FEATURE_WORD]).isEqualTo(StompDestinations.KITCHEN_FEATURE);
        assertThat(words[StompDestinations.TENANT_WORD]).isEqualTo(TENANT.toString());
        assertThat(words[StompDestinations.SHOP_WORD]).isEqualTo(SHOP.toString());
    }

    @Test
    void uuidsContributeNoSeparatorSoWordPositionsAreStable() {
        // A UUID is hex + '-', never '.', so the word indices above cannot drift with values.
        assertThat(TENANT.toString()).doesNotContain(StompDestinations.SEPARATOR);
        assertThat(SHOP.toString()).doesNotContain(StompDestinations.SEPARATOR);
    }
}
