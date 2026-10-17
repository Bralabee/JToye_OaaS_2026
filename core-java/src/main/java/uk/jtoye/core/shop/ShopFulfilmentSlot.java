package uk.jtoye.core.shop;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * ADR-0007 / GAP-GTM-02: Capacity-bounded scheduled fulfilment slot for pre-orders,
 * batch drops (e.g. Saturday 2L bowls), and catering windows.
 *
 * <p>Tenant-scoped under {@code ENABLE + FORCE ROW LEVEL SECURITY} in {@code V87}.
 */
@Entity
@Table(name = "shop_fulfilment_slots")
public class ShopFulfilmentSlot {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "window_start", nullable = false)
    private LocalTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalTime windowEnd;

    @Column(name = "cutoff_at", nullable = false)
    private OffsetDateTime cutoffAt;

    @Column(name = "max_orders")
    private Integer maxOrders;

    @Column(name = "max_units")
    private Integer maxUnits;

    @Column(name = "reserved_orders", nullable = false)
    private int reservedOrders = 0;

    @Column(name = "reserved_units", nullable = false)
    private int reservedUnits = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    /**
     * Formats the immutable human-readable window label snapshotted onto
     * {@code orders.scheduled_window_label} (e.g. {@code "2026-10-24 14:00-18:00"}).
     */
    public String formatWindowLabel() {
        return serviceDate + " " + HH_MM.format(windowStart) + "-" + HH_MM.format(windowEnd);
    }

    /** Remaining order capacity, or {@code null} when unbounded. */
    public Integer remainingOrders() {
        return maxOrders == null ? null : Math.max(0, maxOrders - reservedOrders);
    }

    /** Remaining unit capacity, or {@code null} when unbounded. */
    public Integer remainingUnits() {
        return maxUnits == null ? null : Math.max(0, maxUnits - reservedUnits);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }
    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
    public LocalTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalTime windowStart) { this.windowStart = windowStart; }
    public LocalTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalTime windowEnd) { this.windowEnd = windowEnd; }
    public OffsetDateTime getCutoffAt() { return cutoffAt; }
    public void setCutoffAt(OffsetDateTime cutoffAt) { this.cutoffAt = cutoffAt; }
    public Integer getMaxOrders() { return maxOrders; }
    public void setMaxOrders(Integer maxOrders) { this.maxOrders = maxOrders; }
    public Integer getMaxUnits() { return maxUnits; }
    public void setMaxUnits(Integer maxUnits) { this.maxUnits = maxUnits; }
    public int getReservedOrders() { return reservedOrders; }
    public void setReservedOrders(int reservedOrders) { this.reservedOrders = reservedOrders; }
    public int getReservedUnits() { return reservedUnits; }
    public void setReservedUnits(int reservedUnits) { this.reservedUnits = reservedUnits; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
