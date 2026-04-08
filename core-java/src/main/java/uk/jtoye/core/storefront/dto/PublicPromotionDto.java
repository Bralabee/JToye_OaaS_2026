package uk.jtoye.core.storefront.dto;

import uk.jtoye.core.shop.DiscountType;

import java.time.OffsetDateTime;

public class PublicPromotionDto {
    private String label;
    private DiscountType discountType;
    private Integer discountPercent;
    private Integer discountAmountPennies;
    private String category;
    private OffsetDateTime validUntil;

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
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
}
