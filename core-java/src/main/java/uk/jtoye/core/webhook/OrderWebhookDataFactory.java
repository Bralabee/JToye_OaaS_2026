package uk.jtoye.core.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;

import java.util.List;

/**
 * Builds the {@code data} of an {@code order.*} webhook from the order itself (#864).
 *
 * <p>Enriched here, at the webhook edge, and not on {@link OrderStateChangeEvent}: that record rides the order
 * outbox to the {@code order.events} exchange, where the KDS SSE fan-out and the customer-email path also read
 * it. Putting contact details on it would hand them to every subscriber of the stream, including STAFF, whose
 * view of the customer is minimised on purpose (#856). Only the webhook, whose recipient is a GROUP_ADMIN's own
 * endpoint, gets them.
 *
 * <p>Runs inside the fan-out's per-tenant transaction with the tenant GUC pinned, so the read is RLS-scoped; the
 * tenant is also compared explicitly. When the order cannot be read (deleted since the event was raised, or not
 * this tenant's) the event is still delivered, as the thin original.
 */
@Component
public class OrderWebhookDataFactory {

    private static final Logger log = LoggerFactory.getLogger(OrderWebhookDataFactory.class);

    private final OrderRepository orderRepository;

    public OrderWebhookDataFactory(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** @return the enriched payload, or the original thin event itself (unchanged bytes) when the order cannot be read */
    public Object build(OrderStateChangeEvent event) {
        Order order = orderRepository.findAllWithItemsByIdIn(List.of(event.orderId())).stream()
                .filter(o -> event.tenantId().equals(o.getTenantId()))
                .findFirst()
                .orElse(null);
        if (order == null) {
            log.warn("event=webhook_order_unreadable order={} tenant={} — delivering the thin event",
                    event.orderId(), event.tenantId());
            return event;
        }

        List<OrderWebhookData.Line> lines = order.getItems().stream()
                .map(i -> line(i))
                .toList();
        OrderWebhookData.Address address = order.getFulfilmentType() == FulfilmentType.DELIVERY
                && order.getAddressLine1() != null
                ? new OrderWebhookData.Address(order.getAddressLine1(), order.getAddressLine2(),
                        order.getAddressCity(), order.getAddressPostcode())
                : null;
        return new OrderWebhookData(
                event.orderId(), event.tenantId(), event.orderNumber(), event.previousStatus(), event.newStatus(),
                event.timestamp(), event.shopId(),
                OrderWebhookData.ENRICHED_VERSION,
                order.getFulfilmentType() == null ? null : order.getFulfilmentType().name(),
                order.getPaymentStatus() == null ? null : order.getPaymentStatus().name(),
                lines,
                order.getSubtotalPennies(), order.getDeliveryFeePennies(), order.getVatAmountPennies(),
                order.getTotalAmountPennies(),
                new OrderWebhookData.Customer(order.getCustomerName(), order.getCustomerEmail(),
                        order.getCustomerPhone()),
                address);
    }

    private static OrderWebhookData.Line line(OrderItem item) {
        return new OrderWebhookData.Line(item.getProductId(), item.getProductName(), item.getQuantity(),
                item.getUnitPricePennies(), item.getTotalPricePennies());
    }
}
