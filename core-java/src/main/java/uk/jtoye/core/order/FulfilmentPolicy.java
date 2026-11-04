package uk.jtoye.core.order;

import uk.jtoye.core.exception.DeliveryRadiusException;
import uk.jtoye.core.exception.FulfilmentNotOfferedException;
import uk.jtoye.core.geo.GeoBounds;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.shop.Shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ONE place the fulfilment rules live: how a fulfilment type is resolved from a request, what
 * a DELIVERY order must carry, and what delivery costs (COR-1, QA-council 20260902-134741).
 *
 * <p>Modelled on {@link uk.jtoye.core.finance.VatCalculator} — the project's established shape for
 * "single source of truth for a money rule": a final class of pure static functions, no state, no
 * dependencies, so both order-creation paths can reach it and neither can drift from the other.
 * 37-48 added the fourth rule, {@link #requireOffered}: which fulfilment types the shop takes.
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
     * Refuse a DELIVERY order beyond the shop's delivery radius (#460 / UXT-030). Both writers call this right
     * after {@link #requireDeliveryAddress}, so the rule is the same on the storefront and on the vendor / REST /
     * MCP path.
     *
     * <p>It applies only when ALL of these hold, and otherwise the order is not touched: the order is a
     * DELIVERY; the shop has set a {@code deliveryRadiusKm} (null = no limit, every shop before V94); and the
     * shop has a stored coordinate to measure from (a shop whose address never geocoded cannot be measured from,
     * and silently refusing every delivery for that reason would be a worse failure than not enforcing). When it
     * applies, a postcode the dataset cannot place is refused too, as {@code delivery-postcode-unrecognised}:
     * the shop asked for a distance limit, and "unknown, so allowed" would be a hole a typo walks through.
     * The exception is a dataset that is not there at all: when {@code datasetLoaded} says the postcode table is
     * empty (the importer has not finished, or failed), every postcode misses, so the check is SKIPPED rather
     * than refusing every delivery for a dependency failure. It is consulted only after a miss.
     *
     * @param locatePostcode resolves the order's postcode to a coordinate (the production
     *                       {@code PostcodeGeocoder::locateCustomerPostcode}); a function so this class stays
     *                       dependency-free
     * @param datasetLoaded  whether the postcode dataset is loaded at all (the production
     *                       {@code PostcodeGeocoder::isDatasetLoaded}); false = fail open
     * @throws DeliveryRadiusException (422) out of range, or postcode not recognised
     */
    public static void requireWithinDeliveryRadius(
            Shop shop,
            FulfilmentType fulfilmentType,
            String addressPostcode,
            java.util.function.Function<String, java.util.Optional<PostcodeGeocoder.Coordinate>> locatePostcode,
            java.util.function.BooleanSupplier datasetLoaded) {
        if (fulfilmentType != FulfilmentType.DELIVERY) {
            return;
        }
        Double radiusKm = shop.getDeliveryRadiusKm();
        if (radiusKm == null || shop.getLatitude() == null || shop.getLongitude() == null) {
            return;
        }
        PostcodeGeocoder.Coordinate customer = locatePostcode.apply(addressPostcode).orElse(null);
        if (customer == null) {
            if (!datasetLoaded.getAsBoolean()) {
                return;
            }
            throw DeliveryRadiusException.postcodeNotRecognised(radiusKm);
        }
        double distanceKm = GeoBounds.distanceKm(shop.getLatitude(), shop.getLongitude(),
                customer.latitude(), customer.longitude());
        if (distanceKm > radiusKm) {
            throw DeliveryRadiusException.outOfRange(radiusKm, distanceKm);
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

    /**
     * Refuse a fulfilment type the shop does not offer (Phase 37-48, RWO-023 / UXT-023). Both writers call
     * this right after {@link #resolve}, so a collection-only shop refuses DELIVERY on the storefront AND on
     * the vendor / REST / MCP path; a check on one door only is the gap this exists to close.
     *
     * <p>A null flag reads as OFFERED: it is the V83 default (every shop took both types before V83), and an
     * unset flag must never become a silent refusal. The database keeps at least one flag true
     * ({@code ck_shops_offers_some}), so the offered list in the refusal is never empty in practice.
     *
     * @throws FulfilmentNotOfferedException (422 {@code fulfilment-not-offered}) naming the offered types
     */
    public static void requireOffered(Shop shop, FulfilmentType fulfilmentType) {
        if (offers(shop, fulfilmentType)) {
            return;
        }
        List<FulfilmentType> offered = new ArrayList<>();
        for (FulfilmentType type : FulfilmentType.values()) {
            if (offers(shop, type)) {
                offered.add(type);
            }
        }
        throw new FulfilmentNotOfferedException(fulfilmentType, offered);
    }

    private static boolean offers(Shop shop, FulfilmentType type) {
        Boolean flag = type == FulfilmentType.DELIVERY ? shop.getOffersDelivery() : shop.getOffersCollection();
        return !Boolean.FALSE.equals(flag);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
