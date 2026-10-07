package uk.jtoye.core.storefront.dto;

import java.util.List;
import java.util.UUID;

public class PublicProductDto {
    private UUID id;
    private String title;
    private String description;
    private String imageUrl;
    private String ingredientsText;
    private Integer allergenMask;
    private Long pricePennies;
    /**
     * COR-6 (QA-council 20260902-134741): this product's VAT rate, as the enum-string form of
     * {@link uk.jtoye.core.finance.VatRate} (STANDARD | REDUCED | ZERO | EXEMPT).
     *
     * <p>Without it the checkout page could not resolve the rate AT ALL, so it hardcoded
     * {@code gross * 20 / 120} and a "VAT (incl. 20%)" label. On a zero-rated basket — most
     * cold takeaway food is zero-rated (HMRC VAT Notice 709/1) — the customer was shown a VAT
     * figure before paying and a contradicting one on the confirmation screen a moment later.
     * The server has resolved the real rate since Issue #81 BUG 2
     * ({@code VatCalculator.predominantRate}); the client was simply never given the input.
     *
     * <p>Additive: an older client that ignores the field is unaffected. The client mirror is a
     * PREVIEW only — {@code VatCalculator} remains authoritative and the order's VAT is
     * recomputed server-side at write time.
     */
    private String vatRate;
    private String category;
    private String dietaryTags;
    private Integer preparationTimeMinutes;
    private Boolean featured;
    private boolean inStock;
    private List<String> imageUrls;
    /**
     * #787 (D-09): the allergen names this product's ingredients text emphasises (in CAPITALS
     * or {@code **bold**} markup) that its declared {@code allergenMask} omits, in
     * {@code AllergenCatalog} bit order. Empty when the declaration and the text agree.
     *
     * <p>Computed on the server by the same {@code OrderAllergenAggregator} the order
     * snapshot and the vendor's {@code ProductDto.allergenWarnings} use, so the browser never
     * parses ingredients text. A storefront surface must render a non-empty list as an
     * advisory beside the declared allergens ("Ingredients name: Milk"), never as "No
     * allergens", and must never merge it into the declared set.
     */
    private List<String> undeclaredIngredientAllergens;
    /**
     * #861 (D-16): the "may contain" (cross-contact) allergen names the vendor recorded that are
     * NOT already in the declared {@code allergenMask}, in {@code AllergenCatalog} bit order.
     * Empty (never null) when the vendor recorded none, has not recorded any, or every one is
     * already declared.
     *
     * <p>Computed by {@code MayContainAllergens}, the same rule the PPDS label prints. A storefront
     * surface must render a non-empty list as its own "May contain" line, separate from the
     * declared allergens, and must never merge it into the declared set.
     */
    private List<String> mayContainAllergens;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getIngredientsText() { return ingredientsText; }
    public void setIngredientsText(String ingredientsText) { this.ingredientsText = ingredientsText; }
    public Integer getAllergenMask() { return allergenMask; }
    public void setAllergenMask(Integer allergenMask) { this.allergenMask = allergenMask; }
    public Long getPricePennies() { return pricePennies; }
    public void setPricePennies(Long pricePennies) { this.pricePennies = pricePennies; }
    public String getVatRate() { return vatRate; }
    public void setVatRate(String vatRate) { this.vatRate = vatRate; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDietaryTags() { return dietaryTags; }
    public void setDietaryTags(String dietaryTags) { this.dietaryTags = dietaryTags; }
    public Integer getPreparationTimeMinutes() { return preparationTimeMinutes; }
    public void setPreparationTimeMinutes(Integer preparationTimeMinutes) { this.preparationTimeMinutes = preparationTimeMinutes; }
    public Boolean getFeatured() { return featured; }
    public void setFeatured(Boolean featured) { this.featured = featured; }
    public boolean isInStock() { return inStock; }
    public void setInStock(boolean inStock) { this.inStock = inStock; }
    public List<String> getImageUrls() { return imageUrls; }
    public void setImageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; }
    public List<String> getUndeclaredIngredientAllergens() { return undeclaredIngredientAllergens; }
    public void setUndeclaredIngredientAllergens(List<String> undeclaredIngredientAllergens) { this.undeclaredIngredientAllergens = undeclaredIngredientAllergens; }
    public List<String> getMayContainAllergens() { return mayContainAllergens; }
    public void setMayContainAllergens(List<String> mayContainAllergens) { this.mayContainAllergens = mayContainAllergens; }
}
