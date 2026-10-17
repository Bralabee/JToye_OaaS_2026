package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link OrderPaymentLink}, protected by V88 RLS policies.
 */
@Repository
public interface OrderPaymentLinkRepository extends JpaRepository<OrderPaymentLink, UUID> {

    Optional<OrderPaymentLink> findByTokenSha256(String tokenSha256);

    List<OrderPaymentLink> findByTenantIdAndOrderIdOrderByCreatedAtDesc(UUID tenantId, UUID orderId);

    boolean existsByTenantIdAndOrderId(UUID tenantId, UUID orderId);

    @Modifying
    @Query("UPDATE OrderPaymentLink l SET l.usedAt = :now WHERE l.id = :id AND l.usedAt IS NULL AND l.revokedAt IS NULL")
    int markUsedIfUnused(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
