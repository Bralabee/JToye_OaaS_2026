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
}
