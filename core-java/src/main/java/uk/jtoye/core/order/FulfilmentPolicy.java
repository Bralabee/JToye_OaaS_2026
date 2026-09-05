package uk.jtoye.core.order;

import java.util.Locale;

/**
 * The ONE place the fulfilment rules live: how a fulfilment type is resolved from a request, what
 * a DELIVERY order must carry, and what delivery costs (COR-1, QA-council 20260902-134741).
 *
 * <p>Modelled on {@link uk.jtoye.core.finance.VatCalculator} — the project's established shape for
 * "single source of truth for a money rule": a final class of pure static functions, no state, no
 * dependencies, so both order-creation paths can reach it and neither can drift from the other.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Until COR-1 the rules lived in exactly one of the two writers.
 * {@code PublicStorefrontService.createGuestOrder} parsed the type, enforced the address and
 * applied the fee; {@code OrderService.createOrder} — the vendor / REST / MCP path — did none of
 * those things, so every order created through it took the V45 entity default and persisted as
 * DELIVERY with a £0 fee and no address. Measured on the dev runtime: 4 of 60 orders, every one of
 * them DELIVERY, fee 0, address NULL. That produced a delivery kitchen ticket with nowhere to
 * deliver to, a READY email promising delivery, and an empty "Delivery address" block on the
 * vendor's screen.
 *
 * <p>Copying the storefront's three rules into the second service would have made two copies of a
 * money rule. This class is the alternative.
 *
 * <h2>The fee rule is server-authoritative</h2>
 *
 * <p>{@link #deliveryFeePennies} takes the shop's own configuration and the computed item
 * subtotal. It never reads a client-supplied fee: the client's arithmetic is a preview, and the
 * total is recomputed here, so tampering with the fulfilment type to underpay is neutralised.
 */
public final class FulfilmentPolicy {

    /** The message both paths raise when a DELIVERY order arrives with no usable address. */
    public static final String MISSING_ADDRESS_MESSAGE =
            "Delivery address (line 1, city and postcode) is required for delivery orders.";

    private FulfilmentPolicy() {
        // static-only utility
    }

    /**
     * Resolve the request's fulfilment type.
     *
     * <p>The {@code fallback} is a per-caller decision and is deliberately NOT a constant here.
     * The storefront falls back to {@code DELIVERY}, matching the V45 column default and the
     * pre-existing behaviour of that endpoint. The vendor / REST / MCP path falls back to
     * {@code COLLECTION}, because that path's request captures no address at all — see
     * {@code OrderService.createOrder} for the full argument. Hard-coding one answer here would
     * silently move one of the two.
     *
     * @param raw      the enum string from the request, may be null or blank
     * @param fallback what a null/blank value means for THIS caller
     * @return the resolved type; never null
     * @throws IllegalArgumentException on a non-blank value that is not a known type — an unknown
     *         value is a 400, never a silent default, because silently defaulting is exactly how
     *         COR-1 happened
     */
    public static FulfilmentType resolve(String raw, FulfilmentType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            // Locale.ROOT: a bare toUpperCase() follows the JVM default locale, and under tr-TR
            // "delivery" becomes "DELİVERY" (dotted capital I) — a valid request 400s on a
            // Turkish-locale host. The enum constants are ASCII; the comparison must be too.
            return FulfilmentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid fulfilment type: " + raw
                    + " (expected DELIVERY or COLLECTION)");
        }
    }

    /**
     * Conditional-required address check. A DELIVERY order MUST carry line 1, city and postcode;
     * line 2 stays optional. A COLLECTION order is not checked and must not persist an address.
     *
     * @throws IllegalArgumentException with {@link #MISSING_ADDRESS_MESSAGE} when a DELIVERY order
     *         is missing any required part
     */
    public static void requireDeliveryAddress(FulfilmentType fulfilmentType,
                                              String addressLine1,
                                              String addressCity,
                                              String addressPostcode) {
        if (fulfilmentType != FulfilmentType.DELIVERY) {
            return;
        }
        if (isBlank(addressLine1) || isBlank(addressCity) || isBlank(addressPostcode)) {
            throw new IllegalArgumentException(MISSING_ADDRESS_MESSAGE);
        }
    }

    /**
     * The delivery fee for an order, server-authoritative.
     *
     * <p>COLLECTION always costs £0. DELIVERY costs the shop's configured fee, waived to £0 once
     * the ITEM subtotal (delivery excluded) reaches the shop's free-delivery threshold. A null
     * shop fee is £0 (an API-created shop genuinely has none) and a null threshold means "no
     * waiver configured", not "always waive".
     *
     * @param fulfilmentType               how the order is fulfilled
     * @param itemSubtotalPennies          sum of the line totals, delivery excluded
     * @param shopDeliveryFeePennies       the shop's configured fee, nullable
     * @param freeDeliveryThresholdPennies the shop's waiver threshold, nullable
     * @return the fee in pennies; never negative
     */
    public static long deliveryFeePennies(FulfilmentType fulfilmentType,
                                          long itemSubtotalPennies,
                                          Long shopDeliveryFeePennies,
                                          Long freeDeliveryThresholdPennies) {
        if (fulfilmentType == FulfilmentType.COLLECTION) {
            return 0L;
        }
        long fee = shopDeliveryFeePennies != null ? shopDeliveryFeePennies : 0L;
        if (freeDeliveryThresholdPennies != null && itemSubtotalPennies >= freeDeliveryThresholdPennies) {
            return 0L;
        }
        return fee;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
