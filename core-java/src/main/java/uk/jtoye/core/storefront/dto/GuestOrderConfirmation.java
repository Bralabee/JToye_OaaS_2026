package uk.jtoye.core.storefront.dto;

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

    public GuestOrderConfirmation(String orderNumber, String status, Long subtotalPennies,
                                  Long deliveryFeePennies, String vatRate, Long vatAmountPennies,
                                  Long totalAmountPennies, String shopName, int itemCount,
                                  Integer unitCount, String clientSecret, List<String> allergenWarnings) {
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
}
