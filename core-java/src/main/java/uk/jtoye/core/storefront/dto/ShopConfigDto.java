package uk.jtoye.core.storefront.dto;

import uk.jtoye.core.shop.DiscountType;

import java.time.OffsetDateTime;
import java.util.List;

public class ShopConfigDto {
    private List<AnnouncementSummary> announcements;
    private List<PublicProductDto> featuredProducts;
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
