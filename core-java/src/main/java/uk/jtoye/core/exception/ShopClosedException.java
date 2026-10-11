package uk.jtoye.core.exception;

import java.time.OffsetDateTime;

/**
 * Thrown when a storefront order reaches a shop that is not taking orders now (Phase 37-20, D-15): the
 * vendor paused orders, or the shop's opening hours do not cover the current UK time.
 *
 * <p>Maps to HTTP 409 via {@code GlobalExceptionHandler.handleShopClosed} with the stable type
 * {@code https://jtoye.uk/errors/shop-closed}, code {@code SHOP_CLOSED}, and the properties {@code reason}
 * ({@code PAUSED | CLOSED_NOW | CLOSED_TODAY | NO_HOURS}) and {@code reopensAt} (null when no reopening time
 * is known). The message is the human sentence the customer is shown (goods P2-CHA-14): it names the shop
 * and says when it reopens.
 */
public class ShopClosedException extends RuntimeException {

    private final String shopName;
    private final String reason;
    private final OffsetDateTime reopensAt;

    public ShopClosedException(String shopName, String reason, OffsetDateTime reopensAt, String detail) {
        super(detail);
        this.shopName = shopName;
        this.reason = reason;
        this.reopensAt = reopensAt;
    }

    public String getShopName() {
        return shopName;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getReopensAt() {
        return reopensAt;
    }
}
