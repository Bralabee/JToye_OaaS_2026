package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request to idempotently seed a shop's catalogue from one or more Pan-African / Caribbean
 * culinary packs or specific dish slugs (GTM-BLK-02).
 */
@Schema(description = "Seed a shop's product catalogue from the Pan-African & Caribbean Culinary Knowledge Base. "
        + "Requires SHOP_MANAGER on shopId and SCOPE_catalog:write. Idempotent: dishes whose deterministic "
        + "CKB SKU or title already exist in the target shop are skipped.")
public record CulinaryPackSeedRequest(
        @NotNull(message = "shopId is required")
        @Schema(description = "Target shop to seed dishes into (requires SHOP_MANAGER)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID shopId,

        @Size(max = 20, message = "At most 20 cuisine keys can be requested at once")
        @Schema(description = "Cuisine pack keys to seed (e.g. ['nigerian', 'ghanaian']). "
                + "At least one of cuisineKeys or dishSlugs must be provided.")
        List<String> cuisineKeys,

        @Size(max = 100, message = "At most 100 dish slugs can be requested at once")
        @Schema(description = "Optional specific dish slugs to seed (e.g. ['jollof-rice', 'suya']). "
                + "If both cuisineKeys and dishSlugs are provided, their union is seeded.")
        List<String> dishSlugs,

        @Min(value = 1, message = "defaultPricePennies must be at least 1")
        @Schema(description = "Optional override price in pennies applied to seeded dishes. "
                + "When omitted, mains/soups-stews default to 950 (£9.50) and sides/snacks/street-food/desserts/drinks default to 550 (£5.50).")
        Long defaultPricePennies,

        @Schema(description = "Whether seeded dishes are immediately marked available on the storefront. "
                + "Defaults to false (draft) so the Food Business Operator reviews and confirms their exact kitchen recipe and price before going live.")
        Boolean available) {
}
