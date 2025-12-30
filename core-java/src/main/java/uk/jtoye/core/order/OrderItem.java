package uk.jtoye.core.order;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Line item within an order.
 * Links products to orders with quantity and pricing at time of order.
 */
@Entity
@Table(name = "order_items")
@Audited
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price_pennies", nullable = false)
    private Long unitPricePennies;

    @Column(name = "total_price_pennies", nullable = false)
    private Long totalPricePennies;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Constructors
    public OrderItem() {
    }

    public OrderItem(UUID productId, Integer quantity, Long unitPricePennies) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPricePennies = unitPricePennies;
        this.totalPricePennies = quantity * unitPricePennies;
    }

    // Helper methods
    public void calculateTotalPrice() {
        this.totalPricePennies = this.quantity * this.unitPricePennies;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
        calculateTotalPrice();
    }

    public Long getUnitPricePennies() {
        return unitPricePennies;
    }

    public void setUnitPricePennies(Long unitPricePennies) {
        this.unitPricePennies = unitPricePennies;
        calculateTotalPrice();
    }

    public Long getTotalPricePennies() {
        return totalPricePennies;
    }

    public void setTotalPricePennies(Long totalPricePennies) {
        this.totalPricePennies = totalPricePennies;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
