package uk.jtoye.core.shop.dto;

import uk.jtoye.core.shop.DiscountType;

import java.time.OffsetDateTime;
import java.util.UUID;

public class PromotionDto {
    private UUID id;
    private UUID shopId;
    private String label;
    private DiscountType discountType;
    private Integer discountPercent;
    private Integer discountAmountPennies;
    private String category;
    private OffsetDateTime validFrom;
    private OffsetDateTime validUntil;
    private Boolean active;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }
    public Integer getDiscountPercent() { return discountPercent; }
    public void setDiscountPercent(Integer discountPercent) { this.discountPercent = discountPercent; }
    public Integer getDiscountAmountPennies() { return discountAmountPennies; }
    public void setDiscountAmountPennies(Integer discountAmountPennies) { this.discountAmountPennies = discountAmountPennies; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
