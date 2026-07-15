package uk.jtoye.core.notification.dispatch;

import uk.jtoye.core.notification.template.RecipientRole;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.tenant.TenantRepository;

import java.util.List;
import java.util.UUID;

/**
 * TEMPORARY RED-phase stub — replaced by the GREEN implementation in the same task.
 */
public class RecipientResolver {

    public RecipientResolver(TenantRepository tenantRepository, OrderRepository orderRepository) {
    }

    public record Recipient(String email, RecipientRole role) {
    }

    public enum Family {
        ORDER_STATE, ORDER_REFUND, PAYMENT, ONBOARDING, OTHER;

        public static Family classify(String eventType) {
            return OTHER;
        }
    }

    public List<Recipient> forEvent(String eventType, UUID tenantId, Object payload) {
        return List.of();
    }
}
