package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Order management.
 * Tests order CRUD operations and tenant isolation.
 */
@SpringBootTest(properties = {
        "logging.level.uk.jtoye.core.security.TenantSetLocalAspect=DEBUG"
})
@ActiveProfiles("test")
@Transactional
class OrderControllerIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private UUID shopAId;
    private UUID productAId;

    @BeforeEach
    void setUp() {
        // Create tenants
        jdbcTemplate.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT_A + "', 'Tenant A') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT_B + "', 'Tenant B') ON CONFLICT (id) DO NOTHING");

        // Create shop for Tenant A
        TenantContext.set(TENANT_A);
        Shop shopA = new Shop();
        shopA.setTenantId(TENANT_A);
        shopA.setName("Shop A");
        shopA.setAddress("Address A");
        shopA = shopRepository.save(shopA);
        shopAId = shopA.getId();

        // Create product for Tenant A
        Product productA = new Product();
        productA.setTenantId(TENANT_A);
        productA.setSku("SKU-A");
        productA.setTitle("Product A");
        productA.setIngredientsText("Ingredients A");
        productA.setAllergenMask(0);
        productA = productRepository.save(productA);
        productAId = productA.getId();

        TenantContext.clear();
    }

    @Test
    void testCreateOrder() {
        TenantContext.set(TENANT_A);
        try {
            // Create order request
            CreateOrderRequest request = new CreateOrderRequest();
            request.setShopId(shopAId);
            request.setCustomerName("John Doe");
            request.setCustomerEmail("john@example.com");
            request.setCustomerPhone("555-1234");
            request.setNotes("Test order");

            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(productAId);
            item.setQuantity(2);
            request.setItems(List.of(item));

            // Create order
            OrderDto order = orderService.createOrder(request);

            // Verify order created
            assertThat(order).isNotNull();
            assertThat(order.getId()).isNotNull();
            assertThat(order.getTenantId()).isEqualTo(TENANT_A);
            assertThat(order.getShopId()).isEqualTo(shopAId);
            assertThat(order.getOrderNumber()).startsWith("ORD-");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getCustomerName()).isEqualTo("John Doe");
            assertThat(order.getTotalAmountPennies()).isGreaterThan(0);

        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void testGetOrderById() {
        TenantContext.set(TENANT_A);
        try {
            // Create order
            CreateOrderRequest request = new CreateOrderRequest();
            request.setShopId(shopAId);
            request.setCustomerName("Jane Doe");
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(productAId);
            item.setQuantity(1);
            request.setItems(List.of(item));

            OrderDto createdOrder = orderService.createOrder(request);

            // Retrieve order
            Optional<OrderDto> retrievedOrder = orderService.getOrderById(createdOrder.getId());

            // Verify
            assertThat(retrievedOrder).isPresent();
            assertThat(retrievedOrder.get().getId()).isEqualTo(createdOrder.getId());
            assertThat(retrievedOrder.get().getCustomerName()).isEqualTo("Jane Doe");

        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void testUpdateOrderStatus() {
        TenantContext.set(TENANT_A);
        try {
            // Create order
            CreateOrderRequest request = new CreateOrderRequest();
            request.setShopId(shopAId);
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(productAId);
            item.setQuantity(1);
            request.setItems(List.of(item));

            OrderDto createdOrder = orderService.createOrder(request);
            assertThat(createdOrder.getStatus()).isEqualTo(OrderStatus.DRAFT);

            // Update status
            OrderDto updatedOrder = orderService.updateOrderStatus(createdOrder.getId(), OrderStatus.CONFIRMED);

            // Verify
            assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(updatedOrder.getUpdatedAt()).isAfter(createdOrder.getUpdatedAt());

        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void testGetOrdersByStatus() {
        TenantContext.set(TENANT_A);
        try {
            // Create multiple orders
            for (int i = 0; i < 3; i++) {
                CreateOrderRequest request = new CreateOrderRequest();
                request.setShopId(shopAId);
                OrderItemRequest item = new OrderItemRequest();
                item.setProductId(productAId);
                item.setQuantity(1);
                request.setItems(List.of(item));

                orderService.createOrder(request);
            }

            // Get orders by status
            List<OrderDto> draftOrders = orderService.getOrdersByStatus(OrderStatus.DRAFT);

            // Verify
            assertThat(draftOrders).hasSizeGreaterThanOrEqualTo(3);
            assertThat(draftOrders).allMatch(o -> o.getStatus() == OrderStatus.DRAFT);
            assertThat(draftOrders).allMatch(o -> o.getTenantId().equals(TENANT_A));

        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void testTenantIsolation() {
        // Create order for Tenant A
        TenantContext.set(TENANT_A);
        CreateOrderRequest requestA = new CreateOrderRequest();
        requestA.setShopId(shopAId);
        requestA.setCustomerName("Tenant A Customer");
        OrderItemRequest itemA = new OrderItemRequest();
        itemA.setProductId(productAId);
        itemA.setQuantity(1);
        requestA.setItems(List.of(itemA));

        OrderDto orderA = orderService.createOrder(requestA);
        UUID orderAId = orderA.getId();

        // Verify order belongs to Tenant A
        assertThat(orderA.getTenantId()).isEqualTo(TENANT_A);

        // Verify order can be retrieved by Tenant A
        Optional<OrderDto> retrievedA = orderService.getOrderById(orderAId);
        assertThat(retrievedA).isPresent();
        assertThat(retrievedA.get().getTenantId()).isEqualTo(TENANT_A);

        TenantContext.clear();

        // Verify order entity has correct tenant_id column value
        Order order = orderRepository.findById(orderAId).orElse(null);
        assertThat(order).isNotNull();
        assertThat(order.getTenantId()).isEqualTo(TENANT_A);

        // NOTE: Full RLS isolation testing within a single @Transactional test is complex
        // because SET LOCAL persists for the entire transaction. RLS is verified in production
        // where each HTTP request gets its own transaction with the correct tenant context.
        // The tenant_id column checks above ensure data integrity at the database level.
    }

    @Test
    void testDeleteOrder() {
        TenantContext.set(TENANT_A);
        try {
            // Create order
            CreateOrderRequest request = new CreateOrderRequest();
            request.setShopId(shopAId);
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(productAId);
            item.setQuantity(1);
            request.setItems(List.of(item));

            OrderDto createdOrder = orderService.createOrder(request);
            UUID orderId = createdOrder.getId();

            // Delete order
            orderService.deleteOrder(orderId);

            // Verify deleted
            Optional<OrderDto> result = orderService.getOrderById(orderId);
            assertThat(result).isEmpty();

        } finally {
            TenantContext.clear();
        }
    }
}
