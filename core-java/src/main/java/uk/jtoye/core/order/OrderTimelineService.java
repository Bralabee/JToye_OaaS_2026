package uk.jtoye.core.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.OrderTimelineRepository.RevisionRow;
import uk.jtoye.core.order.dto.OrderTimelineEventDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An order's activity timeline: who changed it, to what, and when (issue #857).
 *
 * <p>A vendor asked "who prepared this order, and when?" and the platform could not say, though it had
 * been recording the answer all along: {@code Order} is {@code @Audited}, so every committed change is a
 * row in {@code orders_aud} stamped, through {@code revinfo}, with the JWT subject of whoever made it.
 * This service reads that history and turns consecutive revisions into the changes a person cares about:
 * the order being placed, a status change, a payment change, a void. No table, no migration, and nothing
 * new is recorded - so the timeline can never disagree with the audit trail it is read from.
 *
 * <p><b>Access</b> is exactly the order detail's: STAFF on the order's shop (VSA-02 / D-02), a foreign or
 * unknown id is a 404 with no existence oracle, and the lookup is tenant-explicit on top of RLS.
 *
 * <p><b>Who.</b> A revision with a subject is a STAFF action, named from the tenant's user directory (display
 * name, else email); one without is SYSTEM - the customer checking out, a payment webhook, a scheduled job -
 * because nobody was signed in. The raw subject is never returned.
 */
@Service
public class OrderTimelineService {

    private final OrderRepository orderRepository;
    private final OrderTimelineRepository timelineRepository;
    private final ShopAccessService shopAccessService;

    public OrderTimelineService(OrderRepository orderRepository,
                                OrderTimelineRepository timelineRepository,
                                ShopAccessService shopAccessService) {
        this.orderRepository = orderRepository;
        this.timelineRepository = timelineRepository;
        this.shopAccessService = shopAccessService;
    }

    @Transactional(readOnly = true)
    public List<OrderTimelineEventDto> timeline(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        shopAccessService.require(order.getShopId(), ShopRole.STAFF);
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
        return build(timelineRepository.findRevisions(tenantId, orderId));
    }

    /** Consecutive revisions to the changes between them. Pure, so the rules are unit-testable. */
    static List<OrderTimelineEventDto> build(List<RevisionRow> revisions) {
        List<OrderTimelineEventDto> events = new ArrayList<>();
        RevisionRow prev = null;
        for (RevisionRow row : revisions) {
            Instant at = row.getRevtstmp() == null ? null : Instant.ofEpochMilli(row.getRevtstmp());
            String actorType = row.getUserId() == null || row.getUserId().isBlank() ? "SYSTEM" : "STAFF";
            String actorName = "STAFF".equals(actorType) ? nameOf(row) : null;

            if (prev == null) {
                // revtype 0 is the order being created. A history that starts later (an order written before
                // auditing, or by a path that bypassed it) still shows the status it first appears with.
                boolean created = row.getRevtype() != null && row.getRevtype() == 0;
                String reason = !created && "CANCELLED".equals(row.getStatus()) ? row.getCancelReasonCode() : null;
                events.add(new OrderTimelineEventDto(created ? "PLACED" : "STATUS", null, row.getStatus(),
                        null, reason, at, actorType, actorName));
            } else {
                if (!Objects.equals(prev.getStatus(), row.getStatus())) {
                    String reason = "CANCELLED".equals(row.getStatus()) ? row.getCancelReasonCode() : null;
                    events.add(new OrderTimelineEventDto("STATUS", prev.getStatus(), row.getStatus(),
                            null, reason, at, actorType, actorName));
                }
                if (!Objects.equals(prev.getPaymentStatus(), row.getPaymentStatus())
                        && row.getPaymentStatus() != null) {
                    events.add(new OrderTimelineEventDto("PAYMENT", null, row.getStatus(),
                            row.getPaymentStatus(), null, at, actorType, actorName));
                }
                if (Boolean.TRUE.equals(row.getVoided()) && !Boolean.TRUE.equals(prev.getVoided())) {
                    events.add(new OrderTimelineEventDto("VOIDED", null, row.getStatus(),
                            null, row.getVoidReason(), at, actorType, actorName));
                }
            }
            prev = row;
        }
        return events;
    }

    private static String nameOf(RevisionRow row) {
        if (row.getDisplayName() != null && !row.getDisplayName().isBlank()) {
            return row.getDisplayName();
        }
        if (row.getEmail() != null && !row.getEmail().isBlank()) {
            return row.getEmail();
        }
        return null;
    }
}
