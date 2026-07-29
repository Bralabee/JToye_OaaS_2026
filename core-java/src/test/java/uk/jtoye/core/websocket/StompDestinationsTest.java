package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---------------------------------------------------------------------------------
    // 27-04 T6 — the construction-time guard, both arms.
    //
    // The reject arm calls assertPublishable DIRECTLY, which is why that method is
    // package-private. Driving only the public kitchen(UUID, UUID) API could never reach
    // it: a UUID's toString() cannot contain '/', so the guard would be observed passing
    // and never proven capable of failing — the exact vacuity this phase exists to catch.
    // ---------------------------------------------------------------------------------

    @Test
    void assertPublishableAcceptsTheShapeTheBuilderProduces() {
        String built = StompDestinations.kitchen(TENANT, SHOP);

        assertThatCode(() -> StompDestinations.assertPublishable(built))
                .as("the builder's own output must survive its own guard")
                .doesNotThrowAnyException();
        assertThat(StompDestinations.assertPublishable(built))
                .as("the guard returns the destination so it can wrap the return expression")
                .isEqualTo(built);
    }

    @Test
    void assertPublishableRejectsASlashedRoutingKeyAndNamesIt() {
        // This is the pre-#266 shape, verbatim: it is what the relay answered with
        // "ERROR: Invalid destination" on the live broker.
        String slashed = StompDestinations.TOPIC_PREFIX
                + StompDestinations.KITCHEN_FEATURE + "/" + TENANT + "/" + SHOP;

        assertThatThrownBy(() -> StompDestinations.assertPublishable(slashed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single dot-separated segment")
                .as("the message must NAME the offending destination, or it cannot be acted on")
                .hasMessageContaining(slashed);
    }

    @Test
    void assertPublishableRejectsADestinationOutsideTheTopicPrefix() {
        assertThatThrownBy(() -> StompDestinations.assertPublishable("/queue/kitchen.x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(StompDestinations.TOPIC_PREFIX);
    }

    @Test
    void rejectionWordingMatchesTheSubscribeSideWallVerbatim() {
        // The publish-side guard and TenantChannelInterceptor's SUBSCRIBE-side rejection must
        // stay worded identically, so an operator grepping logs for one finds the other. If
        // someone reworders either, this fails and forces the pair to be updated together.
        String slashed = StompDestinations.TOPIC_PREFIX + "kitchen/" + TENANT;

        assertThatThrownBy(() -> StompDestinations.assertPublishable(slashed))
                .hasMessageContaining(
                        "Topic destinations must be a single dot-separated segment; "
                                + "'/' is not a valid routing-key character: ");
    }
}
