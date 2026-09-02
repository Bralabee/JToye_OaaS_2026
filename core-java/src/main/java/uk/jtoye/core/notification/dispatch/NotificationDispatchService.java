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
 * unsubscribe URLs (see {@link #buildUnsubscribeLinks} — the clickable page link
 * and the RFC 8058 one-click target are DIFFERENT origins, issue #516), and fans
 * the {@link NotificationMessage} out to the
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
            // INT-4: WARN, not DEBUG. Both runtime tenants had a blank tenants.contact_email,
            // so every vendor-directed email was dropped here invisibly at the default INFO
            // level. A dropped notification is an operator-visible event.
            log.warn("event=notification_dispatch_no_recipients eventType={} tenant={} "
                    + "hint=tenants.contact_email is blank and no fallback recipient resolved",
                    eventType, tenantId);
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

                UnsubscribeLinks links = buildUnsubscribeLinks(tenantId, recipient.email(), category);
                RenderedEmail rendered = templateRenderer.render(templateKey, recipient.role(), model, links.pageUrl());
                NotificationMessage message = new NotificationMessage(
                        tenantId, recipient.email(), eventType, rendered, rendered.text(),
                        links.pageUrl(), links.oneClickUrl());

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
     * The two unsubscribe URLs one email carries.
     *
     * @param pageUrl     what the recipient CLICKS — a browser GET, so it must land on the
     *                    frontend's confirmation page (which then calls the API itself)
     * @param oneClickUrl the RFC 8058 {@code List-Unsubscribe} target a mail provider POSTs to;
     *                    {@code null} when no POST-capable origin is configured, in which case
     *                    nothing one-click is advertised (see {@code EmailChannel})
     */
    private record UnsubscribeLinks(String pageUrl, String oneClickUrl) {
        static final UnsubscribeLinks NONE = new UnsubscribeLinks(null, null);
    }

    /**
     * Build the per-recipient unsubscribe URLs. Returns {@link UnsubscribeLinks#NONE}
     * when the signing secret is unset (feature inert, GLOBAL_RULE_6) — the email
     * still sends, just without the link and the RFC 8058 header; never throws.
     *
     * <p><b>Issue #516 — the origin and the path must belong to the SAME Service.</b>
     * This method used to append the API's path {@code /api/v1/public/unsubscribe}
     * to {@code notification.unsubscribe.base-url}, which is the APP origin in every
     * committed overlay (sourced from {@code frontend.url}). The ingress routes that
     * host wholly to the {@code frontend} Service and the frontend declares no
     * {@code /api/v1} rewrite, so every unsubscribe link in every email answered the
     * frontend's 404 — measured against the running local stack:
     * {@code GET http://localhost:3000/api/v1/public/unsubscribe -> 404}, while
     * {@code GET http://localhost:3000/unsubscribe -> 200}. A recipient could not opt
     * out, which is a PECR/GDPR problem, not a cosmetic one.
     *
     * <p>Both halves now come from config and are asserted against the real ingress +
     * the real controller mappings by {@code UnsubscribeLinkRoutingTest}.
     */
    private UnsubscribeLinks buildUnsubscribeLinks(UUID tenantId, String email, NotificationCategory category) {
        if (!notificationProperties.configured()) {
            return UnsubscribeLinks.NONE;
        }
        try {
            String token = unsubscribeTokenService.tokenFor(tenantId, email, category);
            String query = "?tenant=" + tenantId
                    + "&email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&category=" + category.name()
                    + "&token=" + token;

            NotificationProperties.Unsubscribe cfg = notificationProperties.getUnsubscribe();
            String pageUrl = join(cfg.getBaseUrl(), cfg.getPagePath()) + query;
            String oneClickUrl = cfg.oneClickConfigured()
                    ? join(cfg.getOneClickBaseUrl(), cfg.getOneClickPath()) + query
                    : null;
            return new UnsubscribeLinks(pageUrl, oneClickUrl);
        } catch (RuntimeException e) {
            log.warn("event=unsubscribe_url_build_failed category={}: {}", category, e.getMessage());
            return UnsubscribeLinks.NONE;
        }
    }

    /** Join an origin and a path without doubling or dropping the separating slash. */
    private static String join(String origin, String path) {
        String o = origin == null ? "" : origin.trim();
        String p = path == null ? "" : path.trim();
        while (o.endsWith("/")) {
            o = o.substring(0, o.length() - 1);
        }
        if (!p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        return o + p;
    }
}
