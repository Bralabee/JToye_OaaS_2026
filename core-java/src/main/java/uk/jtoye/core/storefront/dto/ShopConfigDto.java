package uk.jtoye.core.storefront.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class ShopConfigDto {
    private List<String> announcements;
    private List<PublicProductDto> featuredProducts;
    private List<PromotionDto> activePromotions;

    public List<String> getAnnouncements() { return announcements; }
    public void setAnnouncements(List<String> announcements) { this.announcements = announcements; }
    public List<PublicProductDto> getFeaturedProducts() { return featuredProducts; }
    public void setFeaturedProducts(List<PublicProductDto> featuredProducts) { this.featuredProducts = featuredProducts; }
    public List<PromotionDto> getActivePromotions() { return activePromotions; }
    public void setActivePromotions(List<PromotionDto> activePromotions) { this.activePromotions = activePromotions; }

    public record PromotionDto(
            String label,
            Integer discountPercent,
            String category,
            OffsetDateTime validUntil
    ) {}
}
