package uk.jtoye.core.shop.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import uk.jtoye.core.shop.DiscountType;

import java.time.OffsetDateTime;
import java.util.UUID;

public class CreatePromotionRequest {

    @NotBlank
    private String label;

    private DiscountType discountType = DiscountType.PERCENTAGE;

    @Min(1)
    @Max(100)
    private Integer discountPercent;

    @Min(1)
    private Integer discountAmountPennies;

    private String category;

    @NotNull
    private OffsetDateTime validFrom;

    @NotNull
    private OffsetDateTime validUntil;

    private Boolean active = true;

    @NotNull
    private UUID shopId;

    @AssertTrue(message = "PERCENTAGE type requires discountPercent; FLAT_AMOUNT requires discountAmountPennies")
    private boolean isDiscountValid() {
        if (discountType == null || discountType == DiscountType.PERCENTAGE) {
            return discountPercent != null && discountAmountPennies == null;
        } else {
            return discountAmountPennies != null && discountPercent == null;
        }
    }

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
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }
}
