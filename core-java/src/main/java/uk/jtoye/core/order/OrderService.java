package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.finance.FinancialTransactionService;
import uk.jtoye.core.finance.VatCalculator;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.UpdateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.payment.RefundService;
import uk.jtoye.core.payment.dto.RefundDto;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for order management operations.
 * All operations are automatically tenant-scoped via RLS policies.
 */
@Service
@Transactional
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * The statuses that belong on a kitchen board (#564).
     *
     * <p>This used to live only in the browser (`lib/kitchen-orders-api.ts`), which is why
     * the board fetched the shop's whole history and filtered client-side. Server-side is
     * the only place it can bound the query. The frontend constant stays — it still labels
     * and orders the columns — but the two must agree, and this is now the one that
     * decides what a read RETURNS.
     */
    static final List<OrderStatus> KITCHEN_STATUSES =
            List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final CustomerRepository customerRepository;
    private final OrderStateMachineService stateMachineService;
    private final OrderMapper orderMapper;
    private final OrderEventPublisher eventPublisher;
    private final FinancialTransactionService financialTransactionService;
    private final StockService stockService;
    private final RefundService refundService;
    private final ShopAccessService shopAccessService;

    public OrderService(OrderRepository orderRepository,
                       ProductRepository productRepository,
                       ShopRepository shopRepository,
                       CustomerRepository customerRepository,
                       OrderStateMachineService stateMachineService,
                       OrderMapper orderMapper,
                       OrderEventPublisher eventPublisher,
                       FinancialTransactionService financialTransactionService,
                       StockService stockService,
                       RefundService refundService,
                       ShopAccessService shopAccessService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.shopRepository = shopRepository;
        this.customerRepository = customerRepository;
        this.stateMachineService = stateMachineService;
        this.orderMapper = orderMapper;
        this.eventPublisher = eventPublisher;
        this.financialTransactionService = financialTransactionService;
        this.stockService = stockService;
        this.refundService = refundService;
        this.shopAccessService = shopAccessService;
    }

    /**
     * Create a new order with items.
     * Automatically assigns tenant from context and calculates totals.
     * Validates that the shop belongs to the current tenant.
     */
    public OrderDto createOrder(CreateOrderRequest request) {
        // VSA-02 (D-02): a vendor-created order requires SHOP_MANAGER on the target shop
        // (body shopId). The public storefront order path is separate and out of scope.
        shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER);

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
        order.setShopId(shop.getId());
        order.setOrderNumber(generateOrderNumber(tenantId));
        order.setStatus(OrderStatus.DRAFT);
        order.setNotes(request.getNotes());

        // Link customer: if customerId provided, look up and populate denormalized fields
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Customer not found: " + request.getCustomerId()));
            order.setCustomerId(customer.getId());
            order.setCustomerName(customer.getName());
            order.setCustomerEmail(customer.getEmail());
            order.setCustomerPhone(customer.getPhone());
        } else {
            // Fallback: use directly provided fields (backward compatible)
            order.setCustomerName(request.getCustomerName());
            order.setCustomerEmail(request.getCustomerEmail());
            order.setCustomerPhone(request.getCustomerPhone());
        }
        order.setUpdatedAt(OffsetDateTime.now());

        // Add order items with stock validation. Collect each line's
        // VAT-inclusive gross + server-resolved rate for predominant-liability
        // resolution (Issue #81 BUG 2 — closes silent zero-rating on the admin
        // path, which previously left order.vatRate at the ZERO default).
        List<VatCalculator.LineRate> lineRates = new ArrayList<>();
        for (OrderItemRequest itemRequest : request.getItems()) {
            // Fetch product to get current price
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product not found: " + itemRequest.getProductId()));

            // Validate stock availability
            if (!product.hasStock(itemRequest.getQuantity())) {
                throw new IllegalArgumentException(
                        "Insufficient stock for product '" + product.getTitle() + "': requested "
                                + itemRequest.getQuantity() + ", available " + product.getQuantityInStock());
            }

            // Use actual product price
            long unitPrice = product.getPricePennies();

            OrderItem item = new OrderItem(
                    product.getId(),
                    itemRequest.getQuantity(),
                    unitPrice
            );
            item.setTenantId(tenantId);
            item.setProductName(product.getTitle());
            // LGL-03 / V63: snapshot the allergen picture here too, not only on the storefront
            // path. This is the vendor / API / MCP order-creation route, and an order created
            // through it reaches exactly the same kitchen display. If only the storefront
            // snapshotted, every order placed this way would arrive with NO allergen data at all
            // and the KDS banner would correctly render nothing — under-declaration on the one
            // surface that can injure someone, produced by an omission rather than a decision.
            OrderAllergenSnapshot.capture(item, product.getTitle(),
                    product.getAllergenMask(), product.getIngredientsText());
            order.addItem(item);
            lineRates.add(new VatCalculator.LineRate(
                    item.getTotalPricePennies(), product.getVatRate()));
        }

        // Resolve the order's single predominant VAT rate before totalling.
        order.setVatRate(VatCalculator.predominantRate(lineRates));

        // Calculate total
        order.calculateTotal();

        // Save order. QA-council cluster P1 (API-3 rider): saveAndFlush, not save — createdAt is
        // a @CreationTimestamp generated at FLUSH time, so a bare save() would leave it null in
        // the DTO built below even though the row persists with a real timestamp. cascade=ALL on
        // Order.items means the cascaded item rows flush too.
        order = orderRepository.saveAndFlush(order);

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
                .map(order -> {
                    // VSA-02 (D-02): a by-id order read requires at least STAFF on the
                    // order's shop (parent-lookup) — cross-shop hit → typed shop 403.
                    shopAccessService.require(order.getShopId(), ShopRole.STAFF);
                    return orderMapper.toDto(order);
                });
    }

    /**
     * Get order with items by ID (tenant-scoped).
     * Eagerly fetches items for the detail view, plus refund history (Phase 17 VOPS-01).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDetailDto> getOrderDetailById(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    // VSA-02 (D-02): detail read requires at least STAFF on the shop.
                    shopAccessService.require(order.getShopId(), ShopRole.STAFF);
                    OrderDetailDto dto = orderMapper.toDetailDto(order);
                    dto.setRefunds(refundService.findByOrderId(orderId));
                    return dto;
                });
    }

    /**
     * Update order details (customer info, notes).
     * Only allowed on DRAFT or PENDING orders.
     */
    public OrderDto updateOrder(UUID orderId, UpdateOrderRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        // VSA-02 (D-02): order update requires SHOP_MANAGER on the order's shop (parent-lookup).
        shopAccessService.require(order.getShopId(), ShopRole.SHOP_MANAGER);

        if (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidStateTransitionException(
                    "Cannot update order in " + order.getStatus() + " status. Only DRAFT or PENDING orders can be edited.");
        }

        // Link customer if customerId provided
        if (request.getCustomerId() != null) {
            Customer customer = customerRepository.findById(request.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + request.getCustomerId()));
            order.setCustomerId(customer.getId());
            order.setCustomerName(customer.getName());
            order.setCustomerEmail(customer.getEmail());
            order.setCustomerPhone(customer.getPhone());
        } else {
            if (request.getCustomerName() != null) order.setCustomerName(request.getCustomerName());
            if (request.getCustomerEmail() != null) order.setCustomerEmail(request.getCustomerEmail());
            if (request.getCustomerPhone() != null) order.setCustomerPhone(request.getCustomerPhone());
        }

        if (request.getNotes() != null) order.setNotes(request.getNotes());
        order.setUpdatedAt(OffsetDateTime.now());
        order = orderRepository.save(order);

        log.info("Updated order {} details", order.getOrderNumber());
        return orderMapper.toDto(order);
    }

    /**
     * Get order by order number (tenant-scoped).
     */
    @Transactional(readOnly = true)
    public Optional<OrderDto> getOrderByNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .map(order -> {
                    // VSA-02 (D-02): by-number read requires at least STAFF on the shop.
                    shopAccessService.require(order.getShopId(), ShopRole.STAFF);
                    return orderMapper.toDto(order);
                });
    }

    /**
     * Get all orders (tenant-scoped, pageable).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getAllOrders(Pageable pageable) {
        // VSA-02 (D-01): read-scope to the caller's grant set at the QUERY. GROUP_ADMIN
        // sees the whole tenant; a scoped user only granted-shop orders; ungranted → none.
        if (shopAccessService.isGroupAdmin()) {
            return orderRepository.findAll(pageable)
                    .map(orderMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return orderRepository.findByShopIdIn(granted, pageable)
                .map(orderMapper::toDto);
    }

    /**
     * Get orders by status (tenant-scoped, pageable — Issue #95).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        // VSA-02 (D-01): read-scope by grant set, mirroring getAllOrders.
        if (shopAccessService.isGroupAdmin()) {
            return orderRepository.findByStatus(status, pageable)
                    .map(orderMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return orderRepository.findByStatusAndShopIdIn(status, granted, pageable)
                .map(orderMapper::toDto);
    }

    /**
     * Get orders by shop (tenant-scoped, pageable — Issue #95).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByShop(UUID shopId, Pageable pageable) {
        // VSA-02 (D-02): explicit shop-scoped read requires at least STAFF on that shop.
        shopAccessService.require(shopId, ShopRole.STAFF);
        return orderRepository.findByShopId(shopId, pageable)
                .map(orderMapper::toDto);
    }

    /**
     * The kitchen board, in ONE read (#564).
     *
     * <p><b>What this replaces.</b> The board asked one question — "all active orders for
     * this shop, with their line items" — and paid {@code 1 + N} HTTP requests for it: a
     * list read, then one {@code /detail} per ticket, concurrently. Measured on the dev
     * tenant: 18 active tickets, 19 requests, and the browser {@code online} handler fires
     * the whole burst again on recovery — 38 requests inside ~400 ms against a tenant
     * bucket of {@code capacity(120).refillIntervally(100, 1 min)}. Ten of them came back
     * 429. #563 taught the board to survive that; this removes it.
     *
     * <p><b>It also stops the board reading history to find the present.</b> The old client
     * paged the shop's WHOLE order list and filtered for kitchen statuses in the browser,
     * so the work scaled with how long the shop had been trading rather than with what is
     * on the board. Filtering here bounds the result by live tickets.
     *
     * <p><b>Access is deliberately the SAME rule as {@link #getOrdersByShop}</b> — STAFF on
     * the named shop (VSA-02 / D-02). A new endpoint taking a caller-supplied shopId is a
     * BOLA surface, and the safe move is to reuse the existing check rather than to reason
     * out a new one. RLS scopes rows to the tenant underneath it.
     */
    @Transactional(readOnly = true)
    public Page<OrderDetailDto> getKitchenBoard(UUID shopId, Pageable pageable) {
        shopAccessService.require(shopId, ShopRole.STAFF);

        Page<Order> page = orderRepository.findByShopIdAndStatusIn(
                shopId, KITCHEN_STATUSES, pageable);
        if (page.isEmpty()) {
            return page.map(orderMapper::toDetailDto);
        }

        // The page is already decided by real SQL LIMIT above; this attaches items to
        // exactly those rows. Doing it as a fetch-join on the paged query instead would
        // make Hibernate paginate IN MEMORY (HHH000104) — an unbounded read wearing a
        // paged response, which is the defect this method exists to remove.
        List<UUID> ids = page.getContent().stream().map(Order::getId).toList();
        Map<UUID, Order> withItems = orderRepository.findAllWithItemsByIdIn(ids).stream()
                .collect(Collectors.toMap(Order::getId, o -> o));

        // One query for every ticket's refunds, not one per ticket. The board does not
        // render refunds, but OrderDetailDto carries them and a field left empty because
        // nobody filled it is a lie the next consumer inherits.
        Map<UUID, List<RefundDto>> refundsByOrder = refundService.findByOrderIds(ids);

        return page.map(order -> {
            OrderDetailDto dto = orderMapper.toDetailDto(withItems.getOrDefault(order.getId(), order));
            dto.setRefunds(refundsByOrder.getOrDefault(order.getId(), List.of()));
            return dto;
        });
    }

    /**
     * Get orders by customer (tenant-scoped, pageable — Issue #95).
     */
    @Transactional(readOnly = true)
    public Page<OrderDto> getOrdersByCustomer(UUID customerId, Pageable pageable) {
        // VSA-02 (D-01): read-scope by grant set, mirroring getAllOrders.
        if (shopAccessService.isGroupAdmin()) {
            return orderRepository.findByCustomerId(customerId, pageable)
                    .map(orderMapper::toDto);
        }
        Set<UUID> granted = shopAccessService.grantedShopIds();
        if (granted.isEmpty()) {
            return Page.empty(pageable);
        }
        return orderRepository.findByCustomerIdAndShopIdIn(customerId, granted, pageable)
                .map(orderMapper::toDto);
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
        // VSA-02 (D-02/D-03): the single chokepoint for all six KDS state transitions
        // (submit/confirm/start-preparation/mark-ready/complete/cancel) — each requires
        // at least STAFF on the order's shop. STAFF is the operational floor (read +
        // order-state), so a scoped STAFF user CAN transition here but is denied
        // catalogue writes elsewhere. Gating once here (single load) covers all six
        // public transition entry points, which each delegate to this method.
        shopAccessService.require(order.getShopId(), ShopRole.STAFF);

        OrderStatus oldStatus = order.getStatus();

        // Use StateMachine to validate and execute transition
        OrderStatus newStatus = stateMachineService.sendEvent(orderId, oldStatus, event);

        // Mutate in memory first; the save happens AFTER stock bookkeeping so a
        // stock failure rolls back the status change (CQ-01 — RESEARCH §11 Q7).
        order.setStatus(newStatus);
        order.setUpdatedAt(OffsetDateTime.now());

        // Stock decrement (CQ-01): optimistic-lock gated, re-reads inside retry.
        // Throws InsufficientStockException on exhaustion — surrounding
        // @Transactional rolls back the in-memory status change so the order
        // remains in its prior state (PENDING) rather than becoming a ghost
        // CONFIRMED row.
        if (newStatus == OrderStatus.CONFIRMED) {
            stockService.decrementForOrder(order.getItems());
        }

        // Restore stock when order is cancelled (if it was previously confirmed).
        // Cancel-path is additive; @Version makes it collision-safe for free.
        if (newStatus == OrderStatus.CANCELLED && oldStatus.ordinal() >= OrderStatus.CONFIRMED.ordinal()) {
            stockService.restoreForOrder(order.getItems());
        }

        // Save AFTER stock bookkeeping — ensures a decrement failure leaves the
        // order in its prior PENDING status, not a ghost CONFIRMED row.
        order = orderRepository.save(order);

        log.info("Order {} transitioned: {} -> {} via event {}",
                order.getOrderNumber(), oldStatus, newStatus, event);

        // Record the state change in the transactional outbox (#93). The row
        // joins this transaction: if anything below rolls the transition back,
        // the event rolls back with it — nothing is announced for a change
        // that never committed. The flusher publishes it post-commit.
        eventPublisher.publishStateChange(
                order.getId(), order.getTenantId(), order.getShopId(), order.getOrderNumber(),
                oldStatus, newStatus);

        // Auto-create financial transaction when order is completed. Idempotent
        // on orderId (Issue #81 BUG 3): for card orders PaymentService already
        // created the settlement row on payment, so this COMPLETED call is a
        // no-op; for cash/COD orders no webhook fired, so this creates the sole
        // ledger row. The rate is the order's resolved (predominant) rate, never
        // a hardcoded STANDARD literal (BUG 2).
        if (newStatus == OrderStatus.COMPLETED && order.getTotalAmountPennies() != null) {
            financialTransactionService.createTransaction(
                    new CreateTransactionRequest(
                            order.getTotalAmountPennies(),
                            order.getVatRate(),
                            "Order " + order.getOrderNumber(),
                            order.getId()
                    ));
            log.info("Auto-created financial transaction for completed order {}", order.getOrderNumber());
        }

        return orderMapper.toDto(order);
    }

    /**
     * Delete order by ID (tenant-scoped).
     * Cascade delete will remove order items.
     */
    public void deleteOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        // VSA-02 (D-02): order delete requires SHOP_MANAGER on the order's shop.
        shopAccessService.require(order.getShopId(), ShopRole.SHOP_MANAGER);

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

}
