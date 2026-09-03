package uk.jtoye.core.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import uk.jtoye.core.common.idempotency.Idempotent;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.common.idempotency.IdempotencyOutcome;
import uk.jtoye.core.common.idempotency.IdempotencyService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.UpdateOrderRequest;

import java.util.UUID;

/**
 * REST controller for order management.
 * All endpoints require JWT authentication and are automatically tenant-scoped.
 */
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order management endpoints")
@SecurityRequirement(name = "bearer-jwt")
public class OrderController {

    private final OrderService orderService;
    private final OrderSseService sseService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, OrderSseService sseService,
                           IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.sseService = sseService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Subscribe to real-time order state change events via SSE.
     * GET /orders/stream
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    @Operation(summary = "Order state change stream", description = "Server-Sent Events stream for real-time order updates")
    public SseEmitter streamOrderEvents() {
        return sseService.subscribe();
    }

    /**
     * Create a new order.
     * POST /orders
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [AI-02]: activate reserved orders:write (D-01)
    @PostMapping
    @Idempotent(endpoint = "orders.create")
    @Operation(summary = "Create a new order", description = "Creates an order with items for the authenticated tenant. Supply an Idempotency-Key header to make a retried POST safe: a repeated key replays the original order and never creates a duplicate row.")
    @ApiResponses(value = {
            // API-5 (QA council 20260902-134741): the declared set was springdoc's inferred
            // "200" alone, while this endpoint has never returned 200. Every code below was
            // read off the handler and GlobalExceptionHandler, not copied from a template.
            @ApiResponse(responseCode = "201",
                    description = "Order created. A repeated Idempotency-Key replays the original "
                            + "order and echoes this same 201 - never a second row."),
            @ApiResponse(responseCode = "400",
                    description = "Request validation failed (errors/validation), or a rejected value "
                            + "such as ordering more units than the product has in stock "
                            + "(errors/invalid-argument)"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid "
                            + "(errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the orders:write scope (errors/forbidden), or the caller "
                            + "holds no SHOP_MANAGER grant on the target shop "
                            + "(errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404",
                    description = "The shop, customer or a product in the basket does not exist for "
                            + "this tenant (errors/not-found)"),
            @ApiResponse(responseCode = "409",
                    description = "A request with this Idempotency-Key is still in flight "
                            + "(errors/idempotency-conflict)"),
            @ApiResponse(responseCode = "422",
                    description = "Idempotency-Key reused with a different payload "
                            + "(errors/idempotency-payload-mismatch)")
    })
    public ResponseEntity<OrderDto> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            // Hidden from springdoc: IdempotencyHeaderCustomizer advertises the rich
            // Idempotency-Key parameter (description + maxLength) off @Idempotent, so
            // documenting the raw @RequestHeader too would double-list the header.
            @Parameter(hidden = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            OrderDto order = orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        }
        IdempotencyOutcome<OrderDto> outcome = idempotencyService.execute(
                "orders.create", idempotencyKey, request, OrderDto.class,
                () -> orderService.createOrder(request));
        return ResponseEntity.status(outcome.status()).body(outcome.value());
    }

    /**
     * Get all orders with pagination, optionally filtered to a single shop.
     * GET /orders[?shopId=...]
     *
     * <p>Issue #179 defect 2: the kitchen display has always sent
     * {@code ?shopId=...} but this endpoint silently ignored it, so every
     * shop's orders appeared on every kitchen screen. The filter now delegates
     * to the shop-scoped query. Tenant isolation is unchanged: RLS scopes the
     * query to the authenticated tenant, so a foreign tenant's shopId simply
     * yields an empty page (no existence disclosure).
     */
    @GetMapping
    @Operation(summary = "List all orders", description = "Returns paginated list of orders for the authenticated tenant. Optional shopId query param filters to one shop of the tenant.")
    public ResponseEntity<Page<OrderDto>> getAllOrders(
            @RequestParam(required = false) UUID shopId,
            Pageable pageable) {
        Page<OrderDto> orders = shopId != null
                ? orderService.getOrdersByShop(shopId, pageable)
                : orderService.getAllOrders(pageable);
        return ResponseEntity.ok(orders);
    }

    /**
     * The kitchen board, in one request.
     * GET /orders/kitchen?shopId=...
     *
     * <p>Issue #564. The board asks one question — "all active orders for this shop, with
     * their line items" — and used to pay {@code 1 + N} requests for it: a list read plus
     * one {@code /{id}/detail} per ticket. On an 18-ticket board that is 19 requests, and
     * the browser's {@code online} handler fires the burst again on recovery, so an
     * offline blip cost 38 requests in under half a second against a tenant limit of
     * 100/minute. Ten came back 429. The cost scaled with how BUSY the kitchen was, which
     * is precisely backwards.
     *
     * <p>It also stops the client reading history to find the present: the old path paged
     * the shop's entire order list and filtered for kitchen statuses in the browser.
     *
     * <p>Read-only, so no Idempotency-Key contract (there is nothing to replay). Access is
     * the same STAFF-on-shop grant every other shop-scoped read uses.
     */
    @GetMapping("/kitchen")
    @Operation(summary = "Kitchen board for one shop",
            description = "Returns active (CONFIRMED/PREPARING/READY) orders WITH line items for one shop "
                    + "of the authenticated tenant, newest first. Replaces the list-then-detail-per-ticket "
                    + "round trip the kitchen display used to make (#564). Requires STAFF on the shop.")
    public ResponseEntity<Page<OrderDetailDto>> getKitchenBoard(
            @RequestParam UUID shopId,
            Pageable pageable) {
        return ResponseEntity.ok(orderService.getKitchenBoard(shopId, pageable));
    }

    /**
     * Get order by ID.
     * GET /orders/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get order by ID", description = "Returns a single order for the authenticated tenant")
    public ResponseEntity<OrderDto> getOrderById(@PathVariable UUID id) {
        return orderService.getOrderById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    /**
     * Get order with items by ID.
     * GET /orders/{id}/detail
     */
    @GetMapping("/{id}/detail")
    @Operation(summary = "Get order detail with items", description = "Returns order with line items for the authenticated tenant")
    public ResponseEntity<OrderDetailDto> getOrderDetail(@PathVariable UUID id) {
        return orderService.getOrderDetailById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id));
    }

    /**
     * Update order details (customer info, notes).
     * PUT /orders/{id}
     * Only allowed on DRAFT or PENDING orders.
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PutMapping("/{id}")
    @Operation(summary = "Update order", description = "Update customer info and notes on DRAFT/PENDING orders")
    public ResponseEntity<OrderDto> updateOrder(@PathVariable UUID id, @Valid @RequestBody UpdateOrderRequest request) {
        OrderDto order = orderService.updateOrder(id, request);
        return ResponseEntity.ok(order);
    }

    /**
     * Get orders by status.
     * GET /orders/status/{status}
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "Get orders by status", description = "Returns paginated orders with specified status for the authenticated tenant")
    public ResponseEntity<Page<OrderDto>> getOrdersByStatus(
            @PathVariable OrderStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<OrderDto> orders = orderService.getOrdersByStatus(status, pageable);
        return ResponseEntity.ok(orders);
    }

    /**
     * Get orders by shop.
     * GET /orders/shop/{shopId}
     */
    @GetMapping("/shop/{shopId}")
    @Operation(summary = "Get orders by shop", description = "Returns paginated orders for a specific shop of the authenticated tenant")
    public ResponseEntity<Page<OrderDto>> getOrdersByShop(
            @PathVariable UUID shopId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<OrderDto> orders = orderService.getOrdersByShop(shopId, pageable);
        return ResponseEntity.ok(orders);
    }

    /**
     * Get orders by customer.
     * GET /orders/customer/{customerId}
     */
    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get orders by customer", description = "Returns paginated orders for a specific customer of the authenticated tenant")
    public ResponseEntity<Page<OrderDto>> getOrdersByCustomer(
            @PathVariable UUID customerId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<OrderDto> orders = orderService.getOrdersByCustomer(customerId, pageable);
        return ResponseEntity.ok(orders);
    }

    /**
     * Delete order.
     * DELETE /orders/{id}
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete order", description = "Deletes an order and its items")
    public ResponseEntity<Void> deleteOrder(@PathVariable UUID id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }

    // ========== State Transition Endpoints ==========
    //
    // issue #448: all six transitions funnel through OrderService.transitionOrder, so
    // they share one error contract, declared explicitly below because springdoc cannot
    // read it off @RestControllerAdvice. The 409 is the one the issue calls out by name:
    // #434 added an OptimisticLockingFailureException handler for two writers racing the
    // same @Version-bearing order row, and nothing in the published spec said so.
    // InsufficientStockException maps to the same 409, which is why the description
    // names both causes rather than just the race.

    /**
     * Submit draft order for processing.
     * POST /orders/{id}/submit
     * Transition: DRAFT → PENDING
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit order", description = "Submit a draft order for processing (DRAFT → PENDING)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> submitOrder(@PathVariable UUID id) {
        OrderDto order = orderService.submitOrder(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Confirm pending order.
     * POST /orders/{id}/confirm
     * Transition: PENDING → CONFIRMED
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm order", description = "Confirm a pending order (PENDING → CONFIRMED)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> confirmOrder(@PathVariable UUID id) {
        OrderDto order = orderService.confirmOrder(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Start preparing confirmed order.
     * POST /orders/{id}/start-preparation
     * Transition: CONFIRMED → PREPARING
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/start-preparation")
    @Operation(summary = "Start preparation", description = "Start preparing a confirmed order (CONFIRMED → PREPARING)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> startPreparation(@PathVariable UUID id) {
        OrderDto order = orderService.startPreparation(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Mark order as ready.
     * POST /orders/{id}/mark-ready
     * Transition: PREPARING → READY
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/mark-ready")
    @Operation(summary = "Mark as ready", description = "Mark a preparing order as ready (PREPARING → READY)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> markOrderReady(@PathVariable UUID id) {
        OrderDto order = orderService.markOrderReady(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Complete order.
     * POST /orders/{id}/complete
     * Transition: READY → COMPLETED
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/complete")
    @Operation(summary = "Complete order", description = "Complete a ready order (READY → COMPLETED)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> completeOrder(@PathVariable UUID id) {
        OrderDto order = orderService.completeOrder(id);
        return ResponseEntity.ok(order);
    }

    /**
     * Cancel order.
     * POST /orders/{id}/cancel
     * Transition: ANY → CANCELLED
     */
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")  // Phase 25 [CR-01]: gate all order mutations on orders:write (AI-02 least-privilege)
    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel order", description = "Cancel an order at any stage (ANY → CANCELLED)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transition applied"),
            @ApiResponse(responseCode = "400", description = "Transition not legal from the order's current status"),
            @ApiResponse(responseCode = "403", description = "Caller lacks at least STAFF on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Concurrent modification of the same order, or insufficient stock")
    })
    public ResponseEntity<OrderDto> cancelOrder(@PathVariable UUID id) {
        OrderDto order = orderService.cancelOrder(id);
        return ResponseEntity.ok(order);
    }
}
