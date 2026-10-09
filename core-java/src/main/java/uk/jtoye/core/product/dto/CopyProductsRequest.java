package uk.jtoye.core.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Copy products from one shop of the tenant to another (37-13, RWO-026 / UXT-026).
 *
 * <p>The caller needs SHOP_MANAGER on both shops. {@code productIds} null or empty copies the
 * source shop's whole menu; otherwise only those products, each of which must belong to the
 * source shop (one that does not is a per-product FAILED outcome).
 */
@Schema(description = "Copy products from one shop to another shop of the same tenant. Allergen data "
        + "(declared mask, emphasis spans, may-contain mask, ingredients), price, VAT rate, label fields and "
        + "photos are carried verbatim. Idempotent: a product the target already has is skipped.")
public record CopyProductsRequest(
        @NotNull(message = "sourceShopId is required")
        @Schema(description = "Shop to copy from (requires SHOP_MANAGER)", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sourceShopId,

        @NotNull(message = "targetShopId is required")
        @Schema(description = "Shop to copy into; must differ from sourceShopId (requires SHOP_MANAGER)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID targetShopId,

        @Size(max = 500, message = "At most 500 products can be copied in one request")
        @Schema(description = "Products to copy. Omit (or send an empty list) to copy the source shop's whole menu.")
        List<UUID> productIds) {
}
