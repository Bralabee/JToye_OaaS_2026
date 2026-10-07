package uk.jtoye.core.webhook;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.jtoye.core.boot4.GoldenSamples;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.testsupport.BootJsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The outbound webhook envelope vendors receive, on Jackson 3 (38-07, T-38-19).
 *
 * <p>Vendors parse this JSON and verify an HMAC over its exact bytes. The envelope is produced
 * by driving the REAL {@link WebhookFanoutListener} — its order-event handler, its envelope
 * construction, its single serialization with Boot's Jackson-3 {@code JsonMapper} — and
 * capturing the {@code PENDING} delivery row it saves; the worker signs and POSTs exactly that
 * {@code payload}. It is compared with the fixture captured on Boot 3.5.16 by the production
 * Jackson-2 serializer ({@code jackson2-golden/responses/WebhookEventEnvelope.json}, 38-01).
 *
 * <p>The envelope {@code id} is generated per event, so it is checked to be the delivery row's
 * {@code eventId} and then set to the fixture's id; every other member must match as captured.
 *
 * <p>Repositories, the transaction manager and the Hibernate session are mocks: this is the
 * serialization contract, not the RLS write (that is {@code WebhookDeliveryWorkerIntegrationTest}).
 */
class WebhookFanoutListenerEnvelopeTest {

    private static final String FIXTURE = "jackson2-golden/responses/WebhookEventEnvelope.json";
    private static final JsonMapper BOOT = BootJsonMapper.get();

    private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    @BeforeEach
    void setUp() {
        when(entityManager.unwrap(Session.class)).thenReturn(mock(Session.class));
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        WebhookSubscription subscription = new WebhookSubscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setTenantId(GoldenSamples.TENANT_ID);
        subscription.setEventTypes(List.of(WebhookEventType.ORDER_STATE_CHANGED.name()));
        subscription.setStatus(WebhookSubscription.Status.ACTIVE);
        when(subscriptions.findByTenantId(GoldenSamples.TENANT_ID)).thenReturn(List.of(subscription));
    }

    private WebhookFanoutListener listener(JsonMapper mapper) {
        return new WebhookFanoutListener(subscriptions, deliveries, new WebhookProperties(), mapper,
                entityManager, transactionManager);
    }

    /** Drives the listener with the golden order event and returns the one saved delivery row. */
    private WebhookDelivery fanOutTheGoldenOrderEvent(JsonMapper mapper) {
        OrderStateChangeEvent event = GoldenSamples.orderStateChangeEvent();
        listener(mapper).onOrderState(event);
        ArgumentCaptor<WebhookDelivery> saved = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveries).save(saved.capture());
        return saved.getValue();
    }

    private static String fixture() throws IOException {
        try (InputStream in = WebhookFanoutListenerEnvelopeTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture on the test classpath: %s", FIXTURE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    /** The delivered payload with its per-event id replaced by the fixture's, after checking it. */
    private static JsonNode withFixtureId(WebhookDelivery delivery) {
        JsonNode delivered = BOOT.readTree(delivery.getPayload());
        assertThat(delivered.path("id").asString())
                .as("envelope id is the delivery row's eventId")
                .isEqualTo(delivery.getEventId().toString());
        ObjectNode normalized = ((ObjectNode) delivered).deepCopy();
        normalized.put("id", GoldenSamples.EVENT_ID.toString());
        return normalized;
    }

    @Test
    void envelopeVendorsReceive_isTreeEqualToTheJackson2Fixture() throws IOException {
        WebhookDelivery delivery = fanOutTheGoldenOrderEvent(BOOT);

        assertThat(delivery.getEventType()).isEqualTo("order.confirmed");
        assertThat(withFixtureId(delivery))
                .as("delivered envelope (id normalised) vs the Jackson-2 fixture; delivered=%s", delivery.getPayload())
                .isEqualTo(BOOT.readTree(fixture()));
    }

    /**
     * Stronger than tree equality, because the vendor's HMAC covers bytes: apart from the
     * per-event id the delivered string is the Jackson-2 bytes exactly (the envelope and the
     * event are records, which keep declaration order under Jackson 3 — measured by 38-05).
     */
    @Test
    void envelopeBytes_matchTheJackson2FixtureApartFromTheEventId() throws IOException {
        WebhookDelivery delivery = fanOutTheGoldenOrderEvent(BOOT);

        String delivered = delivery.getPayload().replace(delivery.getEventId().toString(),
                GoldenSamples.EVENT_ID.toString());
        assertThat(delivered).isEqualTo(fixture());
    }

    /**
     * Negative control: the tree comparison catches a realistic mapper drift. The same listener
     * with Boot's mapper rebuilt to write dates as timestamps delivers {@code occurredAt} as a
     * number, and the envelope is no longer equal to the fixture.
     */
    @Test
    void negativeControl_aMapperWritingDatesAsTimestamps_isNotTreeEqual() throws IOException {
        JsonMapper timestamps = BOOT.rebuild().enable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();

        WebhookDelivery delivery = fanOutTheGoldenOrderEvent(timestamps);

        JsonNode normalized = withFixtureId(delivery);
        assertThat(normalized.path("occurredAt").isNumber()).as("payload=%s", delivery.getPayload()).isTrue();
        assertThat(normalized).isNotEqualTo(BOOT.readTree(fixture()));
    }

    /**
     * The serialize-failure path keeps its Jackson-2 behaviour: the failure is logged and that
     * event's fan-out is skipped — no delivery row, and nothing thrown out of the
     * {@code @RabbitListener}. On Jackson 2 this was a {@code catch (JsonProcessingException)}
     * the compiler demanded; on Jackson 3 the exception is unchecked, so nothing forces the catch
     * and only this test notices if it goes.
     */
    @Test
    void serializationFailure_skipsTheFanout_savesNoRow_andDoesNotThrow() {
        JsonMapper failing = mock(JsonMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(InvalidDefinitionException.class);

        assertThatCode(() -> listener(failing).onOrderState(GoldenSamples.orderStateChangeEvent()))
                .doesNotThrowAnyException();
        verify(deliveries, never()).save(any());
    }
}
