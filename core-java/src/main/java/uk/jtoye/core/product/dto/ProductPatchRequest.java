package uk.jtoye.core.product.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Phase 37-39 (RWO-016 / UXT-016, P0): the narrow body of {@code PATCH /api/v1/products/{id}} — stock and
 * availability, and (#858, owner ruling 2026-11-04) the price; nothing else.
 *
 * <p>An EPOS or an agent flipping a product's availability used to have only {@code PUT}, which carries the
 * whole product, allergen declaration and ingredients included: an integration that only meant "sold out" could
 * overwrite allergen data. This body cannot express an allergen, ingredient or any other field, and any
 * property other than the four below is REFUSED by name (400 {@code errors/validation}, the property as the
 * {@code errors} key) — never silently ignored, so a client that believes it changed something learns it did not.
 *
 * <p>Unknown properties are captured by {@link #captureUnknownProperty} and refused by
 * {@code ProductService.patchProduct}, rather than relying on {@code @JsonIgnoreProperties(ignoreUnknown = false)}:
 * that annotation value is the Jackson default and does not override the application-wide unknown-property
 * setting (Spring Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off), and even where it fails the request the
 * generic 400 {@code errors/unreadable-request} does not name the property.
 *
 * <p>Every value is absolute, so the same PATCH twice leaves the same product (idempotent by construction).
 */
@Schema(description = "Stock, availability and price patch for one product. Accepts only available, quantityInStock, "
        + "trackInventory and pricePennies; any other property (allergen, ingredient, ...) is refused with a 400 "
        + "errors/validation naming it, and nothing is changed. Omitted fields keep their stored value.",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public class ProductPatchRequest {

    @Schema(description = "Whether the product can be ordered. Omit to keep the current value.", example = "false")
    private Boolean available;

    @Min(value = 0, message = "Stock quantity must be non-negative")
    @Schema(description = "Absolute quantity in stock; setting it tracks stock. Omit to keep the stored quantity.",
            example = "12")
    private Integer quantityInStock;

    @Schema(description = "Stock tracking. Omit (or null) to keep the current tracking; false stops tracking and "
            + "clears the stored quantity (quantityInStock must then be omitted); true requires quantityInStock.",
            example = "true")
    private Boolean trackInventory;

    @Min(value = 0, message = "Price must be non-negative")
    @Max(value = 1000000000L, message = "Price must not exceed £10,000,000")
    @Schema(description = "Absolute price in pennies (#858); the bounds are PUT's. Omit to keep the stored price. "
            + "Orders already placed keep the price they were placed at.", example = "1250")
    private Long pricePennies;

    @JsonIgnore
    private final Set<String> unknownProperties = new LinkedHashSet<>();

    /** Records any property this body does not define, so the service can refuse it by name. */
    @JsonAnySetter
    void captureUnknownProperty(String name, Object value) {
        unknownProperties.add(name);
    }

    /** The properties the client sent that this body does not accept, in the order they arrived. */
    @JsonIgnore
    public Set<String> getUnknownProperties() {
        return Collections.unmodifiableSet(unknownProperties);
    }

    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }
    public Integer getQuantityInStock() { return quantityInStock; }
    public void setQuantityInStock(Integer quantityInStock) { this.quantityInStock = quantityInStock; }
    public Long getPricePennies() { return pricePennies; }
    public void setPricePennies(Long pricePennies) { this.pricePennies = pricePennies; }
    public Boolean getTrackInventory() { return trackInventory; }
    public void setTrackInventory(Boolean trackInventory) { this.trackInventory = trackInventory; }
}
