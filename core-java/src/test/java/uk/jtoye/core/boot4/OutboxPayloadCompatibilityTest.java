package uk.jtoye.core.boot4;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.config.RabbitMQConfig;
import uk.jtoye.core.media.MediaEventOutbox;
import uk.jtoye.core.media.MediaEventOutboxFlusher;
import uk.jtoye.core.media.MediaEventOutboxRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.payment.PaymentEventOutbox;
import uk.jtoye.core.payment.PaymentEventOutboxFlusher;
import uk.jtoye.core.payment.PaymentEventOutboxRepository;
import uk.jtoye.core.testsupport.BootJsonMapper;

import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PENDING outbox rows survive the Boot 4 deploy, in both directions of a rolling update (Phase 38,
 * plan 38-08, BOOT4-06, the D-01 hazard one hop before the broker).
 *
 * <p>DELIBERATE-JACKSON2: emulates Boot-3.5 pods during a rolling deploy
 *
 * <p><b>Boot 3.5 -> Boot 4.</b> A row's payload is the 38-01 {@code jackson2-golden/outbox}
 * fixture, the bytes {@code objectMapper.writeValueAsString(event)} produced on Boot 3.5. The
 * payload's OWNING flusher ({@link PaymentEventOutboxFlusher} for the order, payment, refund and
 * onboarding families; {@link MediaEventOutboxFlusher} for media), built with Boot's Jackson-3
 * {@code JsonMapper}, claims it from a mocked repository. The object it hands to
 * {@code rabbitTemplate.convertAndSend} must be the {@link GoldenSamples} event, and the row must
 * be marked SENT, not poisoned. Nothing is exposed for the test: this is each flusher's own read
 * path.
 *
 * <p><b>Boot 4 -> Boot 3.5.</b> Each sample is written by the mapper the Boot-4 publishers are
 * injected with (Boot's Jackson-3 {@code JsonMapper}) and read by a Jackson-2 {@code ObjectMapper}
 * built the way Boot 3.5 built its bean: {@code Jackson2ObjectMapperBuilder} (well-known modules,
 * so JavaTime; FAIL_ON_UNKNOWN_PROPERTIES off) plus Boot's WRITE_DATES_AS_TIMESTAMPS-off default.
 * A Boot-3.5 flusher still running mid-deploy reads with exactly that bean. The Jackson-2 mapper
 * is deliberate cross-version test code here, never production code.
 *
 * <p><b>Unreadable rows.</b> A truncated copy of each fixture must take its flusher's poison
 * branch (FAILED, poisoned, nothing published, no exception out of the scheduled method), never
 * the transient-retry branch: under Jackson 3 the read failure is an unchecked
 * {@code JacksonException}, and only an explicit catch keeps it out of the retry loop.
 */
class OutboxPayloadCompatibilityTest {

    private static final UUID TENANT = GoldenSamples.TENANT_ID;
    private static final String MEDIA = "MediaProcessingEvent";

    /** The Boot-3.5 pods' ObjectMapper bean, as JacksonAutoConfiguration built it on Boot 3.5.16. */
    @SuppressWarnings({"deprecation", "removal"})
    private static final ObjectMapper BOOT35_MAPPER = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .build();

    static Stream<String> names() {
        return InFlightFixtures.names();
    }

    // ------------------------------------------------------------------ driving the flushers

    /** Outcome of one flusher pass over one row: what was published (null if nothing) and the saved row's state. */
    private record Flushed(Object event, String status, boolean poison, String lastError) {
        void assertSent() {
            assertEquals("SENT", status, "row status, lastError=" + lastError);
            assertFalse(poison, "a Boot-3.5 row must not be poisoned on Boot 4");
        }

        void assertPoisoned() {
            assertEquals("FAILED", status, "an unreadable row must flip FAILED, lastError=" + lastError);
            assertTrue(poison, "an unreadable row must be poisoned so resurrection never re-leases it");
            assertTrue(lastError != null && lastError.startsWith("payload deserialization failed"),
                    "lastError was: " + lastError);
            assertEquals(null, event, "nothing may be published for an unreadable row");
        }
    }

    /** Routes a payload to its owning flusher, as the publishers' exchange/routing key would. */
    private static Flushed flush(String name, String payload, JsonMapper mapper) {
        return MEDIA.equals(name) ? flushMedia(payload, mapper) : flushPaymentFamily(name, payload, mapper);
    }

    private static Flushed flushPaymentFamily(String name, String payload, JsonMapper mapper) {
        String exchange;
        String routingKey;
        switch (name) {
            case "OrderStateChangeEvent" -> { exchange = RabbitMQConfig.ORDER_EVENTS_EXCHANGE; routingKey = "order.state.confirmed"; }
            case "OrderStateChangeEvent-offset" -> { exchange = RabbitMQConfig.ORDER_EVENTS_EXCHANGE; routingKey = "order.state.preparing"; }
            case "PaymentEvent" -> { exchange = RabbitMQConfig.PAYMENT_EVENTS_EXCHANGE; routingKey = "payment.succeeded"; }
            case "RefundEvent" -> { exchange = RabbitMQConfig.ORDER_EVENTS_EXCHANGE; routingKey = "order.refunded"; }
            case "OnboardingStateChangeEvent" -> { exchange = RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE; routingKey = "onboarding.state.manual_review"; }
            default -> throw new IllegalArgumentException("no payment-family route for " + name);
        }
        PaymentEventOutbox row = new PaymentEventOutbox(TENANT, name, routingKey, payload, exchange);

        PaymentEventOutboxRepository repository = mock(PaymentEventOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.claimPendingBatch(anyInt())).thenReturn(List.of(row));
        new PaymentEventOutboxFlusher(repository, rabbitTemplate, mapper, tenantListing(),
                mock(PlatformTransactionManager.class), meters(), 5_000L, 300_000L).flushPending();

        Object event = published(rabbitTemplate, exchange, routingKey);
        ArgumentCaptor<PaymentEventOutbox> saved = ArgumentCaptor.forClass(PaymentEventOutbox.class);
        verify(repository).save(saved.capture());
        PaymentEventOutbox s = saved.getValue();
        return new Flushed(event, s.getStatus().name(), s.isPoison(), s.getLastError());
    }

    private static Flushed flushMedia(String payload, JsonMapper mapper) {
        MediaEventOutbox row = new MediaEventOutbox(TENANT, GoldenSamples.ASSET_ID, payload);

        MediaEventOutboxRepository repository = mock(MediaEventOutboxRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.claimPendingBatch(anyInt())).thenReturn(List.of(row));
        new MediaEventOutboxFlusher(repository, rabbitTemplate, mapper, tenantListing(),
                mock(PlatformTransactionManager.class), meters(), 5_000L, 300_000L).flushPending();

        Object event = published(rabbitTemplate, RabbitMQConfig.MEDIA_EVENTS_EXCHANGE, RabbitMQConfig.MEDIA_EVENTS_ROUTING_KEY);
        ArgumentCaptor<MediaEventOutbox> saved = ArgumentCaptor.forClass(MediaEventOutbox.class);
        verify(repository).save(saved.capture());
        MediaEventOutbox s = saved.getValue();
        return new Flushed(event, s.getStatus().name(), s.isPoison(), s.getLastError());
    }

    /** The object handed to convertAndSend on the expected exchange/routing key, or null if none was. */
    private static Object published(RabbitTemplate rabbitTemplate, String exchange, String routingKey) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        try {
            verify(rabbitTemplate).convertAndSend(eq(exchange), eq(routingKey), payload.capture());
            return payload.getValue();
        } catch (AssertionError none) {
            verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
            return null;
        }
    }

    private static EntityManager tenantListing() {
        EntityManager entityManager = mock(EntityManager.class);
        Query tenantQuery = mock(Query.class);
        when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        when(tenantQuery.getResultList()).thenReturn(List.of(TENANT));
        return entityManager;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> meters() {
        return mock(ObjectProvider.class);
    }

    // ----------------------------------------------------------------- Boot 3.5 -> Boot 4

    @ParameterizedTest(name = "outbox/{0} (Boot 3.5 row) -> its Boot-4 flusher -> the golden event, row SENT")
    @MethodSource("names")
    void jackson2Row_isReadByItsBoot4Flusher(String name) {
        Flushed f = flush(name, InFlightFixtures.outboxPayload(name), BootJsonMapper.get());
        f.assertSent();
        InFlightFixtures.assertSameEvent(InFlightFixtures.sample(name), f.event());
    }

    // ----------------------------------------------------------------- Boot 4 -> Boot 3.5

    @ParameterizedTest(name = "{0} written by the mapper the Boot-4 publishers inject -> a Boot-3.5 ObjectMapper -> the golden event")
    @MethodSource("names")
    void boot4Row_isReadByABoot35Mapper(String name) throws Exception {
        Object sample = InFlightFixtures.sample(name);
        String row = BootJsonMapper.get().writeValueAsString(sample);
        Object read = BOOT35_MAPPER.readValue(row, sample.getClass());
        InFlightFixtures.assertSameEvent(sample, read);
    }

    // ----------------------------------------------------------------- unreadable rows

    @ParameterizedTest(name = "outbox/{0} truncated by one character -> its flusher poisons it, publishes nothing")
    @MethodSource("names")
    void truncatedRow_isPoisonedNotRetried(String name) {
        String payload = InFlightFixtures.outboxPayload(name);
        Flushed f = flush(name, payload.substring(0, payload.length() - 1), BootJsonMapper.get());
        f.assertPoisoned();
    }

    // ----------------------------------------------------------------- coverage and setup

    @Test
    @DisplayName("the six cases are exactly the outbox fixtures the 38-01 MANIFEST lists (none unchecked)")
    void everyOutboxFixtureIsCovered() {
        assertEquals(InFlightFixtures.manifestNames("outbox"), new TreeSet<>(InFlightFixtures.SAMPLES.keySet()));
    }

    @Test
    @DisplayName("the Boot-3.5 reader is configured as Boot 3.5 configured its bean")
    void boot35ReaderIsConfiguredLikeBoot35() {
        assertFalse(BOOT35_MAPPER.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        assertFalse(BOOT35_MAPPER.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
        assertTrue(BOOT35_MAPPER.getRegisteredModuleIds().stream().anyMatch(id -> String.valueOf(id).contains("jsr310")),
                "JavaTime support must be registered, modules were " + BOOT35_MAPPER.getRegisteredModuleIds());
    }

    @Test
    @DisplayName("negative control: the reverse check can fail — a millisecond-timestamp writer loses the nanos")
    void reverseCheck_rejectsALossyWriter() throws Exception {
        JsonMapper millis = JsonMapper.builder()
                .enable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .build();
        OrderStateChangeEvent sample = GoldenSamples.orderStateChangeEvent();
        Object read = BOOT35_MAPPER.readValue(millis.writeValueAsString(sample), OrderStateChangeEvent.class);
        assertThrows(AssertionError.class, () -> InFlightFixtures.assertSameEvent(sample, read));
    }
}
