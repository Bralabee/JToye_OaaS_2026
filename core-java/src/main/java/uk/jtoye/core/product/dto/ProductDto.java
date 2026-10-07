package uk.jtoye.core.product.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.media.MediaAssetDto;
import uk.jtoye.core.product.AllergenSpan;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class ProductDto {
    private UUID id;
    private String sku;
    private String title;
    private String ingredientsText;
    private Integer allergenMask;
    private Long pricePennies;
    private VatRate vatRate;
    private OffsetDateTime createdAt;
    private String description;
    private String imageUrl;
    private String category;
    private Integer displayOrder;
    private Boolean available;
    private Boolean featured;
    private Integer preparationTimeMinutes;
    private String dietaryTags;
    private UUID shopId;
    private Integer quantityInStock;
    private List<String> additionalImageUrls;
    private Integer shelfLifeDays;
    private String durabilityType;
    private List<AllergenSpan> allergenSpans;

    /**
     * IMG-04 (24-05): the product's media assets, asset-first — the {@code is_primary}
     * {@code product_media} row's asset then the {@code sort_order} gallery rows, each
     * carrying {@code status}/{@code flagged}/{@code failureReason} so the 24-06 UI can
     * render PENDING (processing) / ACTIVE (derivative) / FAILED (reason) / flagged
     * (needs-review) per entry. Populated by {@code ProductService} (not the MapStruct
     * mapper — DB lookups stay out of the mapper, 24-02 convention). Coexists with the
     * flat {@code imageUrl}/{@code additionalImageUrls} during the dual-read window (D-03a);
     * empty for an un-migrated product (which still renders via the flat fields).
     */
    private List<MediaAssetDto> media;

    /**
     * #787 (D-09): the allergens this product's ingredients text emphasises (in CAPITALS or
     * {@code **bold**} markup) that its declared {@code allergenMask} omits, one warning per
     * allergen in bit order. Empty when the declaration and the text agree.
     *
     * <p>Derived, never stored: {@code ProductMapper} recomputes it from title, mask and
     * ingredients through {@code OrderAllergenAggregator} on every mapping, so every create,
     * update, get, list and search response carries it and the vendor products list can show
     * the disagreement instead of "No allergens" (Pitfall 11). The declared mask is never
     * changed by it.
     *
     * <p>{@code NON_NULL}: every mapped DTO carries a list (possibly empty); a DTO built by
     * hand without one, such as Phase 38's Boot-3.5 golden {@code ProductDto} sample, keeps
     * its exact wire form (31.1-01 baseline section 2, route (a)).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ProductAllergenWarning> allergenWarnings;

    /**
     * #861 (D-16): the "may contain" (cross-contact) allergens the vendor recorded, in the same
     * 14-bit layout as {@code allergenMask}, exactly as stored. Never merged into
     * {@code allergenMask}.
     *
     * <p>{@code NON_NULL} (31.1-01 baseline section 2, route (a)): absent means "not recorded",
     * {@code 0} means "no cross-contact risk declared", so the vendor API still tells the two
     * apart, and Phase 38's Boot-3.5 golden {@code ProductDto} sample keeps its exact wire form.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer mayContainMask;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
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
    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public String getDurabilityType() { return durabilityType; }
    public void setDurabilityType(String durabilityType) { this.durabilityType = durabilityType; }
    public List<AllergenSpan> getAllergenSpans() { return allergenSpans; }
    public void setAllergenSpans(List<AllergenSpan> allergenSpans) { this.allergenSpans = allergenSpans; }
    public List<MediaAssetDto> getMedia() { return media; }
    public void setMedia(List<MediaAssetDto> media) { this.media = media; }
    public List<ProductAllergenWarning> getAllergenWarnings() { return allergenWarnings; }
    public void setAllergenWarnings(List<ProductAllergenWarning> allergenWarnings) { this.allergenWarnings = allergenWarnings; }
    public Integer getMayContainMask() { return mayContainMask; }
    public void setMayContainMask(Integer mayContainMask) { this.mayContainMask = mayContainMask; }
}
