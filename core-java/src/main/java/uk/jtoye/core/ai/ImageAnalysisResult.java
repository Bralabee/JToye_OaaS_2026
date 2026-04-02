package uk.jtoye.core.ai;

import java.util.List;

/**
 * AI-generated analysis of a food product image.
 * Returned to the vendor as suggestions they can accept or edit.
 */
public class ImageAnalysisResult {
    private String identifiedName;
    private String description;
    private String ingredients;
    private String category;
    private List<String> dietaryTags;
    private List<String> allergenWarnings;
    private String cuisineOrigin;
    private Double confidence;

    public String getIdentifiedName() { return identifiedName; }
    public void setIdentifiedName(String identifiedName) { this.identifiedName = identifiedName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getIngredients() { return ingredients; }
    public void setIngredients(String ingredients) { this.ingredients = ingredients; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public List<String> getDietaryTags() { return dietaryTags; }
    public void setDietaryTags(List<String> dietaryTags) { this.dietaryTags = dietaryTags; }
    public List<String> getAllergenWarnings() { return allergenWarnings; }
    public void setAllergenWarnings(List<String> allergenWarnings) { this.allergenWarnings = allergenWarnings; }
    public String getCuisineOrigin() { return cuisineOrigin; }
    public void setCuisineOrigin(String cuisineOrigin) { this.cuisineOrigin = cuisineOrigin; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
}
