package uk.jtoye.core.storefront.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.shop.HoursSource;
import uk.jtoye.core.shop.OpeningSchedule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public class PublicShopDto {
    private String slug;
    private String name;
    private String description;
    private String address;
    private String logoUrl;
    private String bannerUrl;
    private String phone;
    private String email;
    private Double latitude;
    private Double longitude;
    private Map<String, String> openingHours;
    private String deliveryInfo;
    private Long minimumOrderPennies;
    private Long deliveryFeePennies;
    private Long freeDeliveryThresholdPennies;
    private String tags;

    /**
     * Whether checkout will take an online card payment (QA-council FIX-6 /
     * M3). Derived from {@code PaymentService.isConfigured()}: when Stripe is
     * not configured every order is pay-on-delivery/collection (COD), and the
     * customer must learn that BEFORE committing a binding order, not from
     * the order-creation response. Additive field — existing consumers of the
     * public shop payload ignore it.
     */
    private boolean acceptsCardPayments;

    /**
     * Great-circle distance in kilometres from the coordinate the caller supplied to this shop's
     * stored coordinate (33-06 / #460 link 5).
     *
     * <p><b>Null when the caller supplied no coordinate</b> — every listing that is not a distance
     * search, plus {@code GET /shops/{slug}} and everything derived from it. Nullable rather than
     * absent so the OpenAPI contract has ONE shape for {@code PublicShopDto} and a machine consumer
     * does not have to discover a second one at runtime.
     *
     * <p>This is the SAME number the ordering used: it comes back from the SQL expression in
     * {@code ShopRepository.findPublishedNear} and is never recomputed in Java. Recomputing it
     * would allow the sort and the label to drift, producing a correctly-ordered list whose
     * printed distances are not monotonic.
     *
     * <p>Accuracy is bounded by the source data, not by this field: coordinates are postcode
     * centroids (~100 m, UK/GB only), never door-level.
     */
    private Double distanceKm;

    /**
     * Who the customer is buying from (#789, 31.1-24, D-10/D-11/D-20): the tenant's legal entity
     * from {@code trader_identity} plus this shop's own email and phone.
     *
     * <p><b>Set ONLY by {@code GET /shops/{slug}}.</b> The list, near and search endpoints map
     * through the same {@code toPublicShopDto} and leave it null, so a page of shops costs no
     * per-row lookup (no N+1). The seller is shown where a purchase is about to happen (the shop
     * page, checkout and the confirmation), not on a discovery card.
     *
     * <p>{@code NON_NULL}, deliberately: absent when not set (every list entry) and when the trader
     * has not provided their details. That keeps the field off the wire wherever it is not
     * meaningful and leaves every existing payload byte-identical (the Phase 38 rule for an
     * additive field on a public DTO). The storefront renders absence as "the seller has not
     * provided their legal details yet", never as an empty block.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private SellerIdentityDto seller;

    // ---- 37-20 (D-15, D-25, theme 2): the server's answer, so no client computes open or closed itself ----

    @Schema(description = "Whether the storefront takes an order now (ShopAvailabilityPolicy, UK time). False while "
            + "paused or outside the opening hours; a checkout submitted anyway is refused 409 shop-closed.")
    private boolean openNow;

    @Schema(description = "When the shop next takes orders, when it is not taking them now and a time is known.",
            nullable = true)
    private OffsetDateTime nextOpenAt;

    @Schema(description = "The end of a timed pause still in force (D-15).", nullable = true)
    private OffsetDateTime pausedUntil;

    @Schema(description = "Storefront orders are paused until the vendor resumes them (D-15).")
    private boolean pausedIndefinitely;

    @Schema(description = "Structured opening hours: mon..sun -> {open, close} windows (HH:MM, UK time); [] = closed. "
            + "Null when the shop has none (legacyAlwaysOpen).", nullable = true)
    private Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule;

    @Schema(description = "True for a shop that had no hours when structured hours arrived: it takes orders at any "
            + "time until its vendor sets hours (D-25).")
    private boolean legacyAlwaysOpen;

    @Schema(description = "Where the hours came from. NEEDS_REVIEW: some original text could not be read and those "
            + "days stay open until the vendor saves hours (D-25); openNow already accounts for it.", nullable = true)
    private HoursSource hoursSource;

    // ---- 37-26 (D-20, theme 6): the server's per-line ceiling, so the basket stepper stops where checkout refuses ----

    @Schema(description = "The most units of one product a single order line may carry (jtoye.order-limits.max-line-quantity). "
            + "A line above it is refused 422 order-limit-exceeded on every order path; the storefront stepper stops here.")
    private Integer maxLineQuantity;

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Map<String, String> getOpeningHours() { return openingHours; }
    public void setOpeningHours(Map<String, String> openingHours) { this.openingHours = openingHours; }
    public String getDeliveryInfo() { return deliveryInfo; }
    public void setDeliveryInfo(String deliveryInfo) { this.deliveryInfo = deliveryInfo; }
    public Long getMinimumOrderPennies() { return minimumOrderPennies; }
    public void setMinimumOrderPennies(Long minimumOrderPennies) { this.minimumOrderPennies = minimumOrderPennies; }
    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public void setDeliveryFeePennies(Long deliveryFeePennies) { this.deliveryFeePennies = deliveryFeePennies; }
    public Long getFreeDeliveryThresholdPennies() { return freeDeliveryThresholdPennies; }
    public void setFreeDeliveryThresholdPennies(Long freeDeliveryThresholdPennies) { this.freeDeliveryThresholdPennies = freeDeliveryThresholdPennies; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public boolean isAcceptsCardPayments() { return acceptsCardPayments; }
    public void setAcceptsCardPayments(boolean acceptsCardPayments) { this.acceptsCardPayments = acceptsCardPayments; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
    public SellerIdentityDto getSeller() { return seller; }
    public void setSeller(SellerIdentityDto seller) { this.seller = seller; }
    public boolean isOpenNow() { return openNow; }
    public void setOpenNow(boolean openNow) { this.openNow = openNow; }
    public OffsetDateTime getNextOpenAt() { return nextOpenAt; }
    public void setNextOpenAt(OffsetDateTime nextOpenAt) { this.nextOpenAt = nextOpenAt; }
    public OffsetDateTime getPausedUntil() { return pausedUntil; }
    public void setPausedUntil(OffsetDateTime pausedUntil) { this.pausedUntil = pausedUntil; }
    public boolean isPausedIndefinitely() { return pausedIndefinitely; }
    public void setPausedIndefinitely(boolean pausedIndefinitely) { this.pausedIndefinitely = pausedIndefinitely; }
    public Map<String, List<OpeningSchedule.TimeWindow>> getOpeningSchedule() { return openingSchedule; }
    public void setOpeningSchedule(Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule) { this.openingSchedule = openingSchedule; }
    public boolean isLegacyAlwaysOpen() { return legacyAlwaysOpen; }
    public void setLegacyAlwaysOpen(boolean legacyAlwaysOpen) { this.legacyAlwaysOpen = legacyAlwaysOpen; }
    public HoursSource getHoursSource() { return hoursSource; }
    public void setHoursSource(HoursSource hoursSource) { this.hoursSource = hoursSource; }
    public Integer getMaxLineQuantity() { return maxLineQuantity; }
    public void setMaxLineQuantity(Integer maxLineQuantity) { this.maxLineQuantity = maxLineQuantity; }
}
