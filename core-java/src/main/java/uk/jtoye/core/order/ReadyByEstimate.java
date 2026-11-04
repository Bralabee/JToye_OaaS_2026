package uk.jtoye.core.order;

import uk.jtoye.core.shop.Shop;

import java.time.OffsetDateTime;

/**
 * The ESTIMATED ready-by time of an order (#825, owner ruling 2026-11-04, persona close-out): the shop's
 * default preparation time added to the moment the order was placed or accepted.
 *
 * <p>One rule, shared by the storefront writer, the vendor writer and the accept transition, so the three
 * cannot disagree. It is an estimate and every surface labels it as one; it is not a promise.
 *
 * <ul>
 *   <li>A shop with no preparation time ({@code NULL}) produces NO estimate: nothing is shown, never a guess.</li>
 *   <li>A scheduled-slot order (V87) already carries the customer's chosen window, which is the time that
 *       matters; an on-demand estimate would contradict it, so it gets none.</li>
 * </ul>
 */
public final class ReadyByEstimate {

    private ReadyByEstimate() {
        // static-only
    }

    /**
     * @param shop   the order's shop (may be null: then no estimate)
     * @param anchor the instant the order was placed or accepted
     * @param order  the order, consulted only to detect a scheduled-slot order
     * @return anchor plus the shop's prep time, or {@code null} when no estimate applies
     */
    public static OffsetDateTime of(Shop shop, OffsetDateTime anchor, Order order) {
        if (shop == null || anchor == null) {
            return null;
        }
        Integer minutes = shop.getPrepTimeMinutes();
        if (minutes == null || minutes < 1) {
            return null;
        }
        if (order != null && (order.getFulfilmentSlotId() != null || order.getScheduledForDate() != null)) {
            return null;
        }
        return anchor.plusMinutes(minutes);
    }
}
