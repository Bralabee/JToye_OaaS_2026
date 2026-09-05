package uk.jtoye.core.order.dto;

import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.payment.dto.RefundDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class OrderDetailDto {
    private UUID id;
    private UUID tenantId;
    private UUID shopId;
    private String orderNumber;
    private OrderStatus status;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String notes;
    private Long totalAmountPennies;
    /**
     * COR-4 (V66): UNITS on the order — SUM(order_items.quantity). Nullable, and null means NOT
     * RECORDED (the row predates V66). Never read null as 0. This DTO deliberately carries no
     * {@code itemCount} (that LINES figure lives on the list-view {@code OrderDto}); the lines are
     * available here as {@link #items} itself, so never derive a substitute for a null unitCount
     * from them either.
     */
    private Integer unitCount;
    private List<OrderItemDto> items;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Phase 17 VOPS-01 — payment + refund history exposed for the
    // /dashboard/orders/[id] detail page. Fields are nullable: pre-Phase-17
    // orders may have no payment_status and no refunds.
    private PaymentStatus paymentStatus;
    private String paymentReference;
    private String paymentMethod;
    private List<RefundDto> refunds;

    // Phase 19 UIX-04 — fulfilment + delivery address for the
    // /dashboard/orders/[id] detail page. Nullable: pre-V45 orders default to
    // DELIVERY with no persisted address; COLLECTION orders have no address.
    private FulfilmentType fulfilmentType;
    /**
     * COR-1 (PR #726 review M6): what delivery cost, beside {@link #fulfilmentType}. The list DTO
     * gained this with COR-1; the detail DTO — the one {@code /dashboard/orders/[id]} and the
     * kitchen board actually read — did not, so a DELIVERY order's detail page could not show the
     * fee it charged. Scalar column on the order row: no extra query.
     */
    private Long deliveryFeePennies;
    private String addressLine1;
    private String addressLine2;
    private String addressCity;
    private String addressPostcode;

    // ------------------------------------------------------------------
    // LGL-03 / V63 — the order's allergen picture. This is the DTO the kitchen display actually
    // consumes (fetchKitchenBoard / GET /orders/{id}/detail), so UI-SPEC S4's banner and the
    // per-item badges are fed from here; OrderDto carries the same aggregate for list views.
    //
    // ALL THREE ARE NULL TOGETHER when the order is NOT RECORDED (it predates V63, or one of its
    // lines does). "not recorded" and "nothing declared" (mask 0, empty names) are DIFFERENT
    // statements, and S4 renders them differently: a genuinely allergen-free ticket shows no
    // banner, and a pre-migration ticket must not be allowed to claim it is allergen-free.
    // ------------------------------------------------------------------
    private Integer allergenMask;
    private List<String> allergenNames;

    /**
     * ADVISORY reconciliation lines ("CHECK: {item} — {allergen}"), carried beside the
     * declaration and never merged into it. Empty (not null) when nothing was flagged.
     */
    private List<OrderAllergenFlagDto> allergenFlags;

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public void setTotalAmountPennies(Long totalAmountPennies) { this.totalAmountPennies = totalAmountPennies; }

    public Integer getUnitCount() { return unitCount; }
    public void setUnitCount(Integer unitCount) { this.unitCount = unitCount; }

    public List<OrderItemDto> getItems() { return items; }
    public void setItems(List<OrderItemDto> items) { this.items = items; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public List<RefundDto> getRefunds() { return refunds; }
    public void setRefunds(List<RefundDto> refunds) { this.refunds = refunds; }

    public FulfilmentType getFulfilmentType() { return fulfilmentType; }
    public void setFulfilmentType(FulfilmentType fulfilmentType) { this.fulfilmentType = fulfilmentType; }

    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public void setDeliveryFeePennies(Long deliveryFeePennies) { this.deliveryFeePennies = deliveryFeePennies; }

    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }

    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }

    public String getAddressCity() { return addressCity; }
    public void setAddressCity(String addressCity) { this.addressCity = addressCity; }

    public String getAddressPostcode() { return addressPostcode; }
    public void setAddressPostcode(String addressPostcode) { this.addressPostcode = addressPostcode; }

    public Integer getAllergenMask() { return allergenMask; }
    public void setAllergenMask(Integer allergenMask) { this.allergenMask = allergenMask; }

    public List<String> getAllergenNames() { return allergenNames; }
    public void setAllergenNames(List<String> allergenNames) { this.allergenNames = allergenNames; }

    public List<OrderAllergenFlagDto> getAllergenFlags() { return allergenFlags; }
    public void setAllergenFlags(List<OrderAllergenFlagDto> allergenFlags) { this.allergenFlags = allergenFlags; }
}
