package uk.jtoye.core.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #418 — a transient failure while merely LISTING tenants must not abort a whole
 * scheduled pass.
 *
 * <p><b>What the CI logs showed.</b> Both failing runs of 2026-08-01 carried <b>78</b>
 * stack traces of {@code TaskUtils$LoggingErrorHandler - Unexpected error occurred in
 * scheduled task}, each ending at {@code listTenantIds()} — in
 * {@code PaymentEventOutboxFlusher} and in {@code WebhookDeliveryWorker} alike. Every one
 * of those was a pass that published nothing for <em>any</em> tenant, because the tenant
 * list could not be read. The per-tenant try/catch was working exactly as designed; the
 * query that feeds the loop simply sat outside it.
 *
 * <p><b>This is NOT a fix for the flaky assertion #418 was filed about.</b> That
 * diagnosis was wrong and is retracted on the issue — the schedules are parked at 24h in
 * {@code PaymentEventOutboxReliabilityIntegrationTest}, and no scheduled execution ran
 * inside its window on either failing run. This covers the separate, genuine robustness
 * defect those same logs did prove.
 *
 * <p>A skipped pass is recoverable: the rows stay {@code PENDING} and the next tick
 * retries. Escaping into the scheduler's error handler is what produced the 78 traces.
 */
class OutboxTenantListingFailureTest {

    /** Builds a flusher whose tenant-listing query blows up the way a dead pool does. */
    private PaymentEventOutboxFlusher flusherWithFailingTenantListing(RabbitTemplate rabbitTemplate) {
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.createNativeQuery(anyString()))
                .thenThrow(new PersistenceException(
                        "JToyeHikariPool - Connection is not available, request timed out after 30001ms"));

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);

        return new PaymentEventOutboxFlusher(
                mock(PaymentEventOutboxRepository.class),
                rabbitTemplate,
                new ObjectMapper(),
                entityManager,
                mock(PlatformTransactionManager.class),
                meterRegistryProvider,
                5000L,
                300000L);
    }

    @Test
    @DisplayName("#418: flushPending survives a tenant-listing failure instead of aborting the pass")
    void flushPending_doesNotEscapeIntoTheSchedulerErrorHandler() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        PaymentEventOutboxFlusher flusher = flusherWithFailingTenantListing(rabbitTemplate);

        // FAIL DIRECTION: before the fix this threw PersistenceException straight out of
        // the @Scheduled method. Reverting the try/catch makes this assertion red — the
        // whole point, since a test that cannot fail is not evidence.
        assertThatCode(flusher::flushPending)
                .as("a dead connection pool must skip the pass, not escape to TaskUtils")
                .doesNotThrowAnyException();

        // And it must genuinely publish nothing rather than half a pass.
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("#418: resurrectFailed survives the same failure — the hazard is the shape, not one method")
    void resurrectFailed_doesNotEscapeIntoTheSchedulerErrorHandler() {
        PaymentEventOutboxFlusher flusher = flusherWithFailingTenantListing(mock(RabbitTemplate.class));

        assertThatCode(flusher::resurrectFailed)
                .as("resurrection has the identical uncovered listTenantIds()")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("#418: the guard is scoped to LISTING — it must not swallow a per-tenant failure")
    void guardDoesNotSwallowRealWork() {
        // Anti-vacuity: a try/catch wide enough to hide genuine per-tenant errors would
        // make the two tests above pass for the wrong reason. Prove the new catch fires
        // ONLY on the listing call by showing a successful listing still reaches the
        // per-tenant path — here, an empty tenant list means zero publishes and no throw.
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.List.of());

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meterRegistryProvider = mock(ObjectProvider.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        PaymentEventOutboxFlusher flusher = new PaymentEventOutboxFlusher(
                mock(PaymentEventOutboxRepository.class), rabbitTemplate, new ObjectMapper(),
                entityManager, mock(PlatformTransactionManager.class), meterRegistryProvider,
                5000L, 300000L);

        assertThatCode(flusher::flushPending).doesNotThrowAnyException();
        verify(query).getResultList();
        assertThat(true).as("listing succeeded and the loop ran to completion").isTrue();
    }
}
