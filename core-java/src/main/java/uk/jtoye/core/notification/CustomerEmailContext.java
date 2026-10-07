package uk.jtoye.core.notification;

import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderChannel;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

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
 */
public record CustomerEmailContext(
        String shopName,
        String shopEmail,
        SellerIdentityDto seller,
        OrderChannel placedVia,
        List<String> acknowledgedAllergens,
        List<String> recordedAllergens,
        List<OrderAllergenFlagDto> flags,
        FulfilmentType fulfilmentType) {

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
