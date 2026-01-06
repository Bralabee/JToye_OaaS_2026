package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
    @Schema(description = "Bitmask representing allergens (Natasha's Law requirement, 0-16383 for 14 allergens)", example = "0", required = true)
    private Integer allergenMask;

    @NotNull(message = "Price is required")
    @Min(value = 0, message = "Price must be non-negative (zero or more pennies)")
    @Max(value = 1000000000L, message = "Price must not exceed £10,000,000 (1000000000 pennies)")
    @Schema(description = "Product price in pennies (GBP minor units). Range: 0 to 1,000,000,000 (£0.00 to £10,000,000.00)",
            example = "999",
            required = true,
            minimum = "0",
            maximum = "1000000000")
    private Long pricePennies;

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
}
