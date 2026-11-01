package uk.jtoye.core.storefront.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the SIGNED-IN customer's own order history ({@code GET /public/orders/mine}), and
 * nothing else (SW-PRY-06, swarm epic #964; threat T-31.1-46).
 *
 * <p>It is {@link PublicOrderStatus} plus the customer's own allergy-note TEXT. It is a separate type
 * on purpose: the unauthenticated tracking response and the {@code ?email=&verify=} history keep
 * returning {@link PublicOrderStatus}, which has no field that could carry the note, so neither can
 * echo it however the shared history mapper changes. Only
 * {@code PublicStorefrontService.getVerifiedCustomerOrders} builds this type, and only
 * {@code PublicStorefrontController.getMyOrders} calls that, behind {@code CustomerJwtVerifier}
 * (customers-realm signature, issuer, expiry, {@code email_verified=true}).
 *
 * <p>Never log this object or its note: it may be special-category (health) data.
 */
public class CustomerOrderStatus extends PublicOrderStatus {

    @Schema(description = "The allergy or dietary note the customer sent with this order, as stored (trimmed). "
            + "Returned only on the signed-in customer's own order history, and only for an order whose contact "
            + "was verified when it was placed (contactVerified true). Null when no note was sent, when the "
            + "contact was not verified, or after an erasure. Never returned on the tracking response or the "
            + "email-verified history.", nullable = true, maxLength = 500)
    private String allergyNote;

    public String getAllergyNote() { return allergyNote; }
    public void setAllergyNote(String allergyNote) { this.allergyNote = allergyNote; }

    /** Deliberately excludes the note: this object must never print it into a log line. */
    @Override
    public String toString() {
        return "CustomerOrderStatus[orderNumber=" + getOrderNumber() + "]";
    }
}
