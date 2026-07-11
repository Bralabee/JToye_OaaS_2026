package uk.jtoye.core.order;

/**
 * How an order is fulfilled.
 * Maps to the {@code fulfilment_type} VARCHAR+CHECK column on {@code orders}
 * (V45), persisted via {@link jakarta.persistence.EnumType#STRING}.
 */
public enum FulfilmentType {
    /** Delivered to the customer's address (delivery fee applies). */
    DELIVERY,

    /** Collected by the customer in-store (delivery fee is always £0). */
    COLLECTION
}
