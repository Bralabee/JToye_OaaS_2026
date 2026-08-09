package uk.jtoye.core.storefront.dto;

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
}
