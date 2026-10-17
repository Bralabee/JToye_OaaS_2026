package uk.jtoye.core.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShopFulfilmentSlotRepository extends JpaRepository<ShopFulfilmentSlot, UUID> {

    List<ShopFulfilmentSlot> findByShopIdOrderByServiceDateAscWindowStartAsc(UUID shopId);

    List<ShopFulfilmentSlot> findByShopIdAndActiveTrueAndCutoffAtAfterOrderByServiceDateAscWindowStartAsc(
            UUID shopId, OffsetDateTime now);

    Optional<ShopFulfilmentSlot> findByIdAndShopId(UUID id, UUID shopId);

    /**
     * Atomically reserves one order and {@code units} units on a scheduled fulfilment slot.
     *
     * <p>Uses a single-statement conditional {@code UPDATE} (ADR-0007 §2.2 / R-2) so concurrent
     * checkouts during high-demand batch drops serialize cleanly on PostgreSQL's row lock
     * without throwing spurious {@code ObjectOptimisticLockingFailureException}s while capacity
     * remains. Returns {@code 1} when reserved, or {@code 0} when the slot is missing, inactive,
     * past {@code cutoffAt}, or lacks remaining order/unit capacity.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ShopFulfilmentSlot s
               SET s.reservedOrders = s.reservedOrders + 1,
                   s.reservedUnits = s.reservedUnits + :units,
                   s.version = s.version + 1,
                   s.updatedAt = :now
             WHERE s.id = :slotId
               AND s.shopId = :shopId
               AND s.active = true
               AND s.cutoffAt > :now
               AND (s.maxOrders IS NULL OR s.reservedOrders + 1 <= s.maxOrders)
               AND (s.maxUnits IS NULL OR s.reservedUnits + :units <= s.maxUnits)
            """)
    int tryReserveCapacity(@Param("slotId") UUID slotId,
                           @Param("shopId") UUID shopId,
                           @Param("units") int units,
                           @Param("now") OffsetDateTime now);

    /**
     * Atomically releases one order and {@code units} units when a scheduled order is
     * cancelled or voided, clamped at {@code 0} so repeated or legacy calls never violate
     * {@code ck_fulfilment_slot_capacity}.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ShopFulfilmentSlot s
               SET s.reservedOrders = CASE WHEN s.reservedOrders > 0 THEN s.reservedOrders - 1 ELSE 0 END,
                   s.reservedUnits = CASE WHEN s.reservedUnits >= :units THEN s.reservedUnits - :units ELSE 0 END,
                   s.version = s.version + 1,
                   s.updatedAt = :now
             WHERE s.id = :slotId
               AND s.tenantId = :tenantId
            """)
    int releaseCapacity(@Param("slotId") UUID slotId,
                        @Param("tenantId") UUID tenantId,
                        @Param("units") int units,
                        @Param("now") OffsetDateTime now);
}
