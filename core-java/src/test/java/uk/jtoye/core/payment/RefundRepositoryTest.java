package uk.jtoye.core.payment;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository slice for {@link RefundRepository}.
 *
 * <p>Runs against H2 (Postgres mode) per {@code application-test.yml} —
 * Hibernate {@code ddl-auto: create-drop} generates the schema from the
 * {@link Refund} entity, so the unique constraint on
 * {@code (tenant_id, idempotency_key)} is exercised even though Flyway is
 * disabled in the test profile. RLS is NOT exercised here (H2 has no RLS) —
 * tenant isolation is verified at the integration layer in 17-03.
 */
@DataJpaTest
@ActiveProfiles("test")
class RefundRepositoryTest {

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("Save and find persists all fields including version=0 on first flush")
    void saveAndFind_persistsAllFields() {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Refund refund = new Refund(
                tenantId,
                orderId,
                "pi_test_3ABC",
                "idem_" + UUID.randomUUID().toString().replace("-", ""),
                500L,
                RefundReason.REQUESTED_BY_CUSTOMER,
                "Customer changed mind"
        );

        Refund saved = refundRepository.saveAndFlush(refund);
        UUID savedId = saved.getId();
        assertThat(savedId).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);

        // Force re-load from DB rather than first-level cache
        entityManager.clear();

        Refund reloaded = refundRepository.findById(savedId).orElseThrow();
        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getOrderId()).isEqualTo(orderId);
        assertThat(reloaded.getPaymentIntentId()).isEqualTo("pi_test_3ABC");
        assertThat(reloaded.getIdempotencyKey()).isEqualTo(refund.getIdempotencyKey());
        assertThat(reloaded.getAmountPennies()).isEqualTo(500L);
        assertThat(reloaded.getCurrency()).isEqualTo("gbp");
        assertThat(reloaded.getReason()).isEqualTo(RefundReason.REQUESTED_BY_CUSTOMER);
        assertThat(reloaded.getReasonNote()).isEqualTo("Customer changed mind");
        assertThat(reloaded.getStatus()).isEqualTo(RefundStatus.CREATING);
        assertThat(reloaded.getRequestedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getStripeRefundId()).isNull();
        assertThat(reloaded.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("Unique constraint on (tenant_id, idempotency_key) rejects duplicate insert")
    void uniqueIdempotencyKey_rejectsDuplicate() {
        UUID tenantId = UUID.randomUUID();
        String idemKey = "idem_dup_" + UUID.randomUUID().toString().replace("-", "");

        Refund first = new Refund(
                tenantId,
                UUID.randomUUID(),
                "pi_first",
                idemKey,
                100L,
                RefundReason.DUPLICATE,
                null
        );
        refundRepository.saveAndFlush(first);

        Refund second = new Refund(
                tenantId,
                UUID.randomUUID(),
                "pi_second",
                idemKey,
                200L,
                RefundReason.DUPLICATE,
                null
        );

        assertThatThrownBy(() -> refundRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByOrderIdOrderByRequestedAtDesc returns refunds newest-first")
    void findByOrderIdOrderByRequestedAtDesc_ordersNewestFirst() {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        // Insert 3 refunds with explicit, staggered requested_at timestamps so
        // the ordering is deterministic regardless of clock resolution. We
        // override the @CreationTimestamp via setRequestedAt + saveAndFlush.
        OffsetDateTime base = OffsetDateTime.now();

        Refund oldest = saveWithTimestamp(tenantId, orderId, "k_old", base.minusMinutes(10));
        Refund middle = saveWithTimestamp(tenantId, orderId, "k_mid", base.minusMinutes(5));
        Refund newest = saveWithTimestamp(tenantId, orderId, "k_new", base);

        entityManager.flush();
        entityManager.clear();

        List<Refund> result = refundRepository.findByOrderIdOrderByRequestedAtDesc(orderId);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getId()).isEqualTo(newest.getId());
        assertThat(result.get(1).getId()).isEqualTo(middle.getId());
        assertThat(result.get(2).getId()).isEqualTo(oldest.getId());
    }

    @Test
    @DisplayName("findByTenantIdAndIdempotencyKey returns the matching refund")
    void findByTenantIdAndIdempotencyKey_returnsMatch() {
        UUID tenantId = UUID.randomUUID();
        String idemKey = "idem_lookup_" + UUID.randomUUID().toString().replace("-", "");

        Refund saved = refundRepository.saveAndFlush(new Refund(
                tenantId,
                UUID.randomUUID(),
                "pi_x",
                idemKey,
                300L,
                RefundReason.FRAUDULENT,
                null
        ));

        var found = refundRepository.findByTenantIdAndIdempotencyKey(tenantId, idemKey);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());

        // Wrong tenant must not match (defense in depth — RLS is the wall in
        // production, this app-level check protects unit-test environments)
        var notFound = refundRepository.findByTenantIdAndIdempotencyKey(UUID.randomUUID(), idemKey);
        assertThat(notFound).isEmpty();
    }

    @Test
    @DisplayName("sumLiveAmountByOrderId returns 0 for orders with no refunds")
    void sumLiveAmountByOrderId_zeroForNewOrder() {
        long sum = refundRepository.sumLiveAmountByOrderId(UUID.randomUUID());
        assertThat(sum).isZero();
    }

    @Test
    @DisplayName("sumLiveAmountByOrderId aggregates only live (non-failed/canceled) statuses")
    void sumLiveAmountByOrderId_excludesFailedAndCanceled() {
        UUID tenantId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Refund creating = saveWithStatus(tenantId, orderId, "k1", 100L, RefundStatus.CREATING);
        Refund pending = saveWithStatus(tenantId, orderId, "k2", 200L, RefundStatus.pending);
        Refund succeeded = saveWithStatus(tenantId, orderId, "k3", 400L, RefundStatus.succeeded);
        Refund failed = saveWithStatus(tenantId, orderId, "k4", 999L, RefundStatus.failed);
        Refund canceled = saveWithStatus(tenantId, orderId, "k5", 888L, RefundStatus.canceled);

        entityManager.flush();
        entityManager.clear();

        long sum = refundRepository.sumLiveAmountByOrderId(orderId);
        // CREATING + pending + succeeded counted; failed + canceled excluded.
        assertThat(sum).isEqualTo(100L + 200L + 400L);
    }

    private Refund saveWithTimestamp(UUID tenantId, UUID orderId, String idemKey, OffsetDateTime ts) {
        Refund r = new Refund(
                tenantId,
                orderId,
                "pi_ts",
                idemKey + "_" + UUID.randomUUID().toString().replace("-", ""),
                100L,
                RefundReason.REQUESTED_BY_CUSTOMER,
                null
        );
        Refund saved = refundRepository.saveAndFlush(r);
        // Override @CreationTimestamp via direct field write — JPA dirty-tracks
        // the change so the next flush persists the explicit timestamp.
        saved.setRequestedAt(ts);
        return refundRepository.saveAndFlush(saved);
    }

    private Refund saveWithStatus(UUID tenantId, UUID orderId, String idemKey, long amount, RefundStatus status) {
        Refund r = new Refund(
                tenantId,
                orderId,
                "pi_status",
                idemKey + "_" + UUID.randomUUID().toString().replace("-", ""),
                amount,
                RefundReason.REQUESTED_BY_CUSTOMER,
                null
        );
        r.setStatus(status);
        return refundRepository.saveAndFlush(r);
    }
}
