package uk.jtoye.core.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public class OrderDto {
    private UUID id;
    private UUID tenantId;
    private UUID shopId;
    private String orderNumber;
    private OrderStatus status;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String notes;
    private Long subtotalPennies;
    private VatRate vatRate;
    private Long vatAmountPennies;
    private Long totalAmountPennies;
    /**
     * COR-1: how the order is fulfilled, and what delivery cost.
     *
     * <p>Both are new on the LIST DTO and both are cheap: they are scalar columns on the order
     * row itself, so unlike the allergen aggregate noted below they cost no extra query and no
     * lazy collection load. They are here because without them the vendor list could not see the
     * classification at all — which is how 4 live orders sat mis-classified as DELIVERY-with-no-
     * address without anything on screen contradicting itself.
     */
    private FulfilmentType fulfilmentType;
    private Long deliveryFeePennies;
    private Integer itemCount;
    /**
     * COR-4 (V66): UNITS on the order — SUM(order_items.quantity) — beside {@code itemCount},
     * which stays LINES. Nullable, and null means NOT RECORDED (the row predates V66). Never read
     * null as 0 and never substitute {@code itemCount} for it.
     */
    private Integer unitCount;
    private PaymentStatus paymentStatus;
    private String paymentReference;
    private String paymentMethod;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // ------------------------------------------------------------------
    // Phase 31.1 D-06/D-07: the customer's allergen ACKNOWLEDGEMENT and the channel. Unlike the
    // recorded aggregate below, these are scalar V69 columns on the order row, so the list carries
    // them at no extra query and no collection load.
    //
    // NON_NULL, deliberately (31.1-01 section 2, route (a)): OrderDto is a Phase 38 golden DTO and
    // its stored-response bytes are frozen in IdempotencyFingerprintGoldenTest, so a field written
    // as null would change them. ABSENT therefore means NOT RECORDED (a vendor order, or a row from
    // before V69). A recorded 0 is written as 0. Absent is never read as 0.
    // ------------------------------------------------------------------
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The allergen mask (AllergenCatalog bits 0..13) the customer acknowledged at checkout. "
            + "0 means they acknowledged a basket declaring none of the 14 regulated allergens. ABSENT means no "
            + "acknowledgement was recorded (placedVia VENDOR, or the order predates the acknowledgement).")
    private Integer allergenAckMask;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "When the server accepted allergenAckMask. Absent when no acknowledgement was recorded.")
    private OffsetDateTime allergenAckAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Which channel placed the order: STOREFRONT (the customer, who acknowledged the allergen "
            + "set) or VENDOR (the shop entered it; no customer acknowledgement). Absent when the order predates "
            + "the channel being recorded.", allowableValues = {"STOREFRONT", "VENDOR"})
    private String placedVia;

    public Integer getAllergenAckMask() { return allergenAckMask; }
    public void setAllergenAckMask(Integer allergenAckMask) { this.allergenAckMask = allergenAckMask; }

    public OffsetDateTime getAllergenAckAt() { return allergenAckAt; }
    public void setAllergenAckAt(OffsetDateTime allergenAckAt) { this.allergenAckAt = allergenAckAt; }

    public String getPlacedVia() { return placedVia; }
    public void setPlacedVia(String placedVia) { this.placedVia = placedVia; }

    // ------------------------------------------------------------------
    // Phase 31.1 D-15 (#812): the customer's allergy note and the shop's acknowledgement of it.
    // Scalar V73 columns on the order row, so the list carries them with no extra query.
    //
    // NON_NULL for the same golden-contract reason as the acknowledgement fields above: ABSENT
    // means no note was given, or nobody in the shop has acknowledged it yet. Never written as null
    // on this DTO.
    // ------------------------------------------------------------------
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The customer's allergy or dietary note for this order, separate from notes (delivery). "
            + "Absent when none was given.")
    private String allergyNote;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "When someone in the shop acknowledged reading allergyNote. Absent until acknowledged.")
    private OffsetDateTime allergyNoteAcknowledgedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Who in the shop acknowledged allergyNote (the authenticated principal name). Absent until "
            + "acknowledged.")
    private String allergyNoteAcknowledgedBy;

    public String getAllergyNote() { return allergyNote; }
    public void setAllergyNote(String allergyNote) { this.allergyNote = allergyNote; }

    public OffsetDateTime getAllergyNoteAcknowledgedAt() { return allergyNoteAcknowledgedAt; }
    public void setAllergyNoteAcknowledgedAt(OffsetDateTime allergyNoteAcknowledgedAt) {
        this.allergyNoteAcknowledgedAt = allergyNoteAcknowledgedAt;
    }

    public String getAllergyNoteAcknowledgedBy() { return allergyNoteAcknowledgedBy; }
    public void setAllergyNoteAcknowledgedBy(String allergyNoteAcknowledgedBy) {
        this.allergyNoteAcknowledgedBy = allergyNoteAcknowledgedBy;
    }

    // ------------------------------------------------------------------
    // LGL-03 / V63 — the order-level allergen aggregate is deliberately NOT on this DTO. It is
    // on OrderDetailDto, which is what both declared consumers read: the kitchen board
    // (OrderService.getKitchenBoard, which already batch-fetches the lines) and
    // GET /orders/{id}/detail. See OrderDetailDto for the fields and their null semantics.
    //
    // WHY NOT HERE, MEASURED RATHER THAN ASSUMED. OrderDto is the LIST view and carries no items
    // by design. The aggregate is derived from the lines, so populating it here means loading the
    // very collection this DTO exists to avoid. Probed on Testcontainers Postgres with Hibernate
    // statistics: after orderRepository.findAll(PageRequest), the items collection is
    // NOT initialised, and touching it for 7 orders cost 7 additional prepared statements —
    // exactly one SELECT per row, on four paged endpoints. That is the same 1+N shape #564 was
    // written to remove from the kitchen board.
    //
    // The "just emit null on the list path" escape is worse, not cheaper: null on these fields
    // means NOT RECORDED, so a list that simply did not load the lines would be indistinguishable
    // from an order whose allergens were never recorded — precisely the collapse this plan
    // forbids. Absent is honest; a fabricated null is not.
    // ------------------------------------------------------------------

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

    public FulfilmentType getFulfilmentType() { return fulfilmentType; }
    public void setFulfilmentType(FulfilmentType fulfilmentType) { this.fulfilmentType = fulfilmentType; }

    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public void setDeliveryFeePennies(Long deliveryFeePennies) { this.deliveryFeePennies = deliveryFeePennies; }

    public Integer getUnitCount() { return unitCount; }
    public void setUnitCount(Integer unitCount) { this.unitCount = unitCount; }

    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getSubtotalPennies() { return subtotalPennies; }
    public void setSubtotalPennies(Long subtotalPennies) { this.subtotalPennies = subtotalPennies; }

    public VatRate getVatRate() { return vatRate; }
    public void setVatRate(VatRate vatRate) { this.vatRate = vatRate; }

    public Long getVatAmountPennies() { return vatAmountPennies; }
    public void setVatAmountPennies(Long vatAmountPennies) { this.vatAmountPennies = vatAmountPennies; }
}
