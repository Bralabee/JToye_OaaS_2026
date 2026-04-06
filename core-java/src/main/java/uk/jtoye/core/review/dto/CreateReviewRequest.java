package uk.jtoye.core.review.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public class CreateReviewRequest {
    @NotNull(message = "Order ID is required")
    private UUID orderId;

    @NotNull(message = "Food rating is required")
    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    private Integer foodRating;

    @Min(value = 1, message = "Rating must be between 1 and 5")
    @Max(value = 5, message = "Rating must be between 1 and 5")
    private Integer deliveryRating;

    @Size(max = 1000, message = "Comment must be under 1000 characters")
    private String comment;

    @Size(max = 5, message = "Maximum 5 photos per review")
    private List<String> photoUrls;

    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public Integer getFoodRating() { return foodRating; }
    public void setFoodRating(Integer foodRating) { this.foodRating = foodRating; }
    public Integer getDeliveryRating() { return deliveryRating; }
    public void setDeliveryRating(Integer deliveryRating) { this.deliveryRating = deliveryRating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
}
