package uk.jtoye.core.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.jtoye.core.order.OrderStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The {@code data} member of an {@code order.*} webhook envelope (#864, P2-RAV-08).
 *
 * <p>The first seven components are the original thin event, byte-for-byte the same names and order, so a receiver
 * written against it keeps working. Everything after {@code shopId} is additive: {@code dataVersion = 2} marks an
 * enriched payload, so a receiver tests one field instead of probing for the others. Amounts are integer pence.
 *
 * <p>Absent members are omitted (not {@code null}): an event whose order could not be read is delivered as the
 * original event with none of the additions and no {@code dataVersion}, never dropped.
 *
 * <p><b>Privacy.</b> {@link Customer} and {@link Address} are the order's own contact details. Webhook
 * subscriptions are GROUP_ADMIN-only and tenant-wide ({@code WebhookSubscriptionService}), so the recipient is the
 * tenant's own administrator, who can read all of this through the API. The STAFF field-minimisation ruling
 * (2026-11-03, #856) is about which fields a kitchen-only role sees; it does not reach a GROUP_ADMIN's endpoint.
 * The stored payload is scrubbed by {@code GdprService} on Article 17 erasure. Not included, deliberately: free-text
 * notes and the allergy note (special-category text the platform does not interpret), and any payment reference.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderWebhookData(
        UUID orderId,
        UUID tenantId,
        String orderNumber,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        OffsetDateTime timestamp,
        UUID shopId,
        Integer dataVersion,
        String fulfilmentType,
        String paymentStatus,
        List<Line> items,
        Long subtotalPennies,
        Long deliveryFeePennies,
        Long vatAmountPennies,
        Long totalPennies,
        Customer customer,
        Address deliveryAddress
) {

    /** The version stamped on an enriched payload; the original thin event has none. */
    public static final int ENRICHED_VERSION = 2;

    /** One order line, as it was when the order was placed (the name is the snapshot, not the live product). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(UUID productId, String name, Integer quantity, Long unitPricePennies, Long totalPricePennies) {
    }

    /** The customer's contact details as stored on the order (an erased order carries the redaction marker). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Customer(String name, String email, String phone) {
    }

    /** The delivery address; present only on a delivery order that has one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Address(String line1, String line2, String city, String postcode) {
    }
}
