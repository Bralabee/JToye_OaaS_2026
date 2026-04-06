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
    private int itemCount;
    private String clientSecret;
    private List<String> allergenWarnings;

    public GuestOrderConfirmation(String orderNumber, String status, Long subtotalPennies,
                                  Long deliveryFeePennies, String vatRate, Long vatAmountPennies,
                                  Long totalAmountPennies, String shopName, int itemCount,
                                  String clientSecret, List<String> allergenWarnings) {
        this.orderNumber = orderNumber;
        this.status = status;
        this.subtotalPennies = subtotalPennies;
        this.deliveryFeePennies = deliveryFeePennies;
        this.vatRate = vatRate;
        this.vatAmountPennies = vatAmountPennies;
        this.totalAmountPennies = totalAmountPennies;
        this.shopName = shopName;
        this.itemCount = itemCount;
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
    public String getClientSecret() { return clientSecret; }
    public List<String> getAllergenWarnings() { return allergenWarnings; }
}
