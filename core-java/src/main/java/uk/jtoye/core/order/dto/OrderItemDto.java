package uk.jtoye.core.order.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One order line as the API exposes it.
 *
 * <h2>The allergen pair (LGL-03 / V63)</h2>
 *
 * <p>{@code allergenMask} and {@code allergenNames} are the WRITE-TIME snapshot of what this
 * line's product declared, not a live read of the product today. D-04's whole point is that a
 * single order-level aggregate tells kitchen staff nothing actionable — they need to know which
 * ITEM carries which allergen — so the per-line set is on the wire beside the order-level one.
 *
 * <p>Both are {@code null} together when the line predates V63 ("not recorded"). An empty
 * {@code allergenNames} with {@code allergenMask == 0} means the vendor declared none of the 14
 * regulated allergens. Those are different statements: a consumer that collapses them either
 * claims a historic order was allergen-free, or refuses to render an honest empty state.
 *
 * <p>The names are resolved server-side from {@code AllergenCatalog} so the checkout and the
 * kitchen display cannot disagree about wording; the mask is kept alongside so a client that
 * already has the table (the frontend does) can do its own thing without a second round trip.
 */
public record OrderItemDto(
    UUID id,
    UUID productId,
    String productName,
    Integer quantity,
    Long unitPricePennies,
    Long totalPricePennies,
    OffsetDateTime createdAt,
    Integer allergenMask,
    List<String> allergenNames
) {}
