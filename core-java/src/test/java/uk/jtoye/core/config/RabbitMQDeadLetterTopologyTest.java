package uk.jtoye.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the dead-letter topology, INCLUDING its deliberate asymmetries (phase 27, plan 27-03).
 *
 * <p><b>Why a test rather than a comment.</b> Four dead-letter queues existed with zero consumers
 * and nobody watching; when that was finally alerted on, the first question was "which queues are
 * supposed to have a DLX and which are not". The answer lived only in the shape of the code, so a
 * future reader could "tidy up" the one queue that deliberately lacks one and break startup against
 * every existing broker. These assertions make each choice explicit and failing.
 *
 * <p>Deliberately a plain unit test: {@code new RabbitMQConfig()} with no Spring context, no broker
 * and no database — the same style as {@code RabbitMQConfigFanoutQueueTest}.
 */
class RabbitMQDeadLetterTopologyTest {

    private static final String DLX_ARG = "x-dead-letter-exchange";

    private final RabbitMQConfig config = new RabbitMQConfig();

    // =====================================================================================
    // Every queue that dead-letters must name the RIGHT exchange.
    //
    // A wrong DLX is worse than no DLX: the message is accepted, routed somewhere unexpected or
    // dropped at an unbound exchange, and the queue-depth alert on the intended DLQ stays silent
    // forever. Nothing else in the system would notice.
    // =====================================================================================

    @Test
    @DisplayName("order.state-changes dead-letters to order.events.dlx")
    void orderEventsQueueCarriesTheOrderDlx() {
        assertThat(config.orderEventsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.DLX_EXCHANGE);
    }

    @Test
    @DisplayName("payment.events dead-letters to payment.events.dlx — the money path")
    void paymentEventsQueueCarriesThePaymentDlx() {
        assertThat(config.paymentEventsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.PAYMENT_EVENTS_DLX);
    }

    @Test
    @DisplayName("webhook.deliveries dead-letters to webhook.deliveries.dlx")
    void webhookDeliveriesQueueCarriesTheWebhookDlx() {
        assertThat(config.webhookDeliveriesQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.WEBHOOK_DELIVERIES_DLX);
    }

    @Test
    @DisplayName("media.process dead-letters to media.events.dlx")
    void mediaEventsQueueCarriesTheMediaDlx() {
        assertThat(config.mediaEventsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.MEDIA_EVENTS_DLX);
    }

    @Test
    @DisplayName("the three notification queues dead-letter to their domain's DLX")
    void notificationQueuesCarryTheirDomainDlx() {
        assertThat(config.orderNotificationsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.DLX_EXCHANGE);
        assertThat(config.paymentNotificationsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.PAYMENT_EVENTS_DLX);
        assertThat(config.refundNotificationsQueue().getArguments())
                .containsEntry(DLX_ARG, RabbitMQConfig.DLX_EXCHANGE);
    }

    // =====================================================================================
    // A DLQ that dead-letters is a loop.
    // =====================================================================================

    @Test
    @DisplayName("no DLQ carries a DLX of its own — a dead-letter queue that dead-letters is a loop")
    void deadLetterQueuesDoNotThemselvesDeadLetter() {
        for (Queue dlq : new Queue[]{
                config.deadLetterQueue(),
                config.paymentDeadLetterQueue(),
                config.webhookDeliveriesDeadLetterQueue(),
                config.mediaDeadLetterQueue()}) {
            assertThat(dlq.getArguments())
                    .as("%s must not carry %s: a DLQ whose messages dead-letter again is a routing "
                            + "loop with no terminal state", dlq.getName(), DLX_ARG)
                    .doesNotContainKey(DLX_ARG);
            assertThat(dlq.isDurable())
                    .as("%s must be durable — a dead letter that does not survive a broker restart "
                            + "is a lost event, which is the failure this whole layer exists to stop",
                            dlq.getName())
                    .isTrue();
        }
    }

    // =====================================================================================
    // THE DELIBERATE ABSENCE. This is the assertion this class exists for.
    // =====================================================================================

    @Test
    @DisplayName("onboarding.notifications has NO DLX, and that is deliberate — adding one breaks startup")
    void onboardingNotificationsDeliberatelyHasNoDeadLetterExchange() {
        // x-dead-letter-exchange is a queue ARGUMENT. Redeclaring an existing durable queue with
        // different arguments returns PRECONDITION_FAILED (406) and kills the declaring channel, so
        // "just add a DLX" breaks boot against EVERY broker that already holds this queue —
        // including every developer machine and every deployed environment. Migrating it needs a new
        // queue name, a rebind and a drain, which is recorded in the phase's deferred-items.md.
        //
        // The visibility gap this leaves is closed a different way: retry exhaustion is counted at
        // the interceptor (jtoye_amqp_retries_exhausted_total), which fires whether or not a
        // dead-letter exchange exists downstream. See RabbitMQRetryExhaustedCounterTest.
        assertThat(config.onboardingNotificationsQueue().getArguments())
                .as("this ABSENCE is deliberate — see the comment above before 'fixing' it; the "
                        + "406 it would cause is a startup failure, not a test failure")
                .doesNotContainKey(DLX_ARG);
    }
}
