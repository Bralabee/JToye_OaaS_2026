package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.finance.VatRate;

import java.util.UUID;

@Schema(description = "Request to create a new product (Natasha's Law compliant)")
public class CreateProductRequest {

    @NotBlank(message = "SKU is required")
    @Size(min = 1, max = 100, message = "SKU must be between 1 and 100 characters")
    @Schema(description = "Stock Keeping Unit (unique per tenant)", example = "YAM-5KG", required = true)
    private String sku;

    @NotBlank(message = "Title is required")
    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    @Schema(description = "Product title", example = "Yam 5kg", required = true)
    private String title;

    @NotBlank(message = "Ingredients text is required (Natasha's Law)")
    @Size(min = 1, max = 2000, message = "Ingredients text must be between 1 and 2000 characters")
    @Schema(description = "Full ingredients list (Natasha's Law requirement). Wrap each allergen "
            + "in double asterisks so the PPDS label emboldens it INLINE within the ingredients "
            + "list (FSA requirement) — e.g. \"Wheat flour, **milk**, sugar, **egg**\". Real "
            + "punctuation (parentheses, commas, percentages) is preserved. See "
            + "docs/ppds-label-markup.md for the full markup convention.",
            example = "Wheat flour, **milk**, sugar", required = true)
    private String ingredientsText;

    @NotNull(message = "Allergen mask is required (Natasha's Law)")
    @Min(value = 0, message = "Allergen mask must be non-negative")
    @Max(value = 16383, message = "Allergen mask must not exceed 16383 (14 allergens max)")
    @Schema(description = "Bitmask representing allergens (Natasha's Law requirement)", example = "0", required = true)
    private Integer allergenMask;

    @NotNull(message = "Price is required")
    @Min(value = 0, message = "Price must be non-negative")
    @Max(value = 1000000000L, message = "Price must not exceed £10,000,000")
    @Schema(description = "Product price in pennies", example = "999", required = true)
    private Long pricePennies;

    // Defaults to STANDARD when absent so existing API clients keep working and
    // no product is silently zero-rated (Issue #81 BUG 2).
    @Schema(description = "VAT liability for this product", example = "STANDARD",
            defaultValue = "STANDARD")
    private VatRate vatRate = VatRate.STANDARD;

    // Optional storefront presentation fields
    // QA BE-2: description is TEXT (unbounded); a generous @Size guards against abusive
    // payloads and keeps over-length behaviour a clear 400, consistent with the other DTOs.
    @Schema(description = "Customer-facing product description")
    @Size(max = 5000, message = "Description must be at most 5000 characters")
    private String description;

    @Schema(description = "Product image URL")
    private String imageUrl;

    @Schema(description = "Product category for menu grouping", example = "Mains")
    private String category;

    @Schema(description = "Sort position within category", example = "0")
    private Integer displayOrder;

    @Schema(description = "Whether the product is currently available", example = "true")
    private Boolean available;

    @Schema(description = "Whether the product is featured/popular", example = "false")
    private Boolean featured;

    @Schema(description = "Estimated preparation time in minutes", example = "15")
    private Integer preparationTimeMinutes;

    @Schema(description = "Comma-separated dietary tags", example = "Vegan, Gluten-Free")
    private String dietaryTags;

    @Schema(description = "Shop this product belongs to (null = available on all tenant shops)")
    private UUID shopId;

    @Min(value = 0, message = "Stock quantity must be non-negative")
    @Schema(description = "Quantity in stock (null = unlimited/untracked)", example = "50")
    private Integer quantityInStock;

    // ---- PPDS / Natasha's Law durability (Issue #82 P0-6) ----
    // Both optional on the request, but a COMPLIANT PPDS label REQUIRES them: the
    // label endpoint returns HTTP 422 for a product missing shelf life / durability
    // type (alongside a missing shop address). See docs/ppds-label-markup.md.

    @Min(value = 0, message = "Shelf life days must be non-negative")
    @Schema(description = "Per-product shelf life in days. The PPDS label prints a durability date "
            + "computed as generationDate + shelfLifeDays. Required for a compliant label (the "
            + "label 422s when absent).", example = "3")
    private Integer shelfLifeDays;

    @Pattern(regexp = "USE_BY|BEST_BEFORE", message = "Durability type must be USE_BY or BEST_BEFORE")
    @Schema(description = "Which durability wording the PPDS label prints: USE_BY ('Use by:') or "
            + "BEST_BEFORE ('Best before:'). Required for a compliant label (the label 422s when "
            + "absent).", example = "USE_BY", allowableValues = {"USE_BY", "BEST_BEFORE"})
    private String durabilityType;

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
    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public String getDurabilityType() { return durabilityType; }
    public void setDurabilityType(String durabilityType) { this.durabilityType = durabilityType; }
}
