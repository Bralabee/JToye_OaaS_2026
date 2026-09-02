package uk.jtoye.core.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One item of a {@link BatchSyncRequest} (QA-council 20260902 Cluster A, finding API-2).
 *
 * <p>Replaces the untyped {@code Map<String, Object>} the batch used to carry, which gave
 * Jakarta validation nothing to bind to: {@code POST /sync/batch} stored {@code pricePennies=-500}
 * and {@code allergenMask=-1} / {@code 16384} — values {@code PUT /api/v1/products/{id}}
 * rejects — and the unauthenticated public storefront then served them. The bounds below are
 * the ones {@link uk.jtoye.core.product.dto.CreateProductRequest} declares, verbatim (same
 * limits, same messages), so the two write paths answer identically and the RFC 7807
 * {@code https://jtoye.uk/errors/validation} 400 names the offending field with its item index
 * ({@code items[0].pricePennies}).
 *
 * <p><strong>Wire-compatible with the untyped shape, deliberately.</strong> Field names are
 * unchanged; unknown keys are ignored (the edge forwards {@code []map[string]interface{}}
 * verbatim — {@code edge-go/cmd/edge/handlers.go SyncBatch}); and every field stays OPTIONAL at
 * the binding layer, because which fields a given {@code type} needs is decided in
 * {@code SyncService} exactly as before (a product item without a SKU is skipped; a shop item
 * has no SKU at all) — a {@code @NotNull} here would reject one type's valid item on the other
 * type's behalf. The constraints are therefore RANGE/SIZE bounds only, which Jakarta skips on
 * {@code null}. Presence rules (NOT NULL columns) keep firing where they always did, in the
 * database, as the typed {@code missing-field} 400.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "One shop or product to upsert. Which fields apply depends on `type`.")
public class SyncItem {

    @Schema(description = "Item kind, case-insensitive: `shop` or `product`", example = "product")
    private String type;

    // ---- product fields: bounds mirror CreateProductRequest, verbatim ----

    @Size(min = 1, max = 100, message = "SKU must be between 1 and 100 characters")
    @Schema(description = "Stock Keeping Unit (unique per tenant) — the product upsert key", example = "YAM-5KG")
    private String sku;

    @Size(min = 1, max = 255, message = "Title must be between 1 and 255 characters")
    @Schema(description = "Product title", example = "Yam 5kg")
    private String title;

    @Size(min = 1, max = 2000, message = "Ingredients text must be between 1 and 2000 characters")
    @Schema(description = "Full ingredients list (Natasha's Law)", example = "Wheat flour, **milk**, sugar")
    private String ingredientsText;

    @Min(value = 0, message = "Allergen mask must be non-negative")
    @Max(value = 16383, message = "Allergen mask must not exceed 16383 (14 allergens max)")
    @Schema(description = "UK FSA 14-bit allergen bitmask (Natasha's Law)", example = "0")
    private Integer allergenMask;

    @Min(value = 0, message = "Price must be non-negative")
    @Max(value = 1000000000L, message = "Price must not exceed £10,000,000")
    @Schema(description = "Product price in pennies", example = "999")
    private Long pricePennies;

    // ---- shop fields: bounds mirror CreateShopRequest ----

    @Size(min = 1, max = 255, message = "Name must be between 1 and 255 characters")
    @Schema(description = "Shop name — the shop upsert key", example = "Mama Ade's Kitchen")
    private String name;

    @Schema(description = "Shop address", example = "Unit 4, Brixton Village, London")
    private String address;
}
