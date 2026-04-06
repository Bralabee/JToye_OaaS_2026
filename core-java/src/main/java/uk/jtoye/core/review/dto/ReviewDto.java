package uk.jtoye.core.review.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class ReviewDto {
    private UUID id;
    private String customerName;
    private Integer foodRating;
    private Integer deliveryRating;
    private String comment;
    private List<String> photoUrls;
    private OffsetDateTime createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Integer getFoodRating() { return foodRating; }
    public void setFoodRating(Integer foodRating) { this.foodRating = foodRating; }
    public Integer getDeliveryRating() { return deliveryRating; }
    public void setDeliveryRating(Integer deliveryRating) { this.deliveryRating = deliveryRating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public List<String> getPhotoUrls() { return photoUrls; }
    public void setPhotoUrls(List<String> photoUrls) { this.photoUrls = photoUrls; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
