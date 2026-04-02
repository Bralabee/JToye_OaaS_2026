package uk.jtoye.core.storefront.dto;

import java.time.OffsetDateTime;

public class PublicOrderStatus {
    private String orderNumber;
    private String status;
    private String shopName;
    private Long totalAmountPennies;
    private int itemCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public Long getTotalAmountPennies() { return totalAmountPennies; }
    public void setTotalAmountPennies(Long totalAmountPennies) { this.totalAmountPennies = totalAmountPennies; }
    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
