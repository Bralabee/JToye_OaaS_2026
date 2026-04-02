package uk.jtoye.core.ai;

import uk.jtoye.core.product.dto.ProductDto;

/**
 * Response from image upload that includes both the saved product
 * and optional AI analysis suggestions.
 */
public class ImageUploadResponse {
    private ProductDto product;
    private ImageAnalysisResult aiSuggestions;

    public ImageUploadResponse(ProductDto product, ImageAnalysisResult aiSuggestions) {
        this.product = product;
        this.aiSuggestions = aiSuggestions;
    }

    public ProductDto getProduct() { return product; }
    public void setProduct(ProductDto product) { this.product = product; }
    public ImageAnalysisResult getAiSuggestions() { return aiSuggestions; }
    public void setAiSuggestions(ImageAnalysisResult aiSuggestions) { this.aiSuggestions = aiSuggestions; }
}
