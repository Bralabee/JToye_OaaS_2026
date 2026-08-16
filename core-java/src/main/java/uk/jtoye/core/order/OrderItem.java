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

    @Column(name = "product_name", nullable = false)
    private String productName = "Unknown Product";

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price_pennies", nullable = false)
    private Long unitPricePennies;

    @Column(name = "total_price_pennies", nullable = false)
    private Long totalPricePennies;

    /**
     * LGL-03 / V63 — the product's DECLARED UK FSA 14-bit allergen mask, snapshotted when the
     * order was placed. {@code null} means NOT RECORDED (the row predates V63); {@code 0} means
     * the vendor declared none of the 14. Those are different statements and nothing downstream
     * may collapse them — see {@link OrderAllergenSnapshot}.
     *
     * <p>A boxed {@code Integer}, not an {@code int}, precisely so the "not recorded" state has a
     * representation. Do not give this column a database DEFAULT.
     */
    @Column(name = "allergen_mask")
    private Integer allergenMask;

    /**
     * LGL-03 / V63 — the ADVISORY reconciliation result for this line: the bits the product's
     * emphasised ingredients text names but its declared mask omits
     * ({@link OrderAllergenAggregator}). Structurally separate from {@link #allergenMask} and
     * never OR-ed into it: a text heuristic must not rewrite a vendor's legally operative
     * declaration. {@code null} means not recorded; {@code 0} means nothing was flagged.
     */
    @Column(name = "allergen_flag_mask")
    private Integer allergenFlagMask;

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

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
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

    public Integer getAllergenMask() {
        return allergenMask;
    }

    public void setAllergenMask(Integer allergenMask) {
        this.allergenMask = allergenMask;
    }

    public Integer getAllergenFlagMask() {
        return allergenFlagMask;
    }

    public void setAllergenFlagMask(Integer allergenFlagMask) {
        this.allergenFlagMask = allergenFlagMask;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
