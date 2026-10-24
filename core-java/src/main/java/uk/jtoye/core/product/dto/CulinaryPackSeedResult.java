package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of seeding a shop's catalogue from the Pan-African & Caribbean Culinary Knowledge Base
 * (GTM-BLK-02).
 */
@Schema(description = "Result of seeding Pan-African / Caribbean dish templates into a shop")
public record CulinaryPackSeedResult(
        @Schema(description = "Dishes created in the target shop") int created,
        @Schema(description = "Dishes skipped because the target shop already has them") int skipped,
        @Schema(description = "Dishes that could not be seeded; see each item's reason") int failed,
        @Schema(description = "One outcome per requested dish template, in catalogue order") List<Item> items) {

    /** What happened to one dish template. */
    public enum Outcome { CREATED, SKIPPED, FAILED }

    @Schema(name = "CulinaryPackSeedResultItem", description = "The outcome for one dish template")
    public record Item(
            @Schema(description = "The dish template slug") String dishSlug,
            @Schema(description = "The dish display name") String dishName,
            @Schema(description = "CREATED, SKIPPED or FAILED") Outcome outcome,
            @Schema(description = "The new product ID in the target shop (CREATED only)") UUID productId,
            @Schema(description = "Why it was skipped or failed (null when CREATED)") String reason) {
    }

    public static CulinaryPackSeedResult of(List<Item> items) {
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (Item item : items) {
            switch (item.outcome()) {
                case CREATED -> created++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new CulinaryPackSeedResult(created, skipped, failed, List.copyOf(items));
    }
}
