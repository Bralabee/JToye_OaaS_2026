package uk.jtoye.core.exception;

import uk.jtoye.core.order.FulfilmentType;

import java.util.List;
import java.util.Locale;

/**
 * Thrown when an order asks for a fulfilment type its shop does not offer (Phase 37-48, RWO-023 / UXT-023):
 * a DELIVERY order to a collection-only shop, or a COLLECTION order to a delivery-only one. Raised by
 * {@code FulfilmentPolicy.requireOffered}, which BOTH order writers call (the storefront checkout and the
 * vendor / REST / MCP {@code OrderService.createOrder}), so the rule cannot hold on one door and not the other.
 *
 * <p>Maps to HTTP 422 {@code https://jtoye.uk/errors/fulfilment-not-offered}, code
 * {@code FULFILMENT_NOT_OFFERED}, with the requested type and the offered types as typed members, so the
 * checkout can switch the customer to the other option and an agent can resubmit without parsing prose.
 */
public class FulfilmentNotOfferedException extends RuntimeException {

    private final FulfilmentType requested;
    private final List<FulfilmentType> offered;

    public FulfilmentNotOfferedException(FulfilmentType requested, List<FulfilmentType> offered) {
        super(detail(requested, offered));
        this.requested = requested;
        this.offered = List.copyOf(offered);
    }

    public FulfilmentType getRequested() {
        return requested;
    }

    public List<FulfilmentType> getOffered() {
        return offered;
    }

    /** "This shop doesn't offer delivery. Choose collection to continue." (the UI-SPEC G1 refusal copy). */
    private static String detail(FulfilmentType requested, List<FulfilmentType> offered) {
        String asked = requested.name().toLowerCase(Locale.ROOT);
        if (offered.isEmpty()) {
            return "This shop doesn't offer " + asked + ".";
        }
        return "This shop doesn't offer " + asked + ". Choose "
                + offered.get(0).name().toLowerCase(Locale.ROOT) + " to continue.";
    }
}
