package uk.jtoye.core.storefront.dto;

public class GuestOrderConfirmation {
    private String orderNumber;
    private String status;
    private Long subtotalPennies;
    private String vatRate;
    private Long vatAmountPennies;
    private Long totalAmountPennies;
    private String shopName;
    private int itemCount;
    private String clientSecret;

    public GuestOrderConfirmation(String orderNumber, String status, Long subtotalPennies,
                                  String vatRate, Long vatAmountPennies, Long totalAmountPennies,
                                  String shopName, int itemCount, String clientSecret) {
        this.orderNumber = orderNumber;
        this.status = status;
        this.subtotalPennies = subtotalPennies;
        this.vatRate = vatRate;
        this.vatAmountPennies = vatAmountPennies;
        this.totalAmountPennies = totalAmountPennies;
        this.shopName = shopName;
        this.itemCount = itemCount;
        this.clientSecret = clientSecret;
    }

    public String getOrderNumber() { return orderNumber; }
    public String getStatus() { return status; }
    public Long getSubtotalPennies() { return subtotalPennies; }
    public String getVatRate() { return vatRate; }
    public Long getVatAmountPennies() { return vatAmountPennies; }
    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public String getShopName() { return shopName; }
    public int getItemCount() { return itemCount; }
    public String getClientSecret() { return clientSecret; }
}
