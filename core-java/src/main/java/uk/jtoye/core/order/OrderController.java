package uk.jtoye.core.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDto;

import java.util.List;
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

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Create a new order.
     * POST /orders
     */
    @PostMapping
    @Operation(summary = "Create a new order", description = "Creates an order with items for the authenticated tenant")
    public ResponseEntity<OrderDto> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        OrderDto order = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    /**
     * Get all orders with pagination.
     * GET /orders
     */
    @GetMapping
    @Operation(summary = "List all orders", description = "Returns paginated list of orders for the authenticated tenant")
    public ResponseEntity<Page<OrderDto>> getAllOrders(Pageable pageable) {
        Page<OrderDto> orders = orderService.getAllOrders(pageable);
        return ResponseEntity.ok(orders);
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
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get orders by status.
     * GET /orders/status/{status}
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "Get orders by status", description = "Returns orders with specified status for the authenticated tenant")
    public ResponseEntity<List<OrderDto>> getOrdersByStatus(@PathVariable OrderStatus status) {
        List<OrderDto> orders = orderService.getOrdersByStatus(status);
        return ResponseEntity.ok(orders);
    }

    /**
     * Get orders by shop.
     * GET /orders/shop/{shopId}
     */
    @GetMapping("/shop/{shopId}")
    @Operation(summary = "Get orders by shop", description = "Returns orders for a specific shop of the authenticated tenant")
    public ResponseEntity<List<OrderDto>> getOrdersByShop(@PathVariable UUID shopId) {
        List<OrderDto> orders = orderService.getOrdersByShop(shopId);
        return ResponseEntity.ok(orders);
    }

    /**
     * Update order status.
     * PATCH /orders/{id}/status
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Update order status", description = "Updates the status of an order")
    public ResponseEntity<OrderDto> updateOrderStatus(
            @PathVariable UUID id,
            @RequestParam OrderStatus status) {
        OrderDto order = orderService.updateOrderStatus(id, status);
        return ResponseEntity.ok(order);
    }

    /**
     * Delete order.
     * DELETE /orders/{id}
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete order", description = "Deletes an order and its items")
    public ResponseEntity<Void> deleteOrder(@PathVariable UUID id) {
        orderService.deleteOrder(id);
        return ResponseEntity.noContent().build();
    }
}
