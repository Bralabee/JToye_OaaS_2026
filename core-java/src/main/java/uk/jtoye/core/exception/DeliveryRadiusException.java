package uk.jtoye.core.exception;

/**
 * A DELIVERY order the shop's delivery radius does not allow (#460 / UXT-030). Raised by
 * {@code FulfilmentPolicy.requireWithinDeliveryRadius}, which BOTH order writers call (the storefront checkout
 * and the vendor / REST / MCP {@code OrderService.createOrder}), so the rule holds on every door.
 *
 * <p>Maps to HTTP 422 via {@code GlobalExceptionHandler.handleDeliveryRadius}:
 * <ul>
 *   <li>{@link Reason#OUT_OF_RANGE}: {@code https://jtoye.uk/errors/delivery-out-of-range}, code
 *       {@code DELIVERY_OUT_OF_RANGE}, with {@code deliveryRadiusKm} and the measured {@code distanceKm}
 *       (to 0.1 km) as typed members, so the checkout can offer collection and an agent can resubmit without
 *       parsing prose;</li>
 *   <li>{@link Reason#POSTCODE_NOT_RECOGNISED}: {@code .../delivery-postcode-unrecognised}, code
 *       {@code DELIVERY_POSTCODE_UNRECOGNISED}: the shop limits delivery by distance and the postcode is not
 *       one this platform can place (a typo, a postcode newer than the dataset, Northern Ireland, which the
 *       dataset does not cover), so the distance cannot be checked. The customer is asked to check it.</li>
 * </ul>
 * Neither echoes the customer's postcode back.
 */
public class DeliveryRadiusException extends RuntimeException {

    public enum Reason { OUT_OF_RANGE, POSTCODE_NOT_RECOGNISED }

    private final Reason reason;
    private final double deliveryRadiusKm;
    private final Double distanceKm;

    private DeliveryRadiusException(Reason reason, String message, double deliveryRadiusKm, Double distanceKm) {
        super(message);
        this.reason = reason;
        this.deliveryRadiusKm = deliveryRadiusKm;
        this.distanceKm = distanceKm;
    }

    public static DeliveryRadiusException outOfRange(double deliveryRadiusKm, double distanceKm) {
        double rounded = Math.round(distanceKm * 10.0) / 10.0;
        return new DeliveryRadiusException(Reason.OUT_OF_RANGE,
                "This shop delivers within " + trim(deliveryRadiusKm) + " km, and that address is about "
                        + trim(rounded) + " km away. Choose collection, or a delivery address closer to the shop.",
                deliveryRadiusKm, rounded);
    }

    public static DeliveryRadiusException postcodeNotRecognised(double deliveryRadiusKm) {
        return new DeliveryRadiusException(Reason.POSTCODE_NOT_RECOGNISED,
                "This shop delivers within " + trim(deliveryRadiusKm) + " km, and we couldn't place that postcode "
                        + "to check the distance. Check the postcode, or choose collection. If it is correct, contact the shop to arrange delivery.",
                deliveryRadiusKm, null);
    }

    public Reason getReason() {
        return reason;
    }

    public double getDeliveryRadiusKm() {
        return deliveryRadiusKm;
    }

    /** The measured distance to 0.1 km; {@code null} when the postcode could not be placed. */
    public Double getDistanceKm() {
        return distanceKm;
    }

    private static String trim(double km) {
        return km == Math.rint(km) ? String.valueOf((long) km) : String.valueOf(km);
    }
}
