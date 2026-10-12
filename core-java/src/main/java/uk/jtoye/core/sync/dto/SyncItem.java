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

import java.util.UUID;

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
 * {@code SyncService} (a product item without a SKU or a shop is REJECTED for that item alone,
 * Phase 37-40; a shop item has no SKU at all) — a {@code @NotNull} here would reject one type's valid item on the other
 * type's behalf. The constraints are therefore RANGE/SIZE bounds only, which Jakarta skips on
 * {@code null}. Presence rules are decided in {@code SyncService}, per branch: on an UPDATE of
 * an existing product every absent field means "unchanged" (PR #726 review M7 — title and
 * ingredientsText included, not only allergenMask and pricePennies); on a CREATE {@code title} is
 * REQUIRED, and so are the other fields {@code CreateProductRequest} requires (ingredientsText,
 * allergenMask, pricePennies): a create missing one is REJECTED with code {@code INVALID_FIELDS} naming
 * them, before anything is written (Phase 37-40; previously a missing title was a 400 for the whole
 * batch and a missing ingredients text died on {@code products.ingredients_text NOT NULL}).
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
    @Schema(description = "Product title. Required when the SKU is new (create); optional on update, where an absent title leaves the existing one unchanged", example = "Yam 5kg")
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

    // ---- Phase 37-40 (RWO-017 / UXT-017 P0): the shop a product belongs to, and its availability and stock ----

    @Schema(description = "Shop the product belongs to. REQUIRED on every product item: a product with no shop "
            + "would be listed and orderable at every shop of the tenant, so an item without one is REJECTED "
            + "with code SHOP_REQUIRED. On an update it must be the shop the SKU already belongs to "
            + "(otherwise REJECTED with code SHOP_MISMATCH). The caller needs SHOP_MANAGER on it.",
            example = "8f14e45f-ceea-467f-a0e6-7c3d1c8a1b2e")
    private UUID shopId;

    @Schema(description = "Whether the product is on sale. Optional; omitted keeps the current value (true on create).",
            example = "true")
    private Boolean available;

    @Min(value = 0, message = "Stock quantity must be non-negative")
    @Schema(description = "Quantity in stock. Optional; omitted keeps the stored quantity. Sending a number "
            + "tracks stock at that count. Same rules as PUT /products/{id}.", example = "12")
    private Integer quantityInStock;

    @Schema(description = "Stock tracking, as on PUT /products/{id}: omit to keep the current tracking; false "
            + "stops tracking (quantityInStock must then be omitted); true requires quantityInStock. A "
            + "contradiction is REJECTED with code INVALID_FIELDS.", example = "true")
    private Boolean trackInventory;

    // ---- shop fields: bounds mirror CreateShopRequest ----

    @Size(min = 1, max = 255, message = "Name must be between 1 and 255 characters")
    @Schema(description = "Shop name — the shop upsert key", example = "Mama Ade's Kitchen")
    private String name;

    @Schema(description = "Shop address", example = "Unit 4, Brixton Village, London")
    private String address;
}
