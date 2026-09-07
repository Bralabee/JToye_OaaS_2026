package uk.jtoye.core.notification.dispatch;

import org.springframework.stereotype.Component;
import uk.jtoye.core.notification.template.RecipientRole;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.onboarding.OnboardingSubmitterResolver;
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
 * <p><b>Vendor recipient = {@code tenants.contact_email} (D-04), with ONE fallback for the
 * onboarding family (INT-4, QA council 20260902-134741).</b> There is no separate
 * "onboarding-contact" email field ({@code VendorOnboarding} has no email column) and none
 * is added. When {@code contact_email} is null/blank, {@code onboarding.state.*} falls back
 * to the email of the user who submitted the application, resolved at dispatch time by
 * {@link OnboardingSubmitterResolver} from data the system already records (the Envers
 * revision's JWT subject + the tenant-scoped {@code user_directory}). Measured on the
 * runtime: both tenants had a blank {@code contact_email}, so every onboarding email was
 * silently dropped while the vendor page promised one. The fallback is onboarding-only —
 * order/refund/payment events have no "submitter" and their vendor leg is unchanged
 * (omitted, now logged at WARN by the dispatcher). The customer recipient is the order's
 * own email.
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
    private final OnboardingSubmitterResolver submitterResolver;

    public RecipientResolver(TenantRepository tenantRepository,
                             OrderRepository orderRepository,
                             OnboardingSubmitterResolver submitterResolver) {
        this.tenantRepository = tenantRepository;
        this.orderRepository = orderRepository;
        this.submitterResolver = submitterResolver;
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
            case ORDER_STATE -> vendorOnly(tenantId);
            // Vendor-only too, with the INT-4 submitter fallback when contact_email is blank.
            case ONBOARDING -> onboardingVendor(tenantId, payload);
            // Both audiences (both new — refund/payment had no email consumer).
            case ORDER_REFUND, PAYMENT -> customerAndVendor(tenantId, payload);
            case OTHER -> List.of();
        };
    }

    private List<Recipient> vendorOnly(UUID tenantId) {
        String vendor = vendorEmail(tenantId);
        return vendor == null ? List.of() : List.of(new Recipient(vendor, RecipientRole.VENDOR));
    }

    /**
     * INT-4: {@code tenants.contact_email} first; when blank, the submitting user's directory
     * email (same VENDOR role — the template audience is unchanged). Empty when neither exists.
     */
    private List<Recipient> onboardingVendor(UUID tenantId, Object payload) {
        List<Recipient> vendor = vendorOnly(tenantId);
        if (!vendor.isEmpty()) {
            return vendor;
        }
        if (payload instanceof OnboardingStateChangeEvent event) {
            return submitterResolver.submitterEmail(event.onboardingId(), tenantId)
                    .map(email -> List.of(new Recipient(email, RecipientRole.VENDOR)))
                    .orElse(List.of());
        }
        return List.of();
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
