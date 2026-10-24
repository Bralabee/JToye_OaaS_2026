package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A regional/cuisine grouping of pre-audited Pan-African and Caribbean dish templates
 * (GTM-BLK-02).
 */
@Schema(description = "A cuisine pack of pre-audited Pan-African / Caribbean dish templates for 1-click shop seeding")
public record CulinaryPackDto(
        @Schema(description = "Stable cuisine pack key", example = "nigerian") String cuisineKey,
        @Schema(description = "Human-readable cuisine pack name", example = "Nigerian") String cuisineLabel,
        @Schema(description = "Region identifier", example = "WEST_AFRICAN") String region,
        @Schema(description = "Number of dish templates in this cuisine pack", example = "6") int dishCount,
        @Schema(description = "Dish templates included in this cuisine pack") List<CulinaryDishTemplateDto> dishes) {
}
