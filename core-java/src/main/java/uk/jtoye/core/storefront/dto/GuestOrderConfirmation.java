package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;

import java.util.List;

public class GuestOrderConfirmation {
    private String orderNumber;
    private String status;
    private Long subtotalPennies;
    private Long deliveryFeePennies;
    private String vatRate;
    private Long vatAmountPennies;
    private Long totalAmountPennies;
    private String shopName;
    /** LINES on the order ({@code COUNT(order_items)}). Semantics untouched — see {@link #unitCount}. */
    private int itemCount;
    /**
     * COR-4 (V66, PR #726 review M5): UNITS on the order — {@code SUM(order_items.quantity)} — beside
     * {@link #itemCount}, which stays LINES. This is the number the basket showed the customer
     * moments before this confirmation renders, so it is the one that must agree with it. Nullable,
     * and null means NOT RECORDED (an idempotent replay of a row that predates V66). Never read null
     * as 0 and never substitute {@code itemCount} for it.
     */
    private Integer unitCount;
    private String clientSecret;
    private List<String> allergenWarnings;

    // ------------------------------------------------------------------
    // Phase 31.1 D-08 (#785 data half): what the customer ACKNOWLEDGED and what the kitchen
    // RECORDED, as separate fields. They are equal on a storefront order by construction (31.1-03
    // compares the acknowledgement with the snapshot union in the same transaction), but they are
    // different FACTS from different columns, and a replayed row from before V69 can hold one
    // without the other. Null on any of them means NOT RECORDED; 0 and [] mean "recorded, and
    // nothing declared". The two are never coalesced into each other.
    // ------------------------------------------------------------------

    @Schema(description = "The allergen mask (AllergenCatalog bits 0..13) the customer acknowledged at checkout "
            + "(orders.allergen_ack_mask). 0 means they acknowledged a basket declaring none of the 14 regulated "
            + "allergens; null means no acknowledgement was recorded (the order predates the acknowledgement).")
    private Integer acknowledgedAllergenMask;

    @Schema(description = "The names of acknowledgedAllergenMask, in AllergenCatalog bit order. [] when the "
            + "mask is 0; null when no acknowledgement was recorded.")
    private List<String> acknowledgedAllergens;

    @Schema(description = "The allergens the order's lines DECLARED when it was placed (the order-line "
            + "snapshot union), in AllergenCatalog bit order. [] when the lines declared none; null when the "
            + "lines carry no snapshot (not recorded). Reconciliation flags are never included here.")
    private List<String> recordedAllergens;

    @Schema(description = "ADVISORY reconciliation lines: an allergen a product's ingredients text emphasises "
            + "but its declaration omits. Separate from, and never merged into, recordedAllergens. [] when "
            + "nothing was flagged; null when the lines carry no snapshot.")
    private List<OrderAllergenFlagDto> recordedAllergenFlags;

    public GuestOrderConfirmation(String orderNumber, String status, Long subtotalPennies,
                                  Long deliveryFeePennies, String vatRate, Long vatAmountPennies,
                                  Long totalAmountPennies, String shopName, int itemCount,
                                  Integer unitCount, String clientSecret, List<String> allergenWarnings,
                                  Integer acknowledgedAllergenMask, List<String> acknowledgedAllergens,
                                  List<String> recordedAllergens, List<OrderAllergenFlagDto> recordedAllergenFlags) {
        this.orderNumber = orderNumber;
        this.status = status;
        this.subtotalPennies = subtotalPennies;
        this.deliveryFeePennies = deliveryFeePennies;
        this.vatRate = vatRate;
        this.vatAmountPennies = vatAmountPennies;
        this.totalAmountPennies = totalAmountPennies;
        this.shopName = shopName;
        this.itemCount = itemCount;
        this.unitCount = unitCount;
        this.clientSecret = clientSecret;
        this.allergenWarnings = allergenWarnings;
        this.acknowledgedAllergenMask = acknowledgedAllergenMask;
        this.acknowledgedAllergens = acknowledgedAllergens;
        this.recordedAllergens = recordedAllergens;
        this.recordedAllergenFlags = recordedAllergenFlags;
    }

    public String getOrderNumber() { return orderNumber; }
    public String getStatus() { return status; }
    public Long getSubtotalPennies() { return subtotalPennies; }
    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public String getVatRate() { return vatRate; }
    public Long getVatAmountPennies() { return vatAmountPennies; }
    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public String getShopName() { return shopName; }
    public int getItemCount() { return itemCount; }
    public Integer getUnitCount() { return unitCount; }
    public String getClientSecret() { return clientSecret; }
    public List<String> getAllergenWarnings() { return allergenWarnings; }
    public Integer getAcknowledgedAllergenMask() { return acknowledgedAllergenMask; }
    public List<String> getAcknowledgedAllergens() { return acknowledgedAllergens; }
    public List<String> getRecordedAllergens() { return recordedAllergens; }
    public List<OrderAllergenFlagDto> getRecordedAllergenFlags() { return recordedAllergenFlags; }
}
