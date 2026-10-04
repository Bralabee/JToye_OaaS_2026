package uk.jtoye.core.boot4;

import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic factory of every sample object the {@code jackson2-golden} fixtures were captured
 * from (Phase 38, plan 38-01).
 *
 * <p>Every call returns a fresh object built from the same constants, so two calls yield equal
 * values and a capture run is reproducible byte for byte. Later plans (38-05, 38-07, 38-08, 38-09,
 * 38-10) rebuild the same objects on the Boot-4 / Jackson-3 tree and compare against the bytes the
 * Boot 3.5 serializers wrote.
 *
 * <p><b>Deliberately Jackson-free</b> (no import from either the Jackson 2 or the Jackson 3
 * package line): this class must compile unchanged on both Boot lines.
 */
public final class GoldenSamples {

    // One fixed UUID per role. Readable on purpose: a fixture that names the wrong role is
    // visible by eye.
    public static final UUID TENANT_ID = UUID.fromString("38010000-0000-4000-8000-00000000a001");
    public static final UUID SHOP_ID = UUID.fromString("38010000-0000-4000-8000-00000000a002");
    public static final UUID ORDER_ID = UUID.fromString("38010000-0000-4000-8000-00000000a003");
    public static final UUID PRODUCT_ID = UUID.fromString("38010000-0000-4000-8000-00000000a004");
    public static final UUID CUSTOMER_ID = UUID.fromString("38010000-0000-4000-8000-00000000a005");
    public static final UUID ASSET_ID = UUID.fromString("38010000-0000-4000-8000-00000000a006");
    public static final UUID SUBSCRIPTION_ID = UUID.fromString("38010000-0000-4000-8000-00000000a007");
    public static final UUID DELIVERY_ID = UUID.fromString("38010000-0000-4000-8000-00000000a008");
    public static final UUID REFUND_ID = UUID.fromString("38010000-0000-4000-8000-00000000a009");
    public static final UUID ONBOARDING_ID = UUID.fromString("38010000-0000-4000-8000-00000000a00a");
    /** A second product, so list-valued samples carry two distinct elements. */
    public static final UUID PRODUCT_ID_2 = UUID.fromString("38010000-0000-4000-8000-00000000a00b");

    /** 2026-10-04T12:34:56.123456789Z — nanosecond precision, so any truncation shows. */
    public static final OffsetDateTime INSTANT_UTC =
            OffsetDateTime.of(2026, 10, 4, 12, 34, 56, 123_456_789, ZoneOffset.UTC);

    /** The same instant at +01:00 (BST), for the offset-sensitive samples. */
    public static final OffsetDateTime INSTANT_PLUS_ONE =
            INSTANT_UTC.withOffsetSameInstant(ZoneOffset.ofHours(1));

    private GoldenSamples() {
    }

    /**
     * The {@code orders.create} idempotency fingerprint (OrderController passes the bound
     * {@link CreateOrderRequest} as {@code requestBody}). Every field is populated except
     * {@code addressLine2}, which stays null so the null-handling of the mapper is captured.
     */
    public static CreateOrderRequest createOrderRequest() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(SHOP_ID);
        request.setCustomerId(CUSTOMER_ID);
        request.setCustomerName("Ada Golden");
        request.setCustomerEmail("ada.golden@example.test");
        request.setCustomerPhone("+447700900123");
        request.setNotes("Ring the bell — £ and é stay UTF-8");
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("1 Fixture Street");
        request.setAddressLine2(null);
        request.setAddressCity("London");
        request.setAddressPostcode("SW1A 1AA");
        request.setItems(List.of(orderItem(PRODUCT_ID, 2), orderItem(PRODUCT_ID_2, 1)));
        return request;
    }

    private static OrderItemRequest orderItem(UUID productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }
}
