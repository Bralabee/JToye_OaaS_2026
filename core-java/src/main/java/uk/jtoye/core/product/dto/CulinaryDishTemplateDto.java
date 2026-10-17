package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One Pan-African or Caribbean dish template from the bundled OaaS Culinary Knowledge Base
 * (GTM-BLK-02, {@code seed/oaas_culinary_catalog.json}).
 */
@Schema(description = "A pre-audited Pan-African or Caribbean dish template with UK FSA 14-allergen "
        + "bitmasks and inline-emphasised ingredients prose (GTM-BLK-02)")
public record CulinaryDishTemplateDto(
        @Schema(description = "Stable dish slug", example = "jollof-rice") String slug,
        @Schema(description = "Display name of the dish", example = "Jollof Rice") String name,
        @Schema(description = "Cuisine key", example = "nigerian") String cuisineKey,
        @Schema(description = "Human-readable cuisine label", example = "Nigerian") String cuisineLabel,
        @Schema(description = "Geographic/cultural region", example = "WEST_AFRICAN") String region,
        @Schema(description = "Course classification (main, soup-stew, street-food, side, snack, dessert, breakfast, drink)",
                example = "main") String course,
        @Schema(description = "Customer-facing dish description") String description,
        @Schema(description = "Recommended refrigerated shelf life in days for PPDS Use-By calculation",
                example = "3") int shelfLifeDays,
        @Schema(description = "UK FSA 14-allergen declared bitmask (0..16383)", example = "0") int allergenMask,
        @Schema(description = "Declared allergens in UK FSA bit order") List<String> allergenNames,
        @Schema(description = "UK FSA 14-allergen cross-contact ('May contain') bitmask (0..16383)",
                example = "608") int mayContainMask,
        @Schema(description = "Cross-contact allergens in UK FSA bit order") List<String> mayContainNames,
        @Schema(description = "Ingredients prose with **bold** emphasis tokens matching allergenMask") String ingredientsText,
        @Schema(description = "Dietary tags (e.g. halal-friendly, gluten-free, vegan)") List<String> dietaryTags) {
}
