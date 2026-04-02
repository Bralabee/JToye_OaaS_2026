package uk.jtoye.core.storefront.dto;

public class GuestOrderConfirmation {
    private String orderNumber;
    private String status;
    private Long totalAmountPennies;
    private String shopName;
    private int itemCount;

    public GuestOrderConfirmation(String orderNumber, String status, Long totalAmountPennies, String shopName, int itemCount) {
        this.orderNumber = orderNumber;
        this.status = status;
        this.totalAmountPennies = totalAmountPennies;
        this.shopName = shopName;
        this.itemCount = itemCount;
    }

    public String getOrderNumber() { return orderNumber; }
    public String getStatus() { return status; }
    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public String getShopName() { return shopName; }
    public int getItemCount() { return itemCount; }
}
