package uk.jtoye.core.storefront.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

    /**
     * 30-17 (UI-SPEC § S1): the connected account a direct-charge or split-deposit PaymentIntent was
     * created on, for Stripe.js's {@code stripeAccount}. Serialised as JSON null — present, never
     * omitted — for a platform or destination intent and for an order with no intent, so a client can
     * tell "the platform owns it" from an older server that does not say.
     */
    @Schema(description = "Connected Stripe account that owns the PaymentIntent; pass as stripeAccount to Stripe.js. "
            + "Null for platform and destination charges.")
    private String stripeAccountId;

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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Reserved scheduled fulfilment slot UUID, when placed for a scheduled slot.")
    private UUID fulfilmentSlotId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Scheduled service date (YYYY-MM-DD), when placed for a scheduled slot.")
    private LocalDate scheduledForDate;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Human-readable scheduled window label (e.g. '2026-10-24 14:00-18:00').")
    private String scheduledWindowLabel;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Upfront deposit amount in pennies when settled under SPLIT_DEPOSIT_VENDOR_MOR.")
    private Long depositAmountPennies;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Remaining balance due in pennies when settled under SPLIT_DEPOSIT_VENDOR_MOR.")
    private Long balanceDuePennies;

    public GuestOrderConfirmation(String orderNumber, String status, Long subtotalPennies,
                                  Long deliveryFeePennies, String vatRate, Long vatAmountPennies,
                                  Long totalAmountPennies, String shopName, int itemCount,
                                  Integer unitCount, String clientSecret, List<String> allergenWarnings,
                                  Integer acknowledgedAllergenMask, List<String> acknowledgedAllergens,
                                  List<String> recordedAllergens, List<OrderAllergenFlagDto> recordedAllergenFlags,
                                  String stripeAccountId) {
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
        this.stripeAccountId = stripeAccountId;
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
    public String getStripeAccountId() { return stripeAccountId; }
    public List<String> getAllergenWarnings() { return allergenWarnings; }
    public Integer getAcknowledgedAllergenMask() { return acknowledgedAllergenMask; }
    public List<String> getAcknowledgedAllergens() { return acknowledgedAllergens; }
    public List<String> getRecordedAllergens() { return recordedAllergens; }
    public List<OrderAllergenFlagDto> getRecordedAllergenFlags() { return recordedAllergenFlags; }
    public UUID getFulfilmentSlotId() { return fulfilmentSlotId; }
    public void setFulfilmentSlotId(UUID fulfilmentSlotId) { this.fulfilmentSlotId = fulfilmentSlotId; }
    public LocalDate getScheduledForDate() { return scheduledForDate; }
    public void setScheduledForDate(LocalDate scheduledForDate) { this.scheduledForDate = scheduledForDate; }
    public String getScheduledWindowLabel() { return scheduledWindowLabel; }
    public void setScheduledWindowLabel(String scheduledWindowLabel) { this.scheduledWindowLabel = scheduledWindowLabel; }
    public Long getDepositAmountPennies() { return depositAmountPennies; }
    public void setDepositAmountPennies(Long depositAmountPennies) { this.depositAmountPennies = depositAmountPennies; }
    public Long getBalanceDuePennies() { return balanceDuePennies; }
    public void setBalanceDuePennies(Long balanceDuePennies) { this.balanceDuePennies = balanceDuePennies; }
}
