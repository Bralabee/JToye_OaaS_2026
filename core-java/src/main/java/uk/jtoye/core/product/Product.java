package uk.jtoye.core.product;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
@Audited
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String title;

    @Column(name = "ingredients_text", nullable = false)
    private String ingredientsText;

    @Column(name = "allergen_mask", nullable = false)
    private Integer allergenMask = 0;

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getIngredientsText() { return ingredientsText; }
    public void setIngredientsText(String ingredientsText) { this.ingredientsText = ingredientsText; }
    public Integer getAllergenMask() { return allergenMask; }
    public void setAllergenMask(Integer allergenMask) { this.allergenMask = allergenMask; }
}
