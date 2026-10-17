package uk.jtoye.core.shop;

import jakarta.annotation.Nullable;
import jakarta.persistence.*;
import uk.jtoye.core.payment.SettlementPolicyMode;
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

    /**
     * Structured opening hours (V80, Phase 37-19 D-15): keys {@code mon}..{@code sun}, each a list of
     * windows, where an empty list means closed that day. NULL when there is no structured schedule: always
     * for a {@link HoursSource#LEGACY_ALWAYS_OPEN} shop (the database refuses one), and for a shop the
     * application wrote before its vendor saved one. {@link #openingHours} is kept, unchanged, for one
     * release (dual-read); it also holds the original text a {@link HoursSource#NEEDS_REVIEW} vendor is shown.
     */
    @Column(name = "opening_schedule", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule;

    /** Where {@link #openingSchedule} came from. NULL = not classified (no default, deliberately). */
    @Enumerated(EnumType.STRING)
    @Column(name = "hours_source", length = 24)
    private HoursSource hoursSource;

    /** Storefront orders are paused until this instant (D-15). NULL = no timed pause. */
    @Column(name = "orders_paused_until")
    private OffsetDateTime ordersPausedUntil;

    /** Storefront orders are paused until the vendor reopens (D-15). */
    @Column(name = "orders_paused_indefinitely", nullable = false)
    private Boolean ordersPausedIndefinitely = false;

    @Column(name = "delivery_info")
    private String deliveryInfo;

    @Column(name = "minimum_order_pennies", nullable = false)
    private Long minimumOrderPennies = 0L;

    @Column(name = "delivery_fee_pennies", nullable = false)
    private Long deliveryFeePennies = 0L;

    @Column(name = "free_delivery_threshold_pennies")
    private Long freeDeliveryThresholdPennies;

    /**
     * The shop takes DELIVERY orders (V83, Phase 37-48 UXT-023). Default true: every shop took both types
     * before V83. Enforced on every order writer by {@code FulfilmentPolicy.requireOffered}; at least one of
     * the two flags is true ({@code ck_shops_offers_some}).
     */
    @Column(name = "offers_delivery", nullable = false)
    private Boolean offersDelivery = true;

    /** The shop takes COLLECTION orders (V83, Phase 37-48 UXT-023). Default true; see {@link #offersDelivery}. */
    @Column(name = "offers_collection", nullable = false)
    private Boolean offersCollection = true;

    /**
     * ADR-0007 / V87: Governs whether this shop takes immediate orders only, scheduled slots only, or both.
     * Default {@link FulfilmentTimingMode#IMMEDIATE_ONLY}, preserving pre-V87 behaviour for every shop.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_timing_mode", nullable = false, length = 32)
    private FulfilmentTimingMode fulfilmentTimingMode = FulfilmentTimingMode.IMMEDIATE_ONLY;

    /**
     * ADR-0007 / V87: Optional shop-level settlement policy override. When {@code null}, the shop inherits
     * from its tenant's {@code OnboardingModel} ({@code WHITE_LABEL} -> {@code DIRECT_CHARGE_VENDOR_MOR},
     * {@code MARKETPLACE} -> {@code DESTINATION_CHARGE_PLATFORM_MOR}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_policy_mode", length = 40)
    private SettlementPolicyMode settlementPolicyMode;

    /**
     * ADR-0007 / V87: Upfront deposit basis points (1000..10000) charged at checkout when
     * {@link #settlementPolicyMode} is {@link SettlementPolicyMode#SPLIT_DEPOSIT_VENDOR_MOR}.
     * Default 10000 (100%).
     */
    @Column(name = "deposit_bps", nullable = false)
    private Integer depositBps = 10000;

    @Column(nullable = false)
    private Boolean published = false;

    @Column(length = 500)
    private String tags;

    @Column(name = "featured_product_ids", columnDefinition = "UUID[]")
    private List<UUID> featuredProductIds;

    /**
     * Optimistic-locking version column. JPA-managed; never mutated by callers.
     * Concurrent updates to the same shop will throw
     * ObjectOptimisticLockingFailureException on save() instead of silently
     * overwriting each other's changes.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
    public Map<String, List<OpeningSchedule.TimeWindow>> getOpeningSchedule() { return openingSchedule; }
    public void setOpeningSchedule(Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule) { this.openingSchedule = openingSchedule; }
    public HoursSource getHoursSource() { return hoursSource; }
    public void setHoursSource(HoursSource hoursSource) { this.hoursSource = hoursSource; }
    public OffsetDateTime getOrdersPausedUntil() { return ordersPausedUntil; }
    public void setOrdersPausedUntil(OffsetDateTime ordersPausedUntil) { this.ordersPausedUntil = ordersPausedUntil; }
    public Boolean getOrdersPausedIndefinitely() { return ordersPausedIndefinitely; }
    public void setOrdersPausedIndefinitely(Boolean ordersPausedIndefinitely) { this.ordersPausedIndefinitely = ordersPausedIndefinitely; }
    public String getDeliveryInfo() { return deliveryInfo; }
    public void setDeliveryInfo(String deliveryInfo) { this.deliveryInfo = deliveryInfo; }
    public Long getMinimumOrderPennies() { return minimumOrderPennies; }
    public void setMinimumOrderPennies(Long minimumOrderPennies) { this.minimumOrderPennies = minimumOrderPennies; }
    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public void setDeliveryFeePennies(Long deliveryFeePennies) { this.deliveryFeePennies = deliveryFeePennies; }
    public Long getFreeDeliveryThresholdPennies() { return freeDeliveryThresholdPennies; }
    public void setFreeDeliveryThresholdPennies(Long freeDeliveryThresholdPennies) { this.freeDeliveryThresholdPennies = freeDeliveryThresholdPennies; }
    public Boolean getOffersDelivery() { return offersDelivery; }
    public void setOffersDelivery(Boolean offersDelivery) { this.offersDelivery = offersDelivery; }
    public Boolean getOffersCollection() { return offersCollection; }
    public void setOffersCollection(Boolean offersCollection) { this.offersCollection = offersCollection; }
    public FulfilmentTimingMode getFulfilmentTimingMode() { return fulfilmentTimingMode; }
    public void setFulfilmentTimingMode(FulfilmentTimingMode fulfilmentTimingMode) { this.fulfilmentTimingMode = fulfilmentTimingMode; }
    public SettlementPolicyMode getSettlementPolicyMode() { return settlementPolicyMode; }
    public void setSettlementPolicyMode(SettlementPolicyMode settlementPolicyMode) { this.settlementPolicyMode = settlementPolicyMode; }
    public Integer getDepositBps() { return depositBps; }
    public void setDepositBps(Integer depositBps) { this.depositBps = depositBps; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public List<UUID> getFeaturedProductIds() { return featuredProductIds; }
    public void setFeaturedProductIds(List<UUID> featuredProductIds) { this.featuredProductIds = featuredProductIds; }

    /** JPA-managed optimistic lock version. Null until the entity is flushed. */
    @Nullable
    public Long getVersion() { return version; }
}
