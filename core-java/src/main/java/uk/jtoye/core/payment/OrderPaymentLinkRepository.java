package uk.jtoye.core.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
