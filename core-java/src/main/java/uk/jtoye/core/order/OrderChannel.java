package uk.jtoye.core.order;

/**
 * Which channel placed an order (V69 {@code orders.placed_via}, Phase 31.1 D-07).
 *
 * <p>{@link #STOREFRONT} orders come through the public guest-order endpoint and must carry the
 * customer's allergen acknowledgement. {@link #VENDOR} orders come through
 * {@code OrderService.createOrder} (the dashboard, the REST API and the MCP {@code create_order}
 * tool); they take no acknowledgement, so theirs is recorded as NULL ("not recorded").
 *
 * <p>An order with no channel at all predates V69. That NULL is never replaced by a guess.
 */
public enum OrderChannel {
    STOREFRONT,
    VENDOR
}
