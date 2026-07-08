package uk.jtoye.core.product;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.envers.Audited;
import uk.jtoye.core.finance.VatRate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "products")
@Audited
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String title;

    @Column(name = "ingredients_text", nullable = false)
    private String ingredientsText;

    @Column(name = "allergen_mask", nullable = false)
    private Integer allergenMask = 0;

    @Column(name = "price_pennies", nullable = false)
    private Long pricePennies = 1000L;

    /**
     * VAT liability for this product. Defaults to STANDARD (UK 20%) so no product
     * is ever silently zero-rated (Issue #81 BUG 2). Resolved into the owning
     * order's predominant VAT rate at order-creation time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_rate", nullable = false, length = 20)
    private VatRate vatRate = VatRate.STANDARD;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(length = 100)
    private String category;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(nullable = false)
    private Boolean available = true;

    @Column(nullable = false)
    private Boolean featured = false;

    @Column(name = "preparation_time_minutes")
    private Integer preparationTimeMinutes;

    @Column(name = "dietary_tags", length = 255)
    private String dietaryTags;

    @Column(name = "shop_id")
    private UUID shopId;

    @Column(name = "quantity_in_stock")
    private Integer quantityInStock;

    @Column(name = "additional_image_urls", columnDefinition = "TEXT[]")
    private List<String> additionalImageUrls = new ArrayList<>();

    // Optimistic-lock column (CQ-01 stock race fix — V34 migration).
    // Primitive long (not Long) — migration DEFAULT 0 + NOT NULL guarantees no NULLs.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIngredientsText() { return ingredientsText; }
    public void setIngredientsText(String ingredientsText) { this.ingredientsText = ingredientsText; }
    public Integer getAllergenMask() { return allergenMask; }
    public void setAllergenMask(Integer allergenMask) { this.allergenMask = allergenMask; }
    public Long getPricePennies() { return pricePennies; }
    public void setPricePennies(Long pricePennies) { this.pricePennies = pricePennies; }
    public VatRate getVatRate() { return vatRate; }
    public void setVatRate(VatRate vatRate) { this.vatRate = vatRate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }
    public Boolean getFeatured() { return featured; }
    public void setFeatured(Boolean featured) { this.featured = featured; }
    public Integer getPreparationTimeMinutes() { return preparationTimeMinutes; }
    public void setPreparationTimeMinutes(Integer preparationTimeMinutes) { this.preparationTimeMinutes = preparationTimeMinutes; }
    public String getDietaryTags() { return dietaryTags; }
    public void setDietaryTags(String dietaryTags) { this.dietaryTags = dietaryTags; }
    public UUID getShopId() { return shopId; }
    public void setShopId(UUID shopId) { this.shopId = shopId; }
    public Integer getQuantityInStock() { return quantityInStock; }
    public void setQuantityInStock(Integer quantityInStock) { this.quantityInStock = quantityInStock; }
    public List<String> getAdditionalImageUrls() { return additionalImageUrls; }
    public void setAdditionalImageUrls(List<String> additionalImageUrls) { this.additionalImageUrls = additionalImageUrls; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    /**
     * Check if product has stock available.
     * NULL quantityInStock means unlimited (no tracking).
     */
    public boolean hasStock() {
        return quantityInStock == null || quantityInStock > 0;
    }

    /**
     * Check if product has enough stock for the requested quantity.
     */
    public boolean hasStock(int requested) {
        return quantityInStock == null || quantityInStock >= requested;
    }
}
