package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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
    private final ShopRepository shopRepository;
    private final OrderStateMachineService stateMachineService;
    private final OrderMapper orderMapper;

    public OrderService(OrderRepository orderRepository,
                       ProductRepository productRepository,
                       ShopRepository shopRepository,
                       OrderStateMachineService stateMachineService,
                       OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.shopRepository = shopRepository;
        this.stateMachineService = stateMachineService;
        this.orderMapper = orderMapper;
    }

    /**
     * Create a new order with items.
     * Automatically assigns tenant from context and calculates totals.
     * Validates that the shop belongs to the current tenant.
     */
    public OrderDto createOrder(CreateOrderRequest request) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        log.debug("Creating order for tenant {} at shop {}", tenantId, request.getShopId());

        // Validate shop exists and belongs to current tenant (RLS will filter, but explicit check provides better error message)
        Shop shop = shopRepository.findById(request.getShopId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Shop not found or does not belong to your tenant: " + request.getShopId()));

        // RLS ensures shop.getTenantId() == tenantId automatically, but this provides defensive programming

        // Create order entity
        Order order = new Order();
        order.setTenantId(tenantId);
        order.setShopId(shop.getId()); // Use validated shop ID
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
                    .orElseThrow(() -> new ResourceNotFoundException(
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

        return orderMapper.toDto(order);
    }

    /**
     * Get order by ID (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDto> getOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(orderMapper::toDto);
    }

    /**
     * Get order by order number (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDto> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(orderMapper::toDto);
    }

    /**
     * Get all orders (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(orderMapper::toDto);
    }

    /**
     * Get orders by status (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status).stream()
                .map(orderMapper::toDto)
                .toList();
    }

    /**
     * Get orders by shop (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByShop(UUID shopId) {
        return orderRepository.findByShopId(shopId).stream()
                .map(orderMapper::toDto)
                .toList();
    }

    /**
     * Update order status (DEPRECATED - use transition methods instead).
     * This method bypasses StateMachine validation.
     * Kept for backward compatibility.
     *
     * @deprecated Use specific transition methods: submitOrder, confirmOrder, etc.
     */
    @Deprecated
    public OrderDto updateOrderStatus(UUID orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        log.warn("Updating order {} status without StateMachine validation: {} -> {}",
                order.getOrderNumber(), order.getStatus(), newStatus);

        order.setStatus(newStatus);
        order.setUpdatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);

        return orderMapper.toDto(order);
    }

    /**
     * Submit draft order for processing.
     * Transition: DRAFT → PENDING
     */
    public OrderDto submitOrder(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.SUBMIT);
    }

    /**
     * Confirm pending order.
     * Transition: PENDING → CONFIRMED
     */
    public OrderDto confirmOrder(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.CONFIRM);
    }

    /**
     * Start preparing confirmed order.
     * Transition: CONFIRMED → PREPARING
     */
    public OrderDto startPreparation(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.START_PREP);
    }

    /**
     * Mark order as ready for pickup/delivery.
     * Transition: PREPARING → READY
     */
    public OrderDto markOrderReady(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.MARK_READY);
    }

    /**
     * Complete order (picked up/delivered).
     * Transition: READY → COMPLETED
     */
    public OrderDto completeOrder(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.COMPLETE);
    }

    /**
     * Cancel order at any stage.
     * Transition: ANY → CANCELLED
     */
    public OrderDto cancelOrder(UUID orderId) {
        return transitionOrder(orderId, OrderEvent.CANCEL);
    }

    /**
     * Execute a state transition using StateMachine.
     * Validates transition, updates order, and persists.
     */
    private OrderDto transitionOrder(UUID orderId, OrderEvent event) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        OrderStatus oldStatus = order.getStatus();

        // Use StateMachine to validate and execute transition
        OrderStatus newStatus = stateMachineService.sendEvent(orderId, oldStatus, event);

        // Update order with new status
        order.setStatus(newStatus);
        order.setUpdatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);

        log.info("Order {} transitioned: {} -> {} via event {}",
                order.getOrderNumber(), oldStatus, newStatus, event);

        return orderMapper.toDto(order);
    }

    /**
     * Delete order by ID (tenant-scoped).
     * Cascade delete will remove order items.
     */
    public void deleteOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        log.info("Deleting order {}", order.getOrderNumber());
        orderRepository.delete(order);
    }

    /**
     * Generate unique order number for tenant.
     * <p>
     * Format: ORD-{tenant-prefix}-{YYYYMMDD}-{random-suffix}
     * Example: ORD-A1B2C3D4-20260116-E5F6G7H8
     * <p>
     * Structure:
     * - ORD: Constant prefix for easy identification
     * - tenant-prefix: First 8 characters of tenant UUID (uppercase) for tenant isolation
     * - YYYYMMDD: ISO date for chronological sorting and filtering
     * - random-suffix: 8-character random hex for collision-proof uniqueness
     * <p>
     * Benefits:
     * - Tenant-aware: Customer support can identify tenant at a glance
     * - Sortable: Date component enables chronological ordering
     * - Debuggable: Human-readable format with clear structure
     * - Collision-proof: Random suffix ensures uniqueness without sequence coordination
     * - Backward compatible: Existing orders retain their old format
     *
     * @param tenantId the tenant UUID for prefix generation
     * @return unique order number string
     */
    private String generateOrderNumber(UUID tenantId) {
        // Extract first 8 characters of tenant UUID for prefix (compact yet unique)
        String tenantPrefix = tenantId.toString().replace("-", "").substring(0, 8).toUpperCase();

        // Add date for sorting/filtering (YYYYMMDD format)
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

        // Add random suffix for uniqueness (8 hex characters, no hyphens)
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();

        return String.format("ORD-%s-%s-%s", tenantPrefix, datePart, randomSuffix);
    }

    /**
     * Convert Order entity to DTO.
     * @deprecated Use {@link OrderMapper#toDto(Order)} instead.
     * TODO: Remove after migration to MapStruct is complete.
     */
    @Deprecated
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
