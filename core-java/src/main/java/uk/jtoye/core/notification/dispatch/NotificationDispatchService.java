package uk.jtoye.core.notification.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.jtoye.core.notification.NotificationProperties;
import uk.jtoye.core.notification.consent.ConsentGate;
import uk.jtoye.core.notification.consent.NotificationCategory;
import uk.jtoye.core.notification.consent.UnsubscribeTokenService;
import uk.jtoye.core.notification.dispatch.RecipientResolver.Family;
import uk.jtoye.core.notification.dispatch.RecipientResolver.Recipient;
import uk.jtoye.core.notification.template.EmailTemplateRenderer;
import uk.jtoye.core.notification.template.RenderedEmail;
import uk.jtoye.core.onboarding.OnboardingStateChangeEvent;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.payment.PaymentEvent;
import uk.jtoye.core.payment.RefundEvent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The single dispatch orchestration the notification listeners delegate to
 * (COMMS-01 + COMMS-02). For an event it: resolves the LOCKED per-family
 * recipient set (D-04 via {@link RecipientResolver}), maps the family to a
 * {@link NotificationCategory}, checks {@link ConsentGate#allows} before every
 * send, renders the branded HTML + plain-text email, builds the per-recipient
 * one-click unsubscribe URL, and fans the {@link NotificationMessage} out to the
 * {@link EmailChannel} + {@link WhatsAppSmsChannel} (the latter a no-op while
 * off).
 *
 * <p><b>No duplicate customer email:</b> the order family resolves to the VENDOR
 * only here — the customer is served by the untouched legacy
 * {@code OrderStateChangeListener} path (Pitfall 5, path A).
 *
 * <p><b>Consent is load-bearing (T-22-04-04):</b> a suppressed recipient (gate
 * {@code false}) receives NO email and NO whatsapp — the send is skipped
 * entirely, never merely un-rendered.
 *
 * <p>Best-effort: per-recipient work is wrapped so one bad recipient never
 * aborts the others, and the channels themselves never throw (their contract),
 * so a dispatch failure cannot poison the consuming listener's transaction.
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    private final RecipientResolver recipientResolver;
    private final ConsentGate consentGate;
    private final EmailTemplateRenderer templateRenderer;
    private final UnsubscribeTokenService unsubscribeTokenService;
    private final NotificationProperties notificationProperties;
    private final EmailChannel emailChannel;
    private final WhatsAppSmsChannel whatsAppSmsChannel;

    public NotificationDispatchService(RecipientResolver recipientResolver,
                                       ConsentGate consentGate,
                                       EmailTemplateRenderer templateRenderer,
                                       UnsubscribeTokenService unsubscribeTokenService,
                                       NotificationProperties notificationProperties,
                                       EmailChannel emailChannel,
                                       WhatsAppSmsChannel whatsAppSmsChannel) {
        this.recipientResolver = recipientResolver;
        this.consentGate = consentGate;
        this.templateRenderer = templateRenderer;
        this.unsubscribeTokenService = unsubscribeTokenService;
        this.notificationProperties = notificationProperties;
        this.emailChannel = emailChannel;
        this.whatsAppSmsChannel = whatsAppSmsChannel;
    }

    /**
     * Resolve → gate → render → fan to channels for one event. Assumes the
     * caller has already pinned {@code TenantContext} + the RLS GUC from
     * {@code tenantId} (the listeners do this before delegating).
     *
     * @param eventType routing-key-style type, e.g. {@code order.state.changed},
     *                  {@code order.refunded}, {@code payment.succeeded},
     *                  {@code onboarding.state.changed}
     * @param tenantId  the already-pinned owning tenant
     * @param payload   the domain event
     */
    public void dispatch(String eventType, UUID tenantId, Object payload) {
        Family family = Family.classify(eventType);
        NotificationCategory category = categoryFor(family);
        if (category == null) {
            log.debug("event=notification_dispatch_skipped reason=unknown_family eventType={}", eventType);
            return;
        }

        List<Recipient> recipients = recipientResolver.forEvent(eventType, tenantId, payload);
        if (recipients.isEmpty()) {
            log.debug("event=notification_dispatch_no_recipients eventType={} tenant={}", eventType, tenantId);
            return;
        }

        Map<String, Object> model = modelFor(family, payload);
        String templateKey = templateKeyFor(family, eventType);

        for (Recipient recipient : recipients) {
            try {
                if (!consentGate.allows(tenantId, recipient.email(), category)) {
                    log.info("event=notification_suppressed eventType={} category={} tenant={}",
                            eventType, category, tenantId);
                    continue;
                }

                String unsubscribeUrl = buildUnsubscribeUrl(tenantId, recipient.email(), category);
                RenderedEmail rendered = templateRenderer.render(templateKey, recipient.role(), model, unsubscribeUrl);
                NotificationMessage message = new NotificationMessage(
                        tenantId, recipient.email(), eventType, rendered, rendered.text(), unsubscribeUrl);

                emailChannel.deliver(message);
                whatsAppSmsChannel.deliver(message);
            } catch (RuntimeException e) {
                // Best-effort: never let one recipient abort the others (or poison the listener tx).
                log.error("event=notification_dispatch_failed eventType={} tenant={}: {}",
                        eventType, tenantId, e.getMessage());
            }
        }
    }

    private static NotificationCategory categoryFor(Family family) {
        return switch (family) {
            case ORDER_STATE -> NotificationCategory.ORDERS;
            case ONBOARDING -> NotificationCategory.ONBOARDING;
            case ORDER_REFUND, PAYMENT -> NotificationCategory.FINANCIAL;
            case OTHER -> null;
        };
    }

    /**
     * The renderer selects its template family from the prefix before the first
     * dot. {@code order.refunded} would otherwise render the ORDER template, so
     * the refund family is mapped to a {@code refund.*} key to pick the refund copy.
     */
    private static String templateKeyFor(Family family, String eventType) {
        return family == Family.ORDER_REFUND ? "refund.processed" : eventType;
    }

    private static Map<String, Object> modelFor(Family family, Object payload) {
        Map<String, Object> model = new HashMap<>();
        if (payload instanceof OrderStateChangeEvent order) {
            model.put("orderNumber", order.orderNumber());
        } else if (payload instanceof RefundEvent refund) {
            model.put("orderNumber", refund.orderNumber());
            model.put("amount", formatAmount(refund.amountPennies(), refund.currency()));
        } else if (payload instanceof PaymentEvent payment) {
            model.put("orderNumber", payment.orderNumber());
            model.put("amount", formatAmount(payment.amountPennies(), payment.currency()));
            // WR-02: carry the outcome so the renderer selects success vs failure
            // copy — payment.succeeded and payment.failed share the "payment" family.
            model.put("paymentType", payment.type() == null ? "" : payment.type().name());
        } else if (payload instanceof OnboardingStateChangeEvent onboarding) {
            // No shop name on the event; the renderer tolerates a missing key.
            model.put("reason", onboarding.reason() == null ? "" : onboarding.reason());
        }
        return model;
    }

    private static String formatAmount(long pennies, String currency) {
        String prefix = currency == null || currency.isBlank()
                ? ""
                : ("GBP".equalsIgnoreCase(currency) ? "£" : currency + " ");
        return prefix + String.format(Locale.UK, "%,.2f", pennies / 100.0);
    }

    /**
     * Build the per-recipient one-click unsubscribe URL. Returns {@code null}
     * when the signing secret is unset (feature inert, GLOBAL_RULE_6) — the
     * email still sends, just without the RFC 8058 header; never throws.
     */
    private String buildUnsubscribeUrl(UUID tenantId, String email, NotificationCategory category) {
        if (!notificationProperties.configured()) {
            return null;
        }
        try {
            String token = unsubscribeTokenService.tokenFor(tenantId, email, category);
            String base = notificationProperties.getUnsubscribe().getBaseUrl();
            return base + "/api/v1/public/unsubscribe"
                    + "?tenant=" + tenantId
                    + "&email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&category=" + category.name()
                    + "&token=" + token;
        } catch (RuntimeException e) {
            log.warn("event=unsubscribe_url_build_failed category={}: {}", category, e.getMessage());
            return null;
        }
    }
}
