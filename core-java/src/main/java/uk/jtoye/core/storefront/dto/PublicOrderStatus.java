package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;

import java.time.OffsetDateTime;
import java.util.List;

public class PublicOrderStatus {
    private String orderNumber;
    private String status;
    private String paymentStatus;
    private String shopName;
    private Long subtotalPennies;
    private String vatRate;
    private Long vatAmountPennies;
    private Long totalAmountPennies;
    private int itemCount;
    /**
     * COR-4 (V66): UNITS on the order — SUM(order_items.quantity) — beside {@code itemCount},
     * which stays LINES. Nullable, and null means NOT RECORDED (the row predates V66). Never read
     * null as 0 and never substitute {@code itemCount} for it.
     */
    private Integer unitCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // ------------------------------------------------------------------
    // Phase 31.1 D-07/D-08 (#785 data half): the acknowledged set (V69 order columns), the
    // recorded set (V63 line snapshot) and the channel. Null means NOT RECORDED on every one of
    // them; 0 and [] mean "recorded, and nothing declared". Never coalesced into each other.
    //
    // The two RECORDED fields are populated only on the single-order tracking response. The
    // history list reads order COLUMNS only and leaves them null, because deriving them there
    // would load one item collection per row (the V63 measurement: 7 orders, 7 extra statements).
    // ------------------------------------------------------------------

    @Schema(description = "The allergen mask (AllergenCatalog bits 0..13) the customer acknowledged at checkout. "
            + "0 means they acknowledged a basket declaring none of the 14 regulated allergens; null means no "
            + "acknowledgement was recorded: the shop entered the order (placedVia VENDOR) or it predates the "
            + "acknowledgement.")
    private Integer acknowledgedAllergenMask;

    @Schema(description = "The names of acknowledgedAllergenMask, in AllergenCatalog bit order. [] when the mask "
            + "is 0; null when no acknowledgement was recorded.")
    private List<String> acknowledgedAllergens;

    @Schema(description = "The allergens the order's lines DECLARED when it was placed (the order-line snapshot "
            + "union), in AllergenCatalog bit order. [] when the lines declared none; null when the lines carry no "
            + "snapshot (not recorded). Populated on the single-order tracking response only: always null on the "
            + "order-history list, which does not load order lines.")
    private List<String> recordedAllergens;

    @Schema(description = "ADVISORY reconciliation lines, separate from and never merged into recordedAllergens. "
            + "[] when nothing was flagged; null when not recorded. Tracking response only, like recordedAllergens.")
    private List<OrderAllergenFlagDto> recordedAllergenFlags;

    @Schema(description = "Which channel placed the order: STOREFRONT (the customer, who acknowledged the "
            + "allergen set) or VENDOR (the shop entered it; no customer acknowledgement was recorded). Null "
            + "when the order predates the channel being recorded.",
            allowableValues = {"STOREFRONT", "VENDOR"})
    private String placedVia;

    public Integer getAcknowledgedAllergenMask() { return acknowledgedAllergenMask; }
    public void setAcknowledgedAllergenMask(Integer acknowledgedAllergenMask) { this.acknowledgedAllergenMask = acknowledgedAllergenMask; }
    public List<String> getAcknowledgedAllergens() { return acknowledgedAllergens; }
    public void setAcknowledgedAllergens(List<String> acknowledgedAllergens) { this.acknowledgedAllergens = acknowledgedAllergens; }
    public List<String> getRecordedAllergens() { return recordedAllergens; }
    public void setRecordedAllergens(List<String> recordedAllergens) { this.recordedAllergens = recordedAllergens; }
    public List<OrderAllergenFlagDto> getRecordedAllergenFlags() { return recordedAllergenFlags; }
    public void setRecordedAllergenFlags(List<OrderAllergenFlagDto> recordedAllergenFlags) { this.recordedAllergenFlags = recordedAllergenFlags; }
    public String getPlacedVia() { return placedVia; }
    public void setPlacedVia(String placedVia) { this.placedVia = placedVia; }

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public void setTotalAmountPennies(Long totalAmountPennies) { this.totalAmountPennies = totalAmountPennies; }
    public Long getSubtotalPennies() { return subtotalPennies; }
    public void setSubtotalPennies(Long subtotalPennies) { this.subtotalPennies = subtotalPennies; }
    public String getVatRate() { return vatRate; }
    public void setVatRate(String vatRate) { this.vatRate = vatRate; }
    public Long getVatAmountPennies() { return vatAmountPennies; }
    public void setVatAmountPennies(Long vatAmountPennies) { this.vatAmountPennies = vatAmountPennies; }
    public Integer getUnitCount() { return unitCount; }
    public void setUnitCount(Integer unitCount) { this.unitCount = unitCount; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
