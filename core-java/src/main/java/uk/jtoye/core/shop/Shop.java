package uk.jtoye.core.shop;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "shops")
@Audited
public class Shop {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(nullable = false, unique = true, length = 100)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String email;

    private Double latitude;

    private Double longitude;

    @Column(name = "opening_hours", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> openingHours;

    @Column(name = "delivery_info")
    private String deliveryInfo;

    @Column(name = "minimum_order_pennies", nullable = false)
    private Long minimumOrderPennies = 0L;

    @Column(name = "delivery_fee_pennies", nullable = false)
    private Long deliveryFeePennies = 0L;

    @Column(name = "free_delivery_threshold_pennies")
    private Long freeDeliveryThresholdPennies;

    @Column(nullable = false)
    private Boolean published = false;

    @Column(length = 500)
    private String tags;

    @Column(name = "announcements", columnDefinition = "TEXT[]")
    private List<String> announcements;

    @Column(name = "featured_product_ids", columnDefinition = "UUID[]")
    private List<UUID> featuredProductIds;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
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
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public List<String> getAnnouncements() { return announcements; }
    public void setAnnouncements(List<String> announcements) { this.announcements = announcements; }
    public List<UUID> getFeaturedProductIds() { return featuredProductIds; }
    public void setFeaturedProductIds(List<UUID> featuredProductIds) { this.featuredProductIds = featuredProductIds; }
}
