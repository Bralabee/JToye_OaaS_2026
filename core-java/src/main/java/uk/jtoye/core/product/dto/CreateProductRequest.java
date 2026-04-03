package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    @Schema(description = "Full ingredients list (Natasha's Law requirement)", example = "Yam (100%)", required = true)
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

    // Optional storefront presentation fields
    @Schema(description = "Customer-facing product description")
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
}
