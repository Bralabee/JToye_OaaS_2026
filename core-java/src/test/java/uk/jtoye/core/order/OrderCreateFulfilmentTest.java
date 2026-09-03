package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.notification.EmailNotificationService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COR-1 (QA-council 20260902-134741, adjudication A8 + owner ruling E-1) — the vendor / REST / MCP
 * order-creation path now says how an order is fulfilled, and what delivery costs.
 *
 * <h2>The defect this file pins</h2>
 *
 * <p>{@code OrderService.createOrder} set neither {@code fulfilmentType} nor
 * {@code deliveryFeePennies}, so the V45 entity default stood and EVERY order created outside the
 * storefront persisted as DELIVERY with a £0 fee and no address. Measured on the dev runtime: 4 of
 * 60 orders, all DELIVERY, all fee 0, all address NULL, all discriminated by
 * {@code payment_method IS NULL} (only this path leaves it null). Downstream, three consumers were
 * actively wrong: the kitchen ticket rendered a DELIVERY ticket with nowhere to deliver to, the
 * vendor's detail panel rendered a "Delivery address" heading with no lines under it, and — the
 * one that reaches a customer — {@code sendOrderReady} rendered DELIVERY copy.
 *
 * <h2>Why COLLECTION is the default, and why DELIVERY had to stay reachable (E-1)</h2>
 *
 * <p>Defaulting to COLLECTION is correct for what the vendor form actually captures: a walk-in /
 * phone ticket with no address. But defaulting ALONE would have been a regression, because it
 * would make DELIVERY unreachable off the storefront — and the owner has ruled (E-1) that vendors
 * DO take delivery orders by phone, API and MCP. So the request gained an optional
 * {@code fulfilmentType} plus the address block, and DELIVERY applies the shop's fee through the
 * SAME rule the storefront uses ({@link FulfilmentPolicy}). Fix A alone is explicitly forbidden.
 *
 * <p>The last test in this file is the one that closes A8's named harm end to end: it creates an
 * order through {@code OrderService} and feeds the PERSISTED fulfilment type into the REAL
 * {@link EmailNotificationService}, so "a phone-in delivery customer is told to come and collect"
 * is asserted against, not reasoned about.
 */
@ExtendWith(MockitoExtension.class)
class OrderCreateFulfilmentTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ShopRepository shopRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderStateMachineService stateMachineService;
    @Mock private OrderMapper orderMapper;
    @Mock private OrderEventPublisher eventPublisher;
    @Mock private FinancialTransactionService financialTransactionService;
    @Mock private StockService stockService;
    @Mock private ShopAccessService shopAccessService;

    /** Real, not mocked — see the note in OrderServiceTest (COR-5). */
    @Spy private OrderNumberGenerator orderNumberGenerator = new OrderNumberGenerator();

    @InjectMocks private OrderService orderService;

    private UUID tenantId;
    private UUID shopId;
    private UUID productId;
    private Shop shop;
    private Product product;

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        productId = UUID.randomUUID();
        TenantContext.set(tenantId);
        lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);

        shop = new Shop();
        setField(shop, "id", shopId);
        shop.setTenantId(tenantId);
        shop.setName("Brixton Kitchen");
        // The live brixton configuration, so the arithmetic below is the arithmetic that ships.
        shop.setDeliveryFeePennies(399L);
        shop.setFreeDeliveryThresholdPennies(2000L);

        product = new Product();
        setField(product, "id", productId);
        product.setTenantId(tenantId);
        product.setSku("JOLLOF-1");
        product.setTitle("Jollof Rice");
        product.setPricePennies(899L);

        lenient().when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));
        lenient().when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        lenient().when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            setField(order, "createdAt", OffsetDateTime.now());
            return order;
        });
    }

    private CreateOrderRequest requestFor(int quantity) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        request.setCustomerName("Phone Caller");
        request.setCustomerEmail("caller@example.com");
        request.setCustomerPhone("07700900000");
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        request.setItems(List.of(item));
        return request;
    }

    private Order created(CreateOrderRequest request) {
        orderService.createOrder(request);
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("COR-1/A8: with no fulfilmentType a vendor order is COLLECTION with a £0 fee and no address")
    void defaultsToCollection() {
        Order order = created(requestFor(1));

        assertEquals(FulfilmentType.COLLECTION, order.getFulfilmentType(),
                "A8: the vendor form captures no address, so the order is a collection ticket");
        assertEquals(0L, order.getDeliveryFeePennies(),
                "a COLLECTION order's £0 fee must be CORRECT, not accidentally right");
        assertNull(order.getAddressLine1(), "no address is captured, so none may be persisted");
    }

    @Test
    @DisplayName("COR-1/E-1: an explicit DELIVERY order charges the shop's fee and persists the address")
    void deliveryChargesTheShopFeeAndKeepsTheAddress() {
        CreateOrderRequest request = requestFor(1); // 899p subtotal, below the 2000p threshold
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("12 Coldharbour Lane");
        request.setAddressLine2("Flat 3");
        request.setAddressCity("London");
        request.setAddressPostcode("SW9 8LF");

        Order order = created(request);

        assertEquals(FulfilmentType.DELIVERY, order.getFulfilmentType());
        assertEquals(399L, order.getDeliveryFeePennies(),
                "E-1: a phone-in delivery order must carry the shop's fee, not £0");
        assertEquals("12 Coldharbour Lane", order.getAddressLine1());
        assertEquals("Flat 3", order.getAddressLine2());
        assertEquals("London", order.getAddressCity());
        assertEquals("SW9 8LF", order.getAddressPostcode());
        // The fee reaches the money: total = subtotal + fee, and VAT follows the combined gross.
        assertEquals(899L, order.getSubtotalPennies());
        assertEquals(899L + 399L, order.getTotalAmountPennies());
    }

    @Test
    @DisplayName("COR-1: the free-delivery waiver applies on the vendor path too — the SAME rule, not a copy")
    void deliveryWaivesTheFeeAboveTheThreshold() {
        CreateOrderRequest request = requestFor(3); // 3 x 899 = 2697p, over the 2000p threshold
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("12 Coldharbour Lane");
        request.setAddressCity("London");
        request.setAddressPostcode("SW9 8LF");

        Order order = created(request);

        assertEquals(0L, order.getDeliveryFeePennies(),
                "the shop's free-delivery threshold must be honoured off the storefront as well");
        assertEquals(2697L, order.getTotalAmountPennies());
    }

    @Test
    @DisplayName("COR-1: a DELIVERY order with no address is rejected — an unfulfillable order is never persisted")
    void deliveryWithoutAnAddressIsRejected() {
        CreateOrderRequest request = requestFor(1);
        request.setFulfilmentType("DELIVERY");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        assertEquals(FulfilmentPolicy.MISSING_ADDRESS_MESSAGE, e.getMessage());
        verify(orderRepository, times(0)).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("COR-1: an address sent with a COLLECTION order is NOT persisted — the type decides, not the payload")
    void collectionNeverPersistsAnAddress() {
        CreateOrderRequest request = requestFor(1);
        request.setFulfilmentType("COLLECTION");
        request.setAddressLine1("12 Coldharbour Lane");
        request.setAddressCity("London");
        request.setAddressPostcode("SW9 8LF");

        Order order = created(request);

        assertEquals(FulfilmentType.COLLECTION, order.getFulfilmentType());
        assertNull(order.getAddressLine1(), "a collection order has no delivery address to keep");
        assertNull(order.getAddressCity());
        assertNull(order.getAddressPostcode());
        assertEquals(0L, order.getDeliveryFeePennies());
    }

    @Test
    @DisplayName("COR-1: an unknown fulfilmentType is a 400, not a silent default")
    void unknownFulfilmentTypeIsRejected() {
        CreateOrderRequest request = requestFor(1);
        request.setFulfilmentType("TELEPORT");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> orderService.createOrder(request));
        assertEquals("Invalid fulfilment type: TELEPORT (expected DELIVERY or COLLECTION)", e.getMessage());
    }

    /**
     * COR-1 contract half. The classification is worthless if it never leaves the server, and
     * before this change OrderDto exposed NEITHER field — so the vendor list could not show what
     * kind of order it was looking at.
     *
     * <p>This uses the REAL MapStruct-generated {@code OrderMapperImpl}, not the mock the rest of
     * this file uses, because the thing under test is the generated mapping itself. A mock would
     * assert only that the test's own lambda copies fields.
     */
    @Test
    @DisplayName("COR-1: OrderDto carries fulfilmentType and deliveryFeePennies out to the vendor list")
    void orderDtoExposesFulfilmentAndFee() {
        CreateOrderRequest request = requestFor(1);
        request.setFulfilmentType("DELIVERY");
        request.setAddressLine1("12 Coldharbour Lane");
        request.setAddressCity("London");
        request.setAddressPostcode("SW9 8LF");
        Order order = created(request);

        uk.jtoye.core.order.dto.OrderDto dto = new OrderMapperImpl().toDto(order);

        assertEquals(FulfilmentType.DELIVERY, dto.getFulfilmentType());
        assertEquals(399L, dto.getDeliveryFeePennies());
        // The pre-existing fields must still map — an added @Mapping must not displace one.
        assertEquals(order.getOrderNumber(), dto.getOrderNumber());
        assertEquals(order.getTotalAmountPennies(), dto.getTotalAmountPennies());
    }

    /**
     * A8's named harm, closed end to end rather than argued. The order is created through the real
     * service; its PERSISTED fulfilment type is then handed to the REAL email service, exactly as
     * {@code OrderStateChangeListener} does at the READY transition. Only {@code JavaMailSender} is
     * mocked, so the assertion reads the message body that would have reached the customer.
     */
    @Test
    @DisplayName("COR-1/A8: a vendor DELIVERY order is emailed delivery copy; a defaulted one is emailed collection copy")
    void theReadyEmailFollowsTheOrderTheVendorActuallyCreated() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationService emailService = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(emailService, "emailEnabled", true);
        ReflectionTestUtils.setField(emailService, "trackingBaseUrl", "https://shop.jtoye.uk");

        // (a) an explicit phone-in DELIVERY order
        CreateOrderRequest delivery = requestFor(1);
        delivery.setFulfilmentType("DELIVERY");
        delivery.setAddressLine1("12 Coldharbour Lane");
        delivery.setAddressCity("London");
        delivery.setAddressPostcode("SW9 8LF");
        Order deliveryOrder = created(delivery);

        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), tenantId, "ORD-COR1-DEL", OrderStatus.PREPARING,
                OrderStatus.READY, OffsetDateTime.now());
        emailService.sendOrderReady(event, "caller@example.com", deliveryOrder.getFulfilmentType());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        String deliveryBody = captor.getValue().getText();
        org.junit.jupiter.api.Assertions.assertTrue(
                deliveryBody.toLowerCase().contains("deliver"),
                "E-1: a phone-in DELIVERY customer must be told the order comes to them");
        org.junit.jupiter.api.Assertions.assertFalse(
                deliveryBody.toLowerCase().contains("collect"),
                "#502: a delivery customer must never be told to collect");
    }

    @Test
    @DisplayName("COR-1/A8: a DEFAULTED vendor order is emailed collection copy — no false delivery promise")
    void aDefaultedVendorOrderIsEmailedCollectionCopy() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailNotificationService emailService = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(emailService, "emailEnabled", true);
        ReflectionTestUtils.setField(emailService, "trackingBaseUrl", "https://shop.jtoye.uk");

        Order walkIn = created(requestFor(1)); // no fulfilmentType -> COLLECTION

        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), tenantId, "ORD-COR1-COL", OrderStatus.PREPARING,
                OrderStatus.READY, OffsetDateTime.now());
        emailService.sendOrderReady(event, "caller@example.com", walkIn.getFulfilmentType());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        String body = captor.getValue().getText();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("ready for collection"),
                "a walk-in ticket must not promise a delivery nobody has an address for");
    }
}
