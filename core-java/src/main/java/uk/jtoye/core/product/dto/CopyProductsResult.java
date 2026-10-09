package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a copy-to-shop request (37-13, RWO-026 / UXT-026): the counts the dashboard reports and
 * one item per source product considered.
 */
@Schema(description = "Result of copying products to another shop: counts plus one outcome per source product")
public record CopyProductsResult(
        @Schema(description = "Products created in the target shop") int created,
        @Schema(description = "Products skipped because the target shop already has them") int skipped,
        @Schema(description = "Products that could not be copied; see each item's reason") int failed,
        @Schema(description = "One outcome per source product, in source order") List<Item> items) {

    /** What happened to one source product. */
    public enum Outcome { CREATED, SKIPPED, FAILED }

    @Schema(name = "CopyProductsResultItem", description = "The outcome for one source product")
    public record Item(
            @Schema(description = "The product copied from") UUID sourceProductId,
            @Schema(description = "CREATED, SKIPPED or FAILED") Outcome outcome,
            @Schema(description = "The new product in the target shop (CREATED only)") UUID newProductId,
            @Schema(description = "Why it was skipped or failed (null when CREATED)") String reason) {
    }

    public static CopyProductsResult of(List<Item> items) {
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
        return new CopyProductsResult(created, skipped, failed, List.copyOf(items));
    }
}
