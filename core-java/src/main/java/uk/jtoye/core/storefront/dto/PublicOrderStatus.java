package uk.jtoye.core.storefront.dto;

import java.time.OffsetDateTime;

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
