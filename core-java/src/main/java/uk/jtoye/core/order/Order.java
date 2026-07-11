package uk.jtoye.core.order;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;
import uk.jtoye.core.finance.VatCalculator;
import uk.jtoye.core.finance.VatRate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Customer order entity with multi-tenant isolation.
 * All order data is tenant-scoped via RLS policies.
 */
@Entity
@Table(name = "orders")
@Audited
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    /**
     * Optional FK to Customer entity.
     * Nullable for backward compatibility with existing orders.
     * New orders should link to Customer when available.
     */
    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "order_number", nullable = false, length = 50)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.DRAFT;

    /**
     * Customer name (denormalized for quick access).
     * Kept for backward compatibility and performance.
     */
    @Column(name = "customer_name", length = 255)
    private String customerName;

    @Column(name = "customer_email", length = 255)
    private String customerEmail;

    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "subtotal_pennies", nullable = false)
    private Long subtotalPennies = 0L;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false, length = 20)
    private VatRate vatRate = VatRate.ZERO;

    @Column(name = "vat_amount_pennies", nullable = false)
    private Long vatAmountPennies = 0L;

    @Column(name = "delivery_fee_pennies", nullable = false)
    private Long deliveryFeePennies = 0L;

    /**
     * How this order is fulfilled (V45). DELIVERY (default) applies a delivery
     * fee and requires an address; COLLECTION forces the delivery fee to £0.
     * VARCHAR+CHECK enum in the DB, mirrored nullable into orders_aud.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_type", nullable = false, length = 20)
    private FulfilmentType fulfilmentType = FulfilmentType.DELIVERY;

    /**
     * UK delivery address (V45). Nullable — a COLLECTION order has no address.
     * Address is PII: GDPR erasure scrubs these from both orders and orders_aud.
     */
    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "address_city", length = 120)
    private String addressCity;

    @Column(name = "address_postcode", length = 12)
    private String addressPostcode;

    @Column(name = "total_amount_pennies", nullable = false)
    private Long totalAmountPennies = 0L;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.NONE;

    @Column(name = "payment_reference", length = 255)
    private String paymentReference;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Optimistic-locking version column. JPA-managed; never mutated by callers.
     * A concurrent write whose SELECT saw an older value will throw
     * {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * on save(), preventing silent last-writer-wins clobbering of stock
     * decrements and state transitions.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // Constructors
    public Order() {
        this.updatedAt = OffsetDateTime.now();
    }

    // Helper methods
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        items.remove(item);
        item.setOrder(null);
    }

    /**
     * Recompute derived totals. Prices are VAT-INCLUSIVE consumer prices
     * (Issue #81 BUG 1 fix), so VAT is the fraction contained WITHIN the total,
     * never added on top:
     * <ul>
     *   <li>{@code subtotal = Σ line.totalPricePennies} (VAT-inclusive)</li>
     *   <li>{@code total = subtotal + deliveryFee} (no VAT added on top)</li>
     *   <li>{@code vatAmount = vatFromGross(subtotal) + vatFromGross(deliveryFee)}
     *       at the order's predominant {@code vatRate}</li>
     * </ul>
     * The single {@link VatCalculator} is the source of truth; the ledger row
     * for this order re-derives the same VAT from the same rate, so order and
     * ledger agree to the penny.
     */
    public void calculateTotal() {
        this.subtotalPennies = items.stream()
                .mapToLong(OrderItem::getTotalPricePennies)
                .sum();
        this.totalAmountPennies = this.subtotalPennies + this.deliveryFeePennies;
        this.vatAmountPennies = VatCalculator.vatFromGross(this.subtotalPennies, this.vatRate)
                + VatCalculator.vatFromGross(this.deliveryFeePennies, this.vatRate);
        this.itemCount = items.size();
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

    public UUID getShopId() {
        return shopId;
    }

    public void setShopId(UUID shopId) {
        this.shopId = shopId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Long getTotalAmountPennies() {
        return totalAmountPennies;
    }

    public void setTotalAmountPennies(Long totalAmountPennies) {
        this.totalAmountPennies = totalAmountPennies;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public Long getSubtotalPennies() {
        return subtotalPennies;
    }

    public void setSubtotalPennies(Long subtotalPennies) {
        this.subtotalPennies = subtotalPennies;
    }

    public VatRate getVatRate() {
        return vatRate;
    }

    public void setVatRate(VatRate vatRate) {
        this.vatRate = vatRate;
    }

    public Long getVatAmountPennies() {
        return vatAmountPennies;
    }

    public void setVatAmountPennies(Long vatAmountPennies) {
        this.vatAmountPennies = vatAmountPennies;
    }

    public Long getDeliveryFeePennies() {
        return deliveryFeePennies;
    }

    public void setDeliveryFeePennies(Long deliveryFeePennies) {
        this.deliveryFeePennies = deliveryFeePennies;
    }

    public FulfilmentType getFulfilmentType() {
        return fulfilmentType;
    }

    public void setFulfilmentType(FulfilmentType fulfilmentType) {
        this.fulfilmentType = fulfilmentType;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressPostcode() {
        return addressPostcode;
    }

    public void setAddressPostcode(String addressPostcode) {
        this.addressPostcode = addressPostcode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    /** JPA-managed optimistic lock version. Null until the entity is flushed. */
    @Nullable
    public Long getVersion() {
        return version;
    }
}
