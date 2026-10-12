package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.shop.DiscountType;

import java.time.OffsetDateTime;
import java.util.List;

public class ShopConfigDto {
    private List<AnnouncementSummary> announcements;
    private List<PublicProductDto> featuredProducts;
    /**
     * 37-35 (D-17, UXT-008): always empty. Checkout applies no promotion, so none is shown; the field is
     * kept as an array so the storefront contract holds. There is no switch that fills it.
     */
    @ArraySchema(arraySchema = @Schema(description = "Always an empty list: promotions are not shown until "
            + "checkout applies them. Kept as an array for contract stability."))
    private List<PromotionDto> activePromotions;

    public List<AnnouncementSummary> getAnnouncements() { return announcements; }
    public void setAnnouncements(List<AnnouncementSummary> announcements) { this.announcements = announcements; }
    public List<PublicProductDto> getFeaturedProducts() { return featuredProducts; }
    public void setFeaturedProducts(List<PublicProductDto> featuredProducts) { this.featuredProducts = featuredProducts; }
    public List<PromotionDto> getActivePromotions() { return activePromotions; }
    public void setActivePromotions(List<PromotionDto> activePromotions) { this.activePromotions = activePromotions; }

    public record AnnouncementSummary(String title, String body, OffsetDateTime validUntil) {}

    public record PromotionDto(
            String label,
            DiscountType discountType,
            Integer discountPercent,
            Integer discountAmountPennies,
            String category,
            OffsetDateTime validUntil
    ) {}
}
