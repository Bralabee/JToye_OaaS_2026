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
    private String category;
    private String dietaryTags;
    private Integer preparationTimeMinutes;
    private Boolean featured;
    private boolean inStock;
    private List<String> imageUrls;

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
}
