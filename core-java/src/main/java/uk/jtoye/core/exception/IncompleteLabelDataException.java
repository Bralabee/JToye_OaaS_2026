package uk.jtoye.core.exception;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when a PPDS (Natasha's Law) allergen label cannot be generated because
 * the product is missing required compliance data — the food business identity
 * (owning shop, its name and address), the shelf life, or the durability type.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity via {@code GlobalExceptionHandler}:
 * the request is well-formed, but the product's persisted data cannot be turned
 * into a compliant label. This deliberately replaces the previous non-compliant
 * fallback (a standalone allergen-summary block / a no-allergen placeholder), so
 * the API fails LOUDLY rather than emitting a misleading "compliant-looking" PDF.
 *
 * <p>Issue #960 (SW-ADE-03): the message is written for the vendor who reads it in a dialog. It says in
 * plain words what to add and where, and carries no database column name and no UUID. The machine
 * readable part is {@link #getMissingFields()}: the request-DTO field names (the ones the product
 * form and {@code CreateProductRequest} use), exposed as the {@code missingFields} problem member.
 */
public class IncompleteLabelDataException extends RuntimeException {
    /** The product has no shop, or its shop does not resolve in the caller's tenant. */
    public static final String FIELD_SHOP = "shopId";
    /** The owning shop has no name. */
    public static final String FIELD_SHOP_NAME = "shopName";
    /** The owning shop has no business address. */
    public static final String FIELD_SHOP_ADDRESS = "shopAddress";
    /** {@code CreateProductRequest.shelfLifeDays}. */
    public static final String FIELD_SHELF_LIFE = "shelfLifeDays";
    /** {@code CreateProductRequest.durabilityType}. */
    public static final String FIELD_DURABILITY_TYPE = "durabilityType";

    private final UUID productId;
    private final List<String> missingFields;

    public IncompleteLabelDataException(String message) {
        this(message, null, List.of());
    }

    public IncompleteLabelDataException(String message, UUID productId, List<String> missingFields) {
        super(message);
        this.productId = productId;
        this.missingFields = List.copyOf(missingFields);
    }

    /** The product the label was asked for; null only for the legacy message-only constructor. */
    public UUID getProductId() {
        return productId;
    }

    /** Every missing item at once, as stable field names; empty only for the legacy constructor. */
    public List<String> getMissingFields() {
        return missingFields;
    }
}
