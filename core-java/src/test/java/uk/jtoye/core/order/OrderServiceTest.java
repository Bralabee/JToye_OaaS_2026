package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderService.
 * Tests service layer business logic with mocked dependencies.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OrderStateMachineService stateMachineService;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderEventPublisher eventPublisher;

    @Mock
    private FinancialTransactionService financialTransactionService;

    @Mock
    private StockService stockService;

    @Mock
    private ShopAccessService shopAccessService;

    @InjectMocks
    private OrderService orderService;

    private UUID tenantId;
    private UUID shopId;
    private UUID productId;
    private UUID orderId;
    private Shop testShop;
    private Product testProduct;
    private Order testOrder;

    /**
     * Helper method to set private fields using reflection.
     * Needed for auto-generated fields like id and createdAt.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        productId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        // Set up tenant context
        TenantContext.set(tenantId);

        // Phase 23 (VSA-02): these unit tests assert the pre-scoping behaviour, so run as
        // a GROUP_ADMIN — require(...) is a no-op and read-scope list methods take the
        // full-tenant query path (existing assertions unchanged). Lenient: not every test
        // exercises a gated method.
        lenient().when(shopAccessService.isGroupAdmin()).thenReturn(true);

        // Create test shop (using reflection to set auto-generated fields)
        testShop = new Shop();
        setField(testShop, "id", shopId);
        testShop.setTenantId(tenantId);
        testShop.setName("Test Shop");
        testShop.setAddress("123 Test St");

        // Create test product (using reflection to set auto-generated fields)
        testProduct = new Product();
        setField(testProduct, "id", productId);
        testProduct.setTenantId(tenantId);
        testProduct.setSku("TEST-001");
        testProduct.setTitle("Test Product");
        testProduct.setPricePennies(1000L); // £10.00

        // Create test order (using reflection to set auto-generated fields)
        testOrder = new Order();
        setField(testOrder, "id", orderId);
        testOrder.setTenantId(tenantId);
        testOrder.setShopId(shopId);
        testOrder.setOrderNumber("ORD-12345");
        testOrder.setStatus(OrderStatus.DRAFT);
        testOrder.setCustomerName("John Doe");
        testOrder.setCustomerEmail("john@example.com");
        testOrder.setTotalAmountPennies(3000L);
        setField(testOrder, "createdAt", OffsetDateTime.now());
        testOrder.setUpdatedAt(OffsetDateTime.now());

        // Mock OrderMapper to return a DTO with all fields copied (lenient to avoid UnnecessaryStubbingException)
        lenient().when(orderMapper.toDto(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            OrderDto dto = new OrderDto();
            dto.setId(order.getId());
            dto.setTenantId(order.getTenantId());
            dto.setShopId(order.getShopId());
            dto.setOrderNumber(order.getOrderNumber());
            dto.setStatus(order.getStatus());
            dto.setCustomerName(order.getCustomerName());
            dto.setCustomerEmail(order.getCustomerEmail());
            dto.setCustomerPhone(order.getCustomerPhone());
            dto.setNotes(order.getNotes());
            dto.setTotalAmountPennies(order.getTotalAmountPennies());
            dto.setCreatedAt(order.getCreatedAt());
            dto.setUpdatedAt(order.getUpdatedAt());
            return dto;
        });
    }

    @Test
    @DisplayName("createOrder - Success with valid request")
    void testCreateOrder_Success() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        request.setCustomerName("John Doe");
        request.setCustomerEmail("john@example.com");
        request.setCustomerPhone("123-456-7890");
        request.setNotes("Test order");

        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(productId);
        itemRequest.setQuantity(3);
        request.setItems(List.of(itemRequest));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", orderId);
            return order;
        });

        // When
        OrderDto result = orderService.createOrder(request);

        // Then
        assertNotNull(result);
        assertEquals(tenantId, result.getTenantId());
        assertEquals(shopId, result.getShopId());
        assertEquals(OrderStatus.DRAFT, result.getStatus());
        assertEquals("John Doe", result.getCustomerName());
        assertEquals("john@example.com", result.getCustomerEmail());
        assertEquals(3000L, result.getTotalAmountPennies()); // 3 * 1000

        verify(shopRepository).findById(shopId);
        verify(productRepository).findById(productId);
        verify(orderRepository).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("createOrder - Fails when tenant context not set")
    void testCreateOrder_MissingTenant() {
        // Given
        TenantContext.clear();
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        request.setItems(List.of());

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            orderService.createOrder(request);
        });

        assertEquals("Tenant context not set", exception.getMessage());
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("createOrder - Fails when shop not found")
    void testCreateOrder_InvalidShop() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        request.setItems(List.of());

        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            orderService.createOrder(request);
        });

        assertTrue(exception.getMessage().contains("Shop not found"));
        verify(shopRepository).findById(shopId);
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("createOrder - Fails when product not found")
    void testCreateOrder_InvalidProduct() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);

        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(productId);
        itemRequest.setQuantity(1);
        request.setItems(List.of(itemRequest));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            orderService.createOrder(request);
        });

        assertTrue(exception.getMessage().contains("Product not found"));
        verify(shopRepository).findById(shopId);
        verify(productRepository).findById(productId);
        verify(orderRepository, never()).saveAndFlush(any(Order.class));
    }

    @Test
    @DisplayName("createOrder - Calculates total correctly with multiple items")
    void testCreateOrder_CalculatesTotalWithMultipleItems() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);

        UUID productId2 = UUID.randomUUID();
        Product product2 = new Product();
        setField(product2, "id", productId2);
        product2.setPricePennies(2000L); // £20.00

        OrderItemRequest item1 = new OrderItemRequest();
        item1.setProductId(productId);
        item1.setQuantity(2); // 2 * 1000 = 2000

        OrderItemRequest item2 = new OrderItemRequest();
        item2.setProductId(productId2);
        item2.setQuantity(3); // 3 * 2000 = 6000

        request.setItems(Arrays.asList(item1, item2));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(productRepository.findById(productId2)).thenReturn(Optional.of(product2));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.createOrder(request);

        // Then
        assertEquals(8000L, result.getTotalAmountPennies()); // 2000 + 6000
    }

    @Test
    @DisplayName("getOrderById - Success when order exists")
    void testGetOrderById_Success() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        // When
        Optional<OrderDto> result = orderService.getOrderById(orderId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(orderId, result.get().getId());
        assertEquals("ORD-12345", result.get().getOrderNumber());
        verify(orderRepository).findById(orderId);
    }

    @Test
    @DisplayName("getOrderById - Returns empty when order not found")
    void testGetOrderById_NotFound() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When
        Optional<OrderDto> result = orderService.getOrderById(orderId);

        // Then
        assertFalse(result.isPresent());
        verify(orderRepository).findById(orderId);
    }

    @Test
    @DisplayName("getOrderByNumber - Success when order exists")
    void testGetOrderByNumber_Success() {
        // Given
        String orderNumber = "ORD-12345";
        when(orderRepository.findByOrderNumber(orderNumber)).thenReturn(Optional.of(testOrder));

        // When
        Optional<OrderDto> result = orderService.getOrderByNumber(orderNumber);

        // Then
        assertTrue(result.isPresent());
        assertEquals(orderNumber, result.get().getOrderNumber());
        verify(orderRepository).findByOrderNumber(orderNumber);
    }

    @Test
    @DisplayName("getAllOrders - Returns paginated results")
    void testGetAllOrders_Paginated() {
        // Given
        Pageable pageable = PageRequest.of(0, 10);
        Page<Order> orderPage = new PageImpl<>(List.of(testOrder), pageable, 1);
        when(orderRepository.findAll(pageable)).thenReturn(orderPage);

        // When
        Page<OrderDto> result = orderService.getAllOrders(pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(orderId, result.getContent().get(0).getId());
        verify(orderRepository).findAll(pageable);
    }

    @Test
    @DisplayName("getOrdersByStatus - Returns paginated orders with matching status")
    void testGetOrdersByStatus_Success() {
        // Given
        OrderStatus status = OrderStatus.PENDING;
        testOrder.setStatus(status);
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findByStatus(status, pageable))
                .thenReturn(new PageImpl<>(List.of(testOrder), pageable, 1));

        // When
        Page<OrderDto> result = orderService.getOrdersByStatus(status, pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(status, result.getContent().get(0).getStatus());
        verify(orderRepository).findByStatus(status, pageable);
    }

    @Test
    @DisplayName("getOrdersByShop - Returns paginated orders for specific shop")
    void testGetOrdersByShop_Success() {
        // Given
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findByShopId(shopId, pageable))
                .thenReturn(new PageImpl<>(List.of(testOrder), pageable, 1));

        // When
        Page<OrderDto> result = orderService.getOrdersByShop(shopId, pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(shopId, result.getContent().get(0).getShopId());
        verify(orderRepository).findByShopId(shopId, pageable);
    }

    @Test
    @DisplayName("getOrdersByCustomer - Returns paginated orders for specific customer")
    void testGetOrdersByCustomer_Success() {
        // Given
        UUID customerId = UUID.randomUUID();
        testOrder.setCustomerId(customerId);
        Pageable pageable = PageRequest.of(0, 20);
        when(orderRepository.findByCustomerId(customerId, pageable))
                .thenReturn(new PageImpl<>(List.of(testOrder), pageable, 1));

        // When
        Page<OrderDto> result = orderService.getOrdersByCustomer(customerId, pageable);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(testOrder.getOrderNumber(), result.getContent().get(0).getOrderNumber());
        verify(orderRepository).findByCustomerId(customerId, pageable);
    }

    @Test
    @DisplayName("submitOrder - Transitions DRAFT to PENDING")
    void testSubmitOrder_Success() {
        // Given
        testOrder.setStatus(OrderStatus.DRAFT);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.SUBMIT))
                .thenReturn(OrderStatus.PENDING);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.submitOrder(orderId);

        // Then
        assertNotNull(result);
        assertEquals(OrderStatus.PENDING, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.DRAFT, OrderEvent.SUBMIT);
        verify(orderRepository).save(any(Order.class));
    }

    @Test
    @DisplayName("confirmOrder - Transitions PENDING to CONFIRMED")
    void testConfirmOrder_Success() {
        // Given
        testOrder.setStatus(OrderStatus.PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CONFIRM))
                .thenReturn(OrderStatus.CONFIRMED);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.confirmOrder(orderId);

        // Then
        assertEquals(OrderStatus.CONFIRMED, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CONFIRM);
    }

    @Test
    @DisplayName("startPreparation - Transitions CONFIRMED to PREPARING")
    void testStartPreparation_Success() {
        // Given
        testOrder.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.START_PREP))
                .thenReturn(OrderStatus.PREPARING);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.startPreparation(orderId);

        // Then
        assertEquals(OrderStatus.PREPARING, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.START_PREP);
    }

    @Test
    @DisplayName("markOrderReady - Transitions PREPARING to READY")
    void testMarkOrderReady_Success() {
        // Given
        testOrder.setStatus(OrderStatus.PREPARING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.PREPARING, OrderEvent.MARK_READY))
                .thenReturn(OrderStatus.READY);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.markOrderReady(orderId);

        // Then
        assertEquals(OrderStatus.READY, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.PREPARING, OrderEvent.MARK_READY);
    }

    @Test
    @DisplayName("completeOrder - Transitions READY to COMPLETED")
    void testCompleteOrder_Success() {
        // Given
        testOrder.setStatus(OrderStatus.READY);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.READY, OrderEvent.COMPLETE))
                .thenReturn(OrderStatus.COMPLETED);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.completeOrder(orderId);

        // Then
        assertEquals(OrderStatus.COMPLETED, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.READY, OrderEvent.COMPLETE);
    }

    @Test
    @DisplayName("cancelOrder - Transitions any status to CANCELLED")
    void testCancelOrder_Success() {
        // Given
        testOrder.setStatus(OrderStatus.PENDING);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CANCEL))
                .thenReturn(OrderStatus.CANCELLED);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        OrderDto result = orderService.cancelOrder(orderId);

        // Then
        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        verify(stateMachineService).sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CANCEL);
    }

    @Test
    @DisplayName("State transition - Fails when order not found")
    void testStateTransition_OrderNotFound() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            orderService.submitOrder(orderId);
        });

        assertTrue(exception.getMessage().contains("Order not found"));
        verify(orderRepository).findById(orderId);
        verify(stateMachineService, never()).sendEvent(any(), any(), any());
    }

    @Test
    @DisplayName("State transition - Propagates InvalidStateTransitionException")
    void testStateTransition_InvalidTransition() {
        // Given
        testOrder.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.COMPLETED, OrderEvent.SUBMIT))
                .thenThrow(new InvalidStateTransitionException("Cannot transition from COMPLETED"));

        // When & Then
        InvalidStateTransitionException exception = assertThrows(InvalidStateTransitionException.class, () -> {
            orderService.submitOrder(orderId);
        });

        assertTrue(exception.getMessage().contains("Cannot transition from COMPLETED"));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    @DisplayName("deleteOrder - Success when order exists")
    void testDeleteOrder_Success() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        // When
        orderService.deleteOrder(orderId);

        // Then
        verify(orderRepository).findById(orderId);
        verify(orderRepository).delete(testOrder);
    }

    @Test
    @DisplayName("deleteOrder - Fails when order not found")
    void testDeleteOrder_NotFound() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        // When & Then
        ResourceNotFoundException exception = assertThrows(ResourceNotFoundException.class, () -> {
            orderService.deleteOrder(orderId);
        });

        assertTrue(exception.getMessage().contains("Order not found"));
        verify(orderRepository).findById(orderId);
        verify(orderRepository, never()).delete(any(Order.class));
    }

    @Test
    @DisplayName("Order number generation - Creates unique order numbers")
    void testOrderNumberGeneration_Uniqueness() {
        // Given
        CreateOrderRequest request1 = new CreateOrderRequest();
        request1.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request1.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When - Create two orders
        OrderDto order1 = orderService.createOrder(request1);
        OrderDto order2 = orderService.createOrder(request1);

        // Then - Order numbers should be unique
        assertNotNull(order1.getOrderNumber());
        assertNotNull(order2.getOrderNumber());
        assertNotEquals(order1.getOrderNumber(), order2.getOrderNumber());
        assertTrue(order1.getOrderNumber().startsWith("ORD-"));
        assertTrue(order2.getOrderNumber().startsWith("ORD-"));
    }

    @Test
    @DisplayName("DTO mapping - Converts Order entity to DTO correctly")
    void testDtoMapping_CorrectFieldMapping() {
        // Given
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));

        // When
        Optional<OrderDto> result = orderService.getOrderById(orderId);

        // Then
        assertTrue(result.isPresent());
        OrderDto dto = result.get();
        assertEquals(testOrder.getId(), dto.getId());
        assertEquals(testOrder.getTenantId(), dto.getTenantId());
        assertEquals(testOrder.getShopId(), dto.getShopId());
        assertEquals(testOrder.getOrderNumber(), dto.getOrderNumber());
        assertEquals(testOrder.getStatus(), dto.getStatus());
        assertEquals(testOrder.getCustomerName(), dto.getCustomerName());
        assertEquals(testOrder.getCustomerEmail(), dto.getCustomerEmail());
        assertEquals(testOrder.getTotalAmountPennies(), dto.getTotalAmountPennies());
        assertEquals(testOrder.getCreatedAt(), dto.getCreatedAt());
        assertEquals(testOrder.getUpdatedAt(), dto.getUpdatedAt());
    }

    @Test
    @DisplayName("createOrder - Sets tenant ID on order items")
    void testCreateOrder_SetsTenantIdOnOrderItems() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest itemRequest = new OrderItemRequest();
        itemRequest.setProductId(productId);
        itemRequest.setQuantity(1);
        request.setItems(List.of(itemRequest));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        when(orderRepository.saveAndFlush(orderCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        orderService.createOrder(request);

        // Then
        Order savedOrder = orderCaptor.getValue();
        assertNotNull(savedOrder.getItems());
        assertEquals(1, savedOrder.getItems().size());
        assertEquals(tenantId, savedOrder.getItems().get(0).getTenantId());
    }

    @Test
    @DisplayName("State transition - Updates updatedAt timestamp")
    void testStateTransition_UpdatesTimestamp() {
        // Given
        OffsetDateTime originalTimestamp = OffsetDateTime.now().minusHours(1);
        testOrder.setUpdatedAt(originalTimestamp);
        testOrder.setStatus(OrderStatus.DRAFT);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(any(), any(), any())).thenReturn(OrderStatus.PENDING);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        when(orderRepository.save(orderCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        orderService.submitOrder(orderId);

        // Then
        Order savedOrder = orderCaptor.getValue();
        assertTrue(savedOrder.getUpdatedAt().isAfter(originalTimestamp));
    }

    // ========================================
    // NEW TESTS: Order Number Generation
    // ========================================

    @Test
    @DisplayName("Order number format - Matches expected pattern")
    void testOrderNumberFormat_MatchesPattern() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When
        OrderDto result = orderService.createOrder(request);

        // Then
        String orderNumber = result.getOrderNumber();
        assertNotNull(orderNumber);

        // Pattern: ORD-{8 hex chars}-{YYYYMMDD}-{8 hex chars}
        Pattern pattern = Pattern.compile("^ORD-[0-9A-F]{8}-\\d{8}-[0-9A-F]{8}$");
        assertTrue(pattern.matcher(orderNumber).matches(),
                "Order number should match pattern ORD-XXXXXXXX-YYYYMMDD-XXXXXXXX, got: " + orderNumber);
    }

    @Test
    @DisplayName("Order number - Contains tenant prefix derived from tenant ID")
    void testOrderNumberFormat_ContainsTenantPrefix() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When
        OrderDto result = orderService.createOrder(request);

        // Then
        String orderNumber = result.getOrderNumber();
        String[] parts = orderNumber.split("-");
        assertEquals(4, parts.length, "Order number should have 4 parts separated by hyphens");

        // Extract expected tenant prefix (first 8 hex chars of tenant UUID)
        String expectedTenantPrefix = tenantId.toString().replace("-", "").substring(0, 8).toUpperCase();
        assertEquals(expectedTenantPrefix, parts[1],
                "Tenant prefix should match first 8 characters of tenant UUID");
    }

    @Test
    @DisplayName("Order number - Contains current date")
    void testOrderNumberFormat_ContainsCurrentDate() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When
        OrderDto result = orderService.createOrder(request);

        // Then
        String orderNumber = result.getOrderNumber();
        String[] parts = orderNumber.split("-");
        assertEquals(4, parts.length);

        // Extract date part and verify it matches today
        String datePart = parts[2];
        String expectedDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        assertEquals(expectedDate, datePart,
                "Date part should match current date in YYYYMMDD format");
    }

    @Test
    @DisplayName("Order number uniqueness - 1000 orders generate unique numbers")
    void testOrderNumberGeneration_UniquenessAtScale() {
        // Given
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When - Generate 1000 order numbers
        Set<String> orderNumbers = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            OrderDto result = orderService.createOrder(request);
            orderNumbers.add(result.getOrderNumber());
        }

        // Then - All should be unique
        assertEquals(1000, orderNumbers.size(),
                "All 1000 generated order numbers should be unique");
    }

    @Test
    @DisplayName("Order number - Different tenants get different prefixes")
    void testOrderNumberGeneration_DifferentTenantsDifferentPrefixes() {
        // Given
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When - Create orders for different tenants
        TenantContext.set(tenant1);
        OrderDto order1 = orderService.createOrder(request);

        TenantContext.set(tenant2);
        OrderDto order2 = orderService.createOrder(request);

        // Then - Tenant prefixes should be different
        String prefix1 = order1.getOrderNumber().split("-")[1];
        String prefix2 = order2.getOrderNumber().split("-")[1];

        assertNotEquals(prefix1, prefix2,
                "Different tenants should have different order number prefixes");

        // Verify prefixes match their respective tenant IDs
        String expectedPrefix1 = tenant1.toString().replace("-", "").substring(0, 8).toUpperCase();
        String expectedPrefix2 = tenant2.toString().replace("-", "").substring(0, 8).toUpperCase();

        assertEquals(expectedPrefix1, prefix1);
        assertEquals(expectedPrefix2, prefix2);
    }

    @Test
    @DisplayName("Order number - Random suffix provides collision resistance")
    void testOrderNumberGeneration_RandomSuffixUniqueness() {
        // Given - Same tenant, same date, should still be unique
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(shopId);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        request.setItems(List.of(item));

        when(shopRepository.findById(shopId)).thenReturn(Optional.of(testShop));
        when(productRepository.findById(productId)).thenReturn(Optional.of(testProduct));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            setField(order, "id", UUID.randomUUID());
            return order;
        });

        // When - Create multiple orders rapidly
        OrderDto order1 = orderService.createOrder(request);
        OrderDto order2 = orderService.createOrder(request);
        OrderDto order3 = orderService.createOrder(request);

        // Then - All should have same tenant and date prefix but different suffixes
        String[] parts1 = order1.getOrderNumber().split("-");
        String[] parts2 = order2.getOrderNumber().split("-");
        String[] parts3 = order3.getOrderNumber().split("-");

        // Same tenant prefix
        assertEquals(parts1[1], parts2[1]);
        assertEquals(parts1[1], parts3[1]);

        // Same date
        assertEquals(parts1[2], parts2[2]);
        assertEquals(parts1[2], parts3[2]);

        // Different random suffixes
        assertNotEquals(parts1[3], parts2[3]);
        assertNotEquals(parts1[3], parts3[3]);
        assertNotEquals(parts2[3], parts3[3]);
    }

    // ========================================
    // NEW TESTS: Stock service delegation (CQ-01 — Phase 14 replaces the
    // batching helper with StockService.decrementForOrder / restoreForOrder).
    // Batching + retry + version behaviour is now pinned by StockServiceTest
    // and ConcurrentStockDecrementIntegrationTest — these tests verify only
    // that OrderService.transitionOrder delegates to the right helper.
    // ========================================

    @Test
    @DisplayName("confirmOrder - Delegates stock decrement to StockService.decrementForOrder")
    void testConfirmOrder_DelegatesToStockService() {
        // Given: a multi-item order on PENDING status transitioning to CONFIRMED
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.addItem(orderItem(p1, 1));
        testOrder.addItem(orderItem(p2, 2));

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.PENDING, OrderEvent.CONFIRM))
                .thenReturn(OrderStatus.CONFIRMED);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // When
        orderService.confirmOrder(orderId);

        // Then: OrderService delegated to StockService — actual retry/version
        // behaviour is covered by StockServiceTest + ConcurrentStockDecrementIntegrationTest.
        verify(stockService, times(1)).decrementForOrder(anyList());
        verify(stockService, never()).restoreForOrder(anyList());
        // ProductRepository must NOT be touched directly by OrderService — that
        // was the pre-Phase-14 anti-pattern (adjustStockInBatch) that masked
        // oversells via Math.max(0, ...).
        verify(productRepository, never()).findAllById(anyList());
        verify(productRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("cancelOrder (after CONFIRMED) - Delegates stock restore to StockService.restoreForOrder")
    void testCancelOrder_DelegatesRestoreToStockService() {
        // Given: a confirmed order being cancelled
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        testOrder.setStatus(OrderStatus.CONFIRMED);
        testOrder.addItem(orderItem(p1, 2));
        testOrder.addItem(orderItem(p2, 3));

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(testOrder));
        when(stateMachineService.sendEvent(orderId, OrderStatus.CONFIRMED, OrderEvent.CANCEL))
                .thenReturn(OrderStatus.CANCELLED);
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

        // When
        orderService.cancelOrder(orderId);

        // Then: restore delegation; decrement must NOT fire on the cancel path.
        verify(stockService, times(1)).restoreForOrder(anyList());
        verify(stockService, never()).decrementForOrder(anyList());
        verify(productRepository, never()).findAllById(anyList());
        verify(productRepository, never()).saveAll(any());
    }

    private Product stockedProduct(UUID id, String sku, int stock) {
        Product p = new Product();
        setField(p, "id", id);
        p.setTenantId(tenantId);
        p.setSku(sku);
        p.setTitle(sku);
        p.setPricePennies(1000L);
        p.setQuantityInStock(stock);
        return p;
    }

    private OrderItem orderItem(UUID productId, int quantity) {
        OrderItem item = new OrderItem(productId, quantity, 1000L);
        item.setTenantId(tenantId);
        return item;
    }

    @Test
    @DisplayName("Order number - Backward compatibility with old format")
    void testOrderNumberFormat_BackwardCompatibility() {
        // Given - An existing order with old format
        Order oldOrder = new Order();
        setField(oldOrder, "id", UUID.randomUUID());
        oldOrder.setTenantId(tenantId);
        oldOrder.setShopId(shopId);
        oldOrder.setOrderNumber("ORD-" + UUID.randomUUID().toString()); // Old format
        oldOrder.setStatus(OrderStatus.COMPLETED);

        when(orderRepository.findByOrderNumber(oldOrder.getOrderNumber()))
                .thenReturn(Optional.of(oldOrder));

        // When - Retrieve old order
        Optional<OrderDto> result = orderService.getOrderByNumber(oldOrder.getOrderNumber());

        // Then - Old format should still work
        assertTrue(result.isPresent());
        assertEquals(oldOrder.getOrderNumber(), result.get().getOrderNumber());

        // Verify old format is different from new format
        assertFalse(oldOrder.getOrderNumber().matches("^ORD-[0-9A-F]{8}-\\d{8}-[0-9A-F]{8}$"),
                "Old format should not match new pattern");
    }
}
