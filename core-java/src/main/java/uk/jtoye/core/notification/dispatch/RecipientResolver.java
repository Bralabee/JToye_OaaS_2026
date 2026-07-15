package uk.jtoye.core.notification.dispatch;

import org.springframework.stereotype.Component;
import uk.jtoye.core.notification.template.RecipientRole;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;
import uk.jtoye.core.tenant.Tenant;
import uk.jtoye.core.tenant.TenantRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves the LOCKED per-family recipient set for a lifecycle event (D-04).
 *
 * <p><b>Audiences (deliberately fixed — see {@link #forEvent}):</b>
 * <ul>
 *   <li>{@code order.state.*} → the VENDOR ONLY. The CUSTOMER is served by the
 *       untouched legacy {@code OrderStateChangeListener → EmailNotificationService}
 *       path, so adding a customer recipient here would double-email the
 *       customer (Pitfall 5). This vendor-only rule is intentional — do not
 *       "fix" it into a duplicate.</li>
 *   <li>{@code order.refunded} → {@code {customer, vendor}} — refund had no
 *       consumer at all before this plan, so BOTH audiences are new.</li>
 *   <li>{@code payment.*} → {@code {customer, vendor}} — payment was audit-only.</li>
 *   <li>{@code onboarding.state.*} → the VENDOR ONLY (there is no J'Toye
 *       platform operator — {@code arch_no_platform_operator}).</li>
 * </ul>
 *
 * <p><b>Vendor recipient = {@code tenants.contact_email} ONLY (D-04).</b> There
 * is NO separate "onboarding-contact" email field in the codebase
 * ({@code VendorOnboarding} has no email column), so there is deliberately no
 * fallback field: when {@code contact_email} is null/blank the vendor recipient
 * is simply omitted (no send). The customer recipient is the order's own email.
 *
 * <p>Every read is tenant-scoped: the caller ({@code NotificationDispatchService}
 * via the listeners) has already pinned {@code TenantContext} + the RLS GUC from
 * {@code event.tenantId()} before {@code forEvent} runs, so
 * {@link OrderRepository#findById} sees only the pinned tenant's order
 * ({@code tenants} carries no RLS by design — V2/V48).
 */
@Component
public class RecipientResolver {

    private final TenantRepository tenantRepository;
    private final OrderRepository orderRepository;

    public RecipientResolver(TenantRepository tenantRepository, OrderRepository orderRepository) {
        this.tenantRepository = tenantRepository;
        this.orderRepository = orderRepository;
    }

    /** A resolved destination + the audience axis the template renders for. */
    public record Recipient(String email, RecipientRole role) {
    }

    /**
     * The event family, classified from the routing-key-style event type. Kept
     * public so {@code NotificationDispatchService} maps the same family to a
     * {@code NotificationCategory} + template without re-parsing.
     */
    public enum Family {
        ORDER_STATE, ORDER_REFUND, PAYMENT, ONBOARDING, OTHER;

        public static Family classify(String eventType) {
            if (eventType == null || eventType.isBlank()) {
                return OTHER;
            }
            String e = eventType.toLowerCase(Locale.ROOT);
            // order.refunded (and any refund.* template key) is the refund family,
            // NOT order.state.* — checked FIRST so the order.* prefix does not swallow it.
            if (e.equals("order.refunded") || e.startsWith("refund")) {
                return ORDER_REFUND;
            }
            if (e.startsWith("order")) {
                return ORDER_STATE;
            }
            if (e.startsWith("payment")) {
                return PAYMENT;
            }
            if (e.startsWith("onboarding")) {
                return ONBOARDING;
            }
            return OTHER;
        }
    }

    /**
     * Resolve the recipient set for {@code eventType} under the already-pinned
     * {@code tenantId}.
     */
    public List<Recipient> forEvent(String eventType, UUID tenantId, Object payload) {
        Family family = Family.classify(eventType);
        return switch (family) {
            // Vendor-only: the customer stays on the legacy path (no duplicate).
            case ORDER_STATE, ONBOARDING -> vendorOnly(tenantId);
            // Both audiences (both new — refund/payment had no email consumer).
            case ORDER_REFUND, PAYMENT -> customerAndVendor(tenantId, payload);
            case OTHER -> List.of();
        };
    }

    private List<Recipient> vendorOnly(UUID tenantId) {
        String vendor = vendorEmail(tenantId);
        return vendor == null ? List.of() : List.of(new Recipient(vendor, RecipientRole.VENDOR));
    }

    private List<Recipient> customerAndVendor(UUID tenantId, Object payload) {
        List<Recipient> out = new ArrayList<>(2);
        String customer = customerEmail(payload);
        if (customer != null && !customer.isBlank()) {
            out.add(new Recipient(customer, RecipientRole.CUSTOMER));
        }
        String vendor = vendorEmail(tenantId);
        if (vendor != null) {
            out.add(new Recipient(vendor, RecipientRole.VENDOR));
        }
        return out;
    }

    /** Vendor recipient = {@code tenants.contact_email} ONLY; omitted when null/blank (no fallback field exists). */
    private String vendorEmail(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(Tenant::getContactEmail)
                .filter(email -> email != null && !email.isBlank())
                .orElse(null);
    }

    /** Customer recipient = the order's own email (D-04). */
    private String customerEmail(Object payload) {
        UUID orderId = orderIdOf(payload);
        if (orderId == null) {
            return null;
        }
        return orderRepository.findById(orderId)
                .map(uk.jtoye.core.order.Order::getCustomerEmail)
                .orElse(null);
    }

    private static UUID orderIdOf(Object payload) {
        if (payload instanceof RefundEvent refund) {
            return refund.orderId();
        }
        if (payload instanceof PaymentEvent payment) {
            return payment.orderId();
        }
        if (payload instanceof OrderStateChangeEvent order) {
            return order.orderId();
        }
        return null;
    }
}
