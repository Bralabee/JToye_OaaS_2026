package uk.jtoye.core.notification;

import uk.jtoye.core.order.CancelReason;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderChannel;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything a customer order email needs to know about the SELLER and the order's allergen
 * record (#789, #785; D-08, D-13), loaded once by {@code OrderStateChangeListener} while the
 * event's tenant is pinned, and handed to the {@code @Async} send as an immutable value.
 *
 * <p><b>Why it exists (31.1-RESEARCH Pitfall 8).</b> The send runs on an {@code @Async} thread
 * that carries no TenantContext and no tenant GUC. A shop, trader-identity or order-line lookup
 * made there reads ZERO rows under FORCE row-level security, and would send a seller block with
 * nothing in it. So nothing is looked up after the listener returns: this record is the whole
 * input, and it holds no entity and no lazy collection.
 *
 * <p><b>Why not on {@code OrderStateChangeEvent}.</b> The event is a persisted outbox payload in
 * Phase 38's AMQP/outbox golden set; widening it would leave in-flight rows deserialising with
 * null fields that cannot be told apart from "not recorded". It is not changed.
 *
 * <p>NULL is not empty, and the difference is stated to the customer:
 * <ul>
 *   <li>{@code acknowledgedAllergens} null: no acknowledgement recorded (a vendor-placed order,
 *       D-07, or one placed before V69); {@code []}: the customer acknowledged a basket that
 *       declared none of the 14.</li>
 *   <li>{@code recordedAllergens} null: the order lines carry no V63 snapshot; {@code []}: the
 *       kitchen declared none of the 14 for these items.</li>
 * </ul>
 *
 * <p>The customer's allergy note is deliberately NOT a component: no customer surface echoes its
 * text (31.1-13), and an email is a customer surface.
 *
 * @param shopName              the shop's trading name (vendor-controlled; sanitised before it
 *                              reaches a header); null when the shop could not be loaded
 * @param shopEmail             the shop's published email, used as Reply-To; null when it has none
 * @param seller                the shop's statutory identity from
 *                              {@code TraderIdentityService.findPublicSeller}; null when the trader
 *                              has provided none
 * @param placedVia             STOREFRONT or VENDOR; null when not recorded
 * @param acknowledgedAllergens the names the customer acknowledged at checkout, or null
 * @param recordedAllergens     the order-line snapshot union, or null
 * @param flags                 advisory reconciliation flags, never merged into either set; never null
 * @param fulfilmentType        collection or delivery; null resolves to the delivery copy
 * @param summary               the items, total and destination (SW-JRD-15); null when not read, and
 *                              then the email carries no order summary rather than an empty one
 * @param estimatedReadyAt      #825: the order's ESTIMATED ready-by time (placed or accepted + the shop's prep
 *                              time); null when the shop has no prep time, and then the email says nothing
 * @param cancelReason          #820: why a shop cancelled, as a category; null when none was recorded. Only the
 *                              cancellation email reads it, and only as {@link CancelReason#customerText()}
 * @param shopPhone             #820: the shop's public phone; null when it has none
 */
public record CustomerEmailContext(
        String shopName,
        String shopEmail,
        SellerIdentityDto seller,
        OrderChannel placedVia,
        List<String> acknowledgedAllergens,
        List<String> recordedAllergens,
        List<OrderAllergenFlagDto> flags,
        FulfilmentType fulfilmentType,
        OrderEmailSummary summary,
        OffsetDateTime estimatedReadyAt,
        CancelReason cancelReason,
        String shopPhone) {

    /** A context with a summary but no ready-by estimate, cancel reason or shop phone (the shape before #825/#820). */
    public CustomerEmailContext(String shopName, String shopEmail, SellerIdentityDto seller, OrderChannel placedVia,
                                List<String> acknowledgedAllergens, List<String> recordedAllergens,
                                List<OrderAllergenFlagDto> flags, FulfilmentType fulfilmentType,
                                OrderEmailSummary summary) {
        this(shopName, shopEmail, seller, placedVia, acknowledgedAllergens, recordedAllergens, flags,
                fulfilmentType, summary, null, null, null);
    }

    /** A context with no order summary (the shape before SW-JRD-15). */
    public CustomerEmailContext(String shopName, String shopEmail, SellerIdentityDto seller, OrderChannel placedVia,
                                List<String> acknowledgedAllergens, List<String> recordedAllergens,
                                List<OrderAllergenFlagDto> flags, FulfilmentType fulfilmentType) {
        this(shopName, shopEmail, seller, placedVia, acknowledgedAllergens, recordedAllergens, flags,
                fulfilmentType, null, null, null, null);
    }

    public CustomerEmailContext {
        acknowledgedAllergens = acknowledgedAllergens == null ? null : List.copyOf(acknowledgedAllergens);
        recordedAllergens = recordedAllergens == null ? null : List.copyOf(recordedAllergens);
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    /** A context that knows nothing about the shop: the email names no seller and says so. */
    public static CustomerEmailContext unknownShop(FulfilmentType fulfilmentType) {
        return new CustomerEmailContext(null, null, null, null, null, null, List.of(), fulfilmentType);
    }
}
