package uk.jtoye.core.shop;

/**
 * ADR-0007 / GAP-GTM-02: Governs whether a shop accepts immediate on-demand orders,
 * scheduled pre-order slots, or both.
 */
public enum FulfilmentTimingMode {
    /**
     * Immediate on-demand orders only (Cohort A default). Checkout requires the shop
     * to be open now ({@link uk.jtoye.core.storefront.ShopAvailabilityPolicy}).
     */
    IMMEDIATE_ONLY,

    /**
     * Scheduled slot pre-orders only (Cohort B batch/litre-bowl kitchens and Cohort C
     * caterers). Checkout requires a valid, active, pre-cutoff {@link ShopFulfilmentSlot}
     * with remaining capacity and succeeds even when the shop is currently closed.
     */
    SCHEDULED_SLOTS_ONLY,

    /**
     * Hybrid mode: immediate orders when open now, plus scheduled slot pre-orders at
     * any time (unless the shop is explicitly paused).
     */
    HYBRID_IMMEDIATE_AND_SLOTS
}
