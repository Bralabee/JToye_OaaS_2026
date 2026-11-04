package uk.jtoye.core.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.order.dto.OrderTimelineEventDto;

import java.util.List;
import java.util.UUID;

/**
 * An order's activity timeline (issue #857). Read-only, so no Idempotency-Key contract: there is
 * nothing to replay. Kept out of {@link OrderController}, whose two constructors tests build by hand.
 */
@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order management endpoints")
@SecurityRequirement(name = "bearer-jwt")
public class OrderTimelineController {

    private final OrderTimelineService timelineService;

    public OrderTimelineController(OrderTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "Order activity timeline",
            description = "Who changed the order, to what, and when, oldest first: placed, each status change, "
                    + "payment changes and a void, read from the order's audit history. A change made by a "
                    + "signed-in person names them (actorType STAFF); one made with no one signed in - the "
                    + "customer at checkout, a payment webhook, a scheduled job - is actorType SYSTEM. "
                    + "Requires at least STAFF on the order's shop.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The timeline, oldest first (may be empty for an order with no recorded history)"),
            @ApiResponse(responseCode = "403", description = "shop-access-denied: the caller holds no STAFF grant on the order's shop"),
            @ApiResponse(responseCode = "404", description = "Order not found")
    })
    public List<OrderTimelineEventDto> timeline(@PathVariable UUID id) {
        return timelineService.timeline(id);
    }
}
