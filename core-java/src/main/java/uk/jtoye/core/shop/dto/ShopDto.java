package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.jtoye.core.shop.HoursSource;
import uk.jtoye.core.shop.OpeningSchedule;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShopDto {
    private UUID id;
    // QA-council H1: expose tenantId so real-time clients (Kitchen Display STOMP
    // topic /topic/kitchen.{tenantId}.{shopId}) can derive their subscription.
    // The frontend Shop type already declares tenantId as required; every sibling
    // DTO (OrderDto, CustomerDto) exposes it, and it is already present in the
    // caller's JWT. Public/anonymous responses use the separate PublicShopDto,
    // which has no tenantId — so this adds no anonymous disclosure.
    private UUID tenantId;
    private String name;
    private String address;
    private OffsetDateTime createdAt;
    private String slug;
    private String description;
    private String logoUrl;
    private String bannerUrl;
    private String phone;
    private String email;
    private Double latitude;
    private Double longitude;
    private Map<String, String> openingHours;

    // 37-20 (D-15/D-25): the hours and pause state the dashboard shows. NON_NULL, so a DTO that never set them
    // (the 38-01 golden ShopDto) serializes byte-for-byte as before (the Phase 38 additive-field rule).

    @Schema(description = "Where the opening schedule came from: STRUCTURED (the vendor saved it), MIGRATED_FROM_TEXT, "
            + "NEEDS_REVIEW (some free-text hours could not be read; those days stay open until the vendor saves "
            + "hours, D-25) or LEGACY_ALWAYS_OPEN (no hours were ever set; open at any time). Absent: no hours yet.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private HoursSource hoursSource;

    @Schema(description = "The original free-text hours, mon..sun, for the NEEDS_REVIEW notice. Read-only.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> legacyOpeningHours;

    @Schema(description = "Structured opening hours: mon..sun -> {open, close} windows (HH:MM, UK time); [] = closed.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule;

    @Schema(description = "Storefront orders are paused until this instant (D-15). Absent: no timed pause.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OffsetDateTime ordersPausedUntil;

    @Schema(description = "Storefront orders are paused until the vendor resumes them (D-15).")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean ordersPausedIndefinitely;

    private String deliveryInfo;
    private Long minimumOrderPennies;
    private Boolean published;
    private String tags;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
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
    public HoursSource getHoursSource() { return hoursSource; }
    public void setHoursSource(HoursSource hoursSource) { this.hoursSource = hoursSource; }
    public Map<String, String> getLegacyOpeningHours() { return legacyOpeningHours; }
    public void setLegacyOpeningHours(Map<String, String> legacyOpeningHours) { this.legacyOpeningHours = legacyOpeningHours; }
    public Map<String, List<OpeningSchedule.TimeWindow>> getOpeningSchedule() { return openingSchedule; }
    public void setOpeningSchedule(Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule) { this.openingSchedule = openingSchedule; }
    public OffsetDateTime getOrdersPausedUntil() { return ordersPausedUntil; }
    public void setOrdersPausedUntil(OffsetDateTime ordersPausedUntil) { this.ordersPausedUntil = ordersPausedUntil; }
    public Boolean getOrdersPausedIndefinitely() { return ordersPausedIndefinitely; }
    public void setOrdersPausedIndefinitely(Boolean ordersPausedIndefinitely) { this.ordersPausedIndefinitely = ordersPausedIndefinitely; }
    public String getDeliveryInfo() { return deliveryInfo; }
    public void setDeliveryInfo(String deliveryInfo) { this.deliveryInfo = deliveryInfo; }
    public Long getMinimumOrderPennies() { return minimumOrderPennies; }
    public void setMinimumOrderPennies(Long minimumOrderPennies) { this.minimumOrderPennies = minimumOrderPennies; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
