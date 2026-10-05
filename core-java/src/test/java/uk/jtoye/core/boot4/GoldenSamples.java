package uk.jtoye.core.boot4;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import uk.jtoye.core.customer.CustomerController;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.gdpr.DsarIntakeService;
import uk.jtoye.core.media.MediaAcceptDto;
import uk.jtoye.core.media.MediaAssetDto;
import uk.jtoye.core.media.MediaAssetStatus;
import uk.jtoye.core.media.MediaProcessingEvent;
import uk.jtoye.core.onboarding.OnboardingState;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.product.AllergenSpan;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.access.Membership;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.webhook.WebhookEventEnvelope;
import uk.jtoye.core.webhook.dto.WebhookDeliveryView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

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
 *
 * <p><b>Production shapes, including collection types.</b> Where a value reaches a serializer
 * that records the runtime type (the Redis cache serializer under default typing EVERYTHING), the
 * collection is built the way production builds it: MapStruct's {@code new ArrayList<>(…)} /
 * {@code new LinkedHashMap<>(…)}, {@code Stream.toList()} for product media, and
 * {@code Map.copyOf} for a membership. Unordered JDK collections are never used where their
 * iteration order would reach the bytes: {@code Map.copyOf} of two or more entries iterates in a
 * per-JVM randomised order, which is why {@link #membership()} carries ONE shop grant.
 *
 * <p><b>Private fingerprint records</b> ({@code MediaUploadController.MediaUploadRequest},
 * {@code MediaController.RedriveRequest}, {@code WebhookDeliveryController.ReplayRequest},
 * {@code PublicStorefrontService.GuestCheckoutIdentity}) are built by reflection on their canonical
 * constructor. Their visibility is NOT widened: they are returned as {@code Object}, which is all
 * the idempotency hash ever sees.
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
    /** The webhook envelope / event id. */
    public static final UUID EVENT_ID = UUID.fromString("38010000-0000-4000-8000-00000000a00c");
    /** A second asset, for the two-element product media list. */
    public static final UUID ASSET_ID_2 = UUID.fromString("38010000-0000-4000-8000-00000000a00d");
    /** The original delivery a replay points back to. */
    public static final UUID REPLAYED_DELIVERY_ID = UUID.fromString("38010000-0000-4000-8000-00000000a00e");

    public static final String ORDER_NUMBER = "JT-20261004-0001";

    /** 2026-10-04T12:34:56.123456789Z — nanosecond precision, so any truncation shows. */
    public static final OffsetDateTime INSTANT_UTC =
            OffsetDateTime.of(2026, 10, 4, 12, 34, 56, 123_456_789, ZoneOffset.UTC);

    /** The same instant at +01:00 (BST), for the offset-sensitive samples. */
    public static final OffsetDateTime INSTANT_PLUS_ONE =
            INSTANT_UTC.withOffsetSameInstant(ZoneOffset.ofHours(1));

    private GoldenSamples() {
    }

    // ------------------------------------------------------------ idempotency fingerprints

    /**
     * {@code orders.create}: OrderController passes the bound {@link CreateOrderRequest} as
     * {@code requestBody}. Every field is populated except {@code addressLine2}, which stays null
     * so the null-handling of the mapper is captured.
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

    /** {@code customers.create}: the bound {@code CreateCustomerRequest}; {@code phone} left null. */
    public static CustomerController.CreateCustomerRequest createCustomerRequest() {
        return new CustomerController.CreateCustomerRequest(
                "Ada Golden", "ada.golden@example.test", null, 5);
    }

    /** {@code media.upload}: the private {@code MediaUploadRequest(productId, sha256, isPrimary, sortOrder)}. */
    public static Object mediaUploadRequest() {
        return construct("uk.jtoye.core.media.MediaUploadController$MediaUploadRequest",
                PRODUCT_ID, "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", true, 3);
    }

    /** {@code media.reprocess}: the private {@code RedriveRequest(assetId)}. */
    public static Object redriveRequest() {
        return construct("uk.jtoye.core.media.MediaController$RedriveRequest", ASSET_ID);
    }

    /** {@code webhooks.replay}: the private {@code ReplayRequest(subscriptionId, deliveryId)}. */
    public static Object replayRequest() {
        return construct("uk.jtoye.core.webhook.WebhookDeliveryController$ReplayRequest",
                SUBSCRIPTION_ID, DELIVERY_ID);
    }

    /**
     * {@code storefront.orders.create} (fixture id {@code storefront.guest-order}): the private
     * {@code GuestCheckoutIdentity(shop.getId(), request)} PublicStorefrontService passes as
     * {@code requestBody}.
     */
    public static Object guestCheckoutIdentity() {
        return construct("uk.jtoye.core.storefront.PublicStorefrontService$GuestCheckoutIdentity",
                SHOP_ID, guestOrderRequest());
    }

    /**
     * The {@code legacyRequestBody} PublicStorefrontService passes beside the identity (fixture id
     * {@code storefront.guest-order.legacy}); {@code addressLine2} left null.
     */
    public static GuestOrderRequest guestOrderRequest() {
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Grace Golden");
        request.setCustomerEmail("grace.golden@example.test");
        request.setCustomerPhone("+447700900456");
        request.setNotes("No onions, please — café style");
        request.setIdempotencyKey("golden-guest-key-0001");
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("2 Fixture Street");
        request.setAddressLine2(null);
        request.setAddressCity("Manchester");
        request.setAddressPostcode("M1 1AE");
        request.setItems(List.of(guestItem(PRODUCT_ID, 3), guestItem(PRODUCT_ID_2, 1)));
        return request;
    }

    // ------------------------------------------------------------ stored / public responses

    /** The {@code orders.create} stored response; {@code paymentReference} left null. */
    public static OrderDto orderDto() {
        OrderDto dto = new OrderDto();
        dto.setId(ORDER_ID);
        dto.setTenantId(TENANT_ID);
        dto.setShopId(SHOP_ID);
        dto.setOrderNumber(ORDER_NUMBER);
        dto.setStatus(OrderStatus.CONFIRMED);
        dto.setCustomerName("Ada Golden");
        dto.setCustomerEmail("ada.golden@example.test");
        dto.setCustomerPhone("+447700900123");
        dto.setNotes("Ring the bell — £ and é stay UTF-8");
        dto.setSubtotalPennies(1250L);
        dto.setVatRate(VatRate.STANDARD);
        dto.setVatAmountPennies(250L);
        dto.setTotalAmountPennies(1799L);
        dto.setFulfilmentType(FulfilmentType.DELIVERY);
        dto.setDeliveryFeePennies(299L);
        dto.setItemCount(2);
        dto.setUnitCount(3);
        dto.setPaymentStatus(PaymentStatus.CAPTURED);
        dto.setPaymentReference(null);
        dto.setPaymentMethod("card");
        dto.setCreatedAt(INSTANT_UTC);
        dto.setUpdatedAt(INSTANT_PLUS_ONE);
        return dto;
    }

    /** The {@code customers.create} stored response; {@code phone} left null. */
    public static CustomerController.CustomerDto customerDto() {
        return new CustomerController.CustomerDto(CUSTOMER_ID, TENANT_ID, "Ada Golden",
                "ada.golden@example.test", null, 5, INSTANT_UTC, INSTANT_PLUS_ONE);
    }

    /** The {@code media.upload} / {@code media.reprocess} stored response. */
    public static MediaAcceptDto mediaAcceptDto() {
        return new MediaAcceptDto(ASSET_ID, "PENDING");
    }

    /** The {@code webhooks.replay} stored response; {@code lastError} left null. */
    public static WebhookDeliveryView webhookDeliveryView() {
        return new WebhookDeliveryView(DELIVERY_ID, SUBSCRIPTION_ID, EVENT_ID, "order.confirmed",
                "RETRYING", 2, 503, null, true, REPLAYED_DELIVERY_ID, INSTANT_PLUS_ONE,
                INSTANT_UTC, INSTANT_PLUS_ONE);
    }

    /**
     * A cached / public {@link ProductDto}; {@code dietaryTags} left null. Lists are built as
     * production builds them (MapStruct ArrayList copies; {@code Stream.toList()} for media).
     */
    public static ProductDto productDto() {
        ProductDto p = new ProductDto();
        p.setId(PRODUCT_ID);
        p.setSku("SKU-GOLDEN-001");
        p.setTitle("Jollof Rice");
        p.setIngredientsText("Rice, tomatoes, peppers, groundnut oil");
        p.setAllergenMask(0b0000_0000_0010_0000);
        p.setPricePennies(899L);
        p.setVatRate(VatRate.ZERO);
        p.setCreatedAt(INSTANT_UTC);
        p.setDescription("Party-size portion");
        p.setImageUrl("https://cdn.example.test/jollof.webp");
        p.setCategory("Mains");
        p.setDisplayOrder(3);
        p.setAvailable(Boolean.TRUE);
        p.setFeatured(Boolean.FALSE);
        p.setPreparationTimeMinutes(25);
        p.setDietaryTags(null);
        p.setShopId(SHOP_ID);
        p.setQuantityInStock(12);
        p.setAdditionalImageUrls(new ArrayList<>(List.of(
                "https://cdn.example.test/1.webp", "https://cdn.example.test/2.webp")));
        p.setShelfLifeDays(2);
        p.setDurabilityType("USE_BY");
        p.setAllergenSpans(new ArrayList<>(List.of(new AllergenSpan(0, 4), new AllergenSpan(24, 33))));
        p.setMedia(Stream.of(
                new MediaAssetDto(ASSET_ID, MediaAssetStatus.ACTIVE, false, null,
                        "https://cdn.example.test/a.webp", "https://cdn.example.test/a-thumb.webp",
                        800, 600, false, false),
                new MediaAssetDto(ASSET_ID_2, MediaAssetStatus.FAILED, true, "decode_failed",
                        null, null, null, null, true, false)).toList());
        return p;
    }

    /** A cached / public {@link ShopDto}; {@code bannerUrl} left null. */
    public static ShopDto shopDto() {
        ShopDto s = new ShopDto();
        s.setId(SHOP_ID);
        s.setTenantId(TENANT_ID);
        s.setName("Mama Put");
        s.setAddress("12 Rye Lane, Peckham");
        s.setCreatedAt(INSTANT_PLUS_ONE);
        s.setSlug("mama-put");
        s.setDescription("West African kitchen");
        s.setLogoUrl("https://cdn.example.test/logo.webp");
        s.setBannerUrl(null);
        s.setPhone("+44 20 7946 0000");
        s.setEmail("hello@example.test");
        s.setLatitude(51.4736);
        s.setLongitude(-0.0693);
        Map<String, String> hours = new LinkedHashMap<>();
        hours.put("monday", "09:00-17:00");
        hours.put("saturday", "10:00-14:00");
        s.setOpeningHours(hours);
        s.setDeliveryInfo("Delivery within 3 miles");
        s.setMinimumOrderPennies(1500L);
        s.setPublished(Boolean.TRUE);
        s.setTags("nigerian,halal");
        return s;
    }

    /**
     * A cached shop-membership, built as ShopAccessService builds it ({@code Map.copyOf}). ONE
     * grant only: a two-entry {@code Map.copyOf} iterates in a per-JVM randomised order, so its
     * bytes would differ between capture runs.
     */
    public static Membership membership() {
        Map<UUID, ShopRole> perShop = new LinkedHashMap<>();
        perShop.put(SHOP_ID, ShopRole.SHOP_MANAGER);
        return new Membership(false, false, Map.copyOf(perShop));
    }

    // ------------------------------------------------------------ outbox / AMQP events

    /** An order state change at UTC, as OrderEventPublisher writes it. */
    public static OrderStateChangeEvent orderStateChangeEvent() {
        return new OrderStateChangeEvent(ORDER_ID, TENANT_ID, ORDER_NUMBER,
                OrderStatus.PENDING, OrderStatus.CONFIRMED, INSTANT_UTC, SHOP_ID);
    }

    /**
     * The same change at +01:00, through the legacy six-argument constructor (so {@code shopId}
     * is null, as for any publisher still on that constructor).
     */
    public static OrderStateChangeEvent orderStateChangeEventOffset() {
        return new OrderStateChangeEvent(ORDER_ID, TENANT_ID, ORDER_NUMBER,
                OrderStatus.CONFIRMED, OrderStatus.PREPARING, INSTANT_PLUS_ONE);
    }

    /** A succeeded payment; {@code failureReason} left null. */
    public static PaymentEvent paymentEvent() {
        return new PaymentEvent(ORDER_ID, TENANT_ID, ORDER_NUMBER, "pi_golden_0001", 1799L, "gbp",
                PaymentEvent.PaymentEventType.SUCCEEDED, null, INSTANT_UTC);
    }

    /** A succeeded refund; {@code failureReason} left null. */
    public static RefundEvent refundEvent() {
        return new RefundEvent(REFUND_ID, ORDER_ID, TENANT_ID, ORDER_NUMBER, "re_golden_0001", 500L, "gbp",
                RefundEvent.RefundEventType.REFUND_SUCCEEDED, "succeeded", null, INSTANT_UTC);
    }

    /** An onboarding transition. */
    public static OnboardingStateChangeEvent onboardingStateChangeEvent() {
        return new OnboardingStateChangeEvent(ONBOARDING_ID, TENANT_ID, SHOP_ID,
                OnboardingState.ACTION_REQUIRED, "Food hygiene rating missing", INSTANT_UTC);
    }

    /** A media processing request. */
    public static MediaProcessingEvent mediaProcessingEvent() {
        return new MediaProcessingEvent(TENANT_ID, ASSET_ID);
    }

    // ------------------------------------------------------------ public wire bodies

    /** The outbound webhook envelope, as WebhookFanoutListener builds it for an order event. */
    public static WebhookEventEnvelope webhookEventEnvelope() {
        return new WebhookEventEnvelope(EVENT_ID, "order.confirmed", TENANT_ID, INSTANT_UTC, "1",
                orderStateChangeEvent());
    }

    /** The DSAR intake acknowledgement: a fresh copy of the service's own private constant. */
    public static DsarIntakeService.DsarIntakeAck dsarIntakeAck() {
        try {
            Field ack = DsarIntakeService.class.getDeclaredField("ACK");
            ack.setAccessible(true);
            DsarIntakeService.DsarIntakeAck constant = (DsarIntakeService.DsarIntakeAck) ack.get(null);
            return new DsarIntakeService.DsarIntakeAck(constant.status(), constant.detail(),
                    constant.acknowledgementWindowDays());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DsarIntakeService.ACK is not readable", e);
        }
    }

    /** The 401 body, built exactly as ProblemDetailAuthenticationEntryPoint.commence builds it. */
    public static ProblemDetail problemDetail401() {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Authentication failed");
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create(staticString(
                "uk.jtoye.core.security.ProblemDetailAuthenticationEntryPoint", "UNAUTHORIZED_TYPE")));
        return problem;
    }

    /** Read a non-public static String constant, so a sample cannot drift from production's value. */
    private static String staticString(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getDeclaredField(fieldName);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(className + "." + fieldName + " is not readable", e);
        }
    }

    // ------------------------------------------------------------ helpers

    private static OrderItemRequest orderItem(UUID productId, int quantity) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private static GuestOrderItemRequest guestItem(UUID productId, int quantity) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    /** Build a private record through its canonical constructor, without widening its visibility. */
    private static Object construct(String className, Object... args) {
        try {
            Constructor<?>[] constructors = Class.forName(className).getDeclaredConstructors();
            if (constructors.length != 1) {
                throw new IllegalStateException(className + " has " + constructors.length
                        + " constructors; expected the record's canonical one only");
            }
            Constructor<?> canonical = constructors[0];
            canonical.setAccessible(true);
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build " + className, e);
        }
    }
}
