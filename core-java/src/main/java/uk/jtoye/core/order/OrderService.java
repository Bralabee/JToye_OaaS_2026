package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for order management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 */
@Service
@Transactional
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    /**
     * Create a new order with items.
     * Automatically assigns tenant from context and calculates totals.
     */
    public OrderDto createOrder(CreateOrderRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating order for tenant {} at shop {}", tenantId, request.getShopId());

        // Create order entity
        Order order = new Order();
        order.setTenantId(tenantId);
        order.setShopId(request.getShopId());
        order.setOrderNumber(generateOrderNumber(tenantId));
        order.setStatus(OrderStatus.DRAFT);
        order.setCustomerName(request.getCustomerName());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setCustomerPhone(request.getCustomerPhone());
        order.setNotes(request.getNotes());
        order.setUpdatedAt(OffsetDateTime.now());

        // Add order items
        for (OrderItemRequest itemRequest : request.getItems()) {
            // Fetch product to get current price
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Product not found: " + itemRequest.getProductId()));

            // Use actual product price
            long unitPrice = product.getPricePennies();

            OrderItem item = new OrderItem(
                    product.getId(),
                    itemRequest.getQuantity(),
                    unitPrice
            );
            item.setTenantId(tenantId);
            order.addItem(item);
        }

        // Calculate total
        order.calculateTotal();

        // Save order
        order = orderRepository.save(order);

        log.info("Created order {} with {} items, total: {} pennies",
                order.getOrderNumber(), order.getItems().size(), order.getTotalAmountPennies());

        return toDto(order);
    }

    /**
     * Get order by ID (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDto> getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(this::toDto);
    }

    /**
     * Get order by order number (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDto> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(this::toDto);
    }

    /**
     * Get all orders (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(this::toDto);
    }

    /**
     * Get orders by status (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Get orders by shop (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByShop(UUID shopId) {
        return orderRepository.findByShopId(shopId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Update order status.
     * In a full implementation, this would validate state transitions.
     */
    public OrderDto updateOrderStatus(UUID orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        log.info("Updating order {} status: {} -> {}",
                order.getOrderNumber(), order.getStatus(), newStatus);

        order.setStatus(newStatus);
        order.setUpdatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);

        return toDto(order);
    }

    /**
     * Delete order by ID (tenant-scoped).
     * Cascade delete will remove order items.
     */
    public void deleteOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        log.info("Deleting order {}", order.getOrderNumber());
        orderRepository.delete(order);
    }

    /**
     * Generate unique order number for tenant.
     * Format: ORD-{uuid} (collision-proof, globally unique)
     */
    private String generateOrderNumber(UUID tenantId) {
        return "ORD-" + UUID.randomUUID().toString();
    }

    /**
     * Convert Order entity to DTO.
     */
    private OrderDto toDto(Order order) {
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
    }
}
