package uk.jtoye.core.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uk.jtoye.core.common.Sentences;
import uk.jtoye.core.config.ClockConfig;
import uk.jtoye.core.notification.consent.NotificationCategory;
import uk.jtoye.core.notification.consent.UnsubscribeTokenService;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderChannel;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.security.access.StaffInviteService;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The customer's order emails, one per order status transition.
 *
 * <p><b>Who the email is from (#789, D-13).</b> The customer buys from the SHOP's legal entity;
 * J'Toye is the ordering platform. So every email is sent From "{shop name} via J'Toye" at the
 * platform's own sender address ({@code notification.email.from}), with Reply-To the shop's
 * published email when it has one, and its body names the seller's legal identity, geographic
 * address and contact details and says J'Toye is not the seller. The order-received email is the
 * durable-medium confirmation (CCR 2013 reg 16), so it also carries the no-cancellation statement
 * for freshly prepared food (reg 28(1)(c)) and the order's allergen record (D-08).
 *
 * <p><b>The wording is not authored here.</b> Every legally operative sentence mirrors, verbatim,
 * the constant the storefront renders: the seller block from
 * {@code frontend/components/storefront/seller-block.tsx} (31.1-24) and the allergen record from
 * {@code frontend/components/storefront/recorded-allergen-set.tsx} and
 * {@code order-allergen-panel.tsx}. {@code EmailNotificationServiceTest} reads those files and
 * fails if the two copies drift. The cancellation wording still awaits adviser review
 * (Assumption A3).
 *
 * <p><b>Header safety (T-31.1-85).</b> The shop name is vendor-controlled and becomes a header
 * value. Control characters (CR and LF included) are replaced with spaces BEFORE it reaches
 * {@link InternetAddress}, which then RFC 2047-encodes it; the Reply-To address is parsed strictly
 * and dropped, never sent, if it is not exactly one valid address.
 *
 * <p><b>Logging (T-31.1-88, ASVS V7).</b> Only the order number and the template name are logged.
 * Never the recipient, and never a mail exception's message, which can quote the addresses.
 *
 * <p><b>Tenant (Pitfall 8).</b> These methods are {@code @Async} and run with no TenantContext.
 * They look nothing up: everything about the shop and the order arrives in the
 * {@link CustomerEmailContext} the listener built while the tenant was pinned.
 */
@Service
public class EmailNotificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    // ---- Wording mirrored from the storefront (one source; parity-tested) ------------------------

    /** seller-block.tsx SELLER_BLOCK_HEADING_COPY. */
    public static final String SELLER_BLOCK_HEADING_COPY = "Who you are buying from";
    /** seller-block.tsx PLATFORM_NOT_SELLER_COPY (D-13). */
    public static final String PLATFORM_NOT_SELLER_COPY =
            "J'Toye is the ordering platform. Your contract for this order is with the seller named here.";
    /** seller-block.tsx CANCELLATION_STATEMENT_COPY (CCR 2013 Sch 2(o), reg 28(1)(c); Assumption A3). */
    public static final String CANCELLATION_STATEMENT_COPY =
            "Your order is freshly prepared food, which is liable to deteriorate rapidly, so the 14-day right to "
                    + "cancel under the Consumer Contracts Regulations 2013 (regulation 28(1)(c)) does not apply. "
                    + "This does not affect your rights if the food is faulty or not as described.";
    /** seller-block.tsx SELLER_DETAILS_MISSING_COPY. */
    public static final String SELLER_DETAILS_MISSING_COPY = "The seller has not provided their legal details yet.";
    /** seller-block.tsx PLATFORM_CONTACT_INTRO_COPY. */
    public static final String PLATFORM_CONTACT_INTRO_COPY =
            "J'Toye is the ordering platform, not the seller. To contact J'Toye, see";
    /** seller-block.tsx PLATFORM_CONTACT_LINK_COPY. */
    public static final String PLATFORM_CONTACT_LINK_COPY = "J'Toye's legal and contact details";
    /** seller-block.tsx SELLER_ENTITY_TYPE_LABELS. */
    public static final Map<TraderEntityType, String> SELLER_ENTITY_TYPE_LABELS = Map.of(
            TraderEntityType.COMPANY, "Registered company",
            TraderEntityType.SOLE_TRADER, "Sole trader",
            TraderEntityType.PARTNERSHIP, "Partnership");

    /** recorded-allergen-set.tsx RECORDED_ALLERGEN_SET_TITLE_COPY. */
    public static final String RECORDED_ALLERGEN_SET_TITLE_COPY = "Allergen record for this order";
    /** recorded-allergen-set.tsx RECORDED_ACK_HEADING_COPY. */
    public static final String RECORDED_ACK_HEADING_COPY = "You confirmed you had read:";
    /** recorded-allergen-set.tsx RECORDED_SET_HEADING_COPY. */
    public static final String RECORDED_SET_HEADING_COPY = "Recorded on your order:";
    /** recorded-allergen-set.tsx RECORDED_ACK_NOT_RECORDED_COPY. */
    public static final String RECORDED_ACK_NOT_RECORDED_COPY = "No allergen acknowledgement was recorded for this order.";
    /** recorded-allergen-set.tsx RECORDED_ACK_NONE_COPY. */
    public static final String RECORDED_ACK_NONE_COPY =
            "You confirmed you had read that the kitchen declared none of the 14 regulated allergens for these items.";
    /** recorded-allergen-set.tsx VENDOR_PLACED_COPY (D-07). */
    public static final String VENDOR_PLACED_COPY =
            "This order was placed by the shop for you, so no allergen confirmation was recorded.";
    /** order-allergen-panel.tsx ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY. */
    public static final String ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY = "Allergen information not recorded for this order";
    /** order-allergen-panel.tsx ALLERGEN_PANEL_EMPTY_HEADING_COPY. */
    public static final String ALLERGEN_PANEL_EMPTY_HEADING_COPY = "No allergens declared for this order";

    /** order-allergen-panel.tsx allergenFlagCopy(flag): advisory, never merged into either set. */
    public static String allergenFlagCopy(OrderAllergenFlagDto flag) {
        return "Check — " + flag.productName() + ": the ingredients list mentions " + flag.allergenName()
                + ", which the kitchen has not declared for this item. Ask the kitchen before you order.";
    }

    /**
     * frontend/lib/order-status-announcement.ts ORDER_STATUS_COPY labels: the words the tracking
     * pages show for each status (parity-tested), so an email and the page name a status alike.
     */
    static final Map<OrderStatus, String> CUSTOMER_STATUS_LABELS = Map.of(
            OrderStatus.DRAFT, "Awaiting payment",
            OrderStatus.PENDING, "Received",
            OrderStatus.CONFIRMED, "Confirmed",
            OrderStatus.PREPARING, "Preparing",
            OrderStatus.READY, "Ready",
            OrderStatus.COMPLETED, "Completed",
            OrderStatus.CANCELLED, "Cancelled",
            OrderStatus.REFUNDED, "Refunded");

    /** The customer's word for a status; a status with no mapping reads as its name, never blank. */
    static String customerStatusLabel(OrderStatus status) {
        return CUSTOMER_STATUS_LABELS.getOrDefault(status, status.name());
    }

    /** The From display-name suffix (D-13). */
    static final String VIA_PLATFORM = " via J'Toye";
    static final String PLATFORM_NAME = "J'Toye";
    static final String HEADER_LIST_UNSUBSCRIBE = "List-Unsubscribe";
    static final String HEADER_LIST_UNSUBSCRIBE_POST = "List-Unsubscribe-Post";
    static final String ONE_CLICK_POST_VALUE = "List-Unsubscribe=One-Click";
    static final String UNSUBSCRIBE_FOOTER_PREFIX = "Unsubscribe from order status updates: ";

    // ---- Configuration ---------------------------------------------------------------------------

    private final JavaMailSender mailSender;
    private final NotificationProperties notificationProperties;
    private final UnsubscribeTokenService unsubscribeTokenService;

    @Value("${notification.email.from:noreply@jtoye.uk}")
    private String fromAddress;

    @Value("${notification.email.enabled:true}")
    private boolean emailEnabled;

    @Value("${notification.email.tracking-base-url:http://localhost:3000}")
    private String trackingBaseUrl;

    @Value("${jtoye.platform.legal-name:J'Toye Digital Ltd}")
    private String platformLegalName;

    @Value("${jtoye.platform.company-number:16471464}")
    private String platformCompanyNumber;

    @Value("${jtoye.platform.registration-jurisdiction:England & Wales}")
    private String platformRegistration;

    /** D-14: from the Companies House record, set by 31.1-27. Empty means the line is omitted, never blank. */
    @Value("${jtoye.platform.registered-office:}")
    private String platformRegisteredOffice;

    public EmailNotificationService(JavaMailSender mailSender) {
        this(mailSender, null, null);
    }

    @Autowired
    public EmailNotificationService(JavaMailSender mailSender,
                                    @Nullable NotificationProperties notificationProperties,
                                    @Nullable UnsubscribeTokenService unsubscribeTokenService) {
        this.mailSender = mailSender;
        this.notificationProperties = notificationProperties;
        this.unsubscribeTokenService = unsubscribeTokenService;
    }

    // ---- One method per status; one send per call -------------------------------------------------

    @Async
    public void sendOrderConfirmation(OrderStateChangeEvent event, String recipientEmail,
                                      CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s has been sent to %s They will confirm it shortly, and you'll get another \
                email when they start preparing it.""".formatted(event.orderNumber(), Sentences.withFullStop(shopLabel(ctx)));
        // #981: order-received is the statutory CCR 2013 reg 16 durable-medium contract
        // confirmation — never carries List-Unsubscribe.
        send(event, recipientEmail, "order-received", subject(event, "Received"), lead, ctx, true, false);
    }

    @Async
    public void sendOrderConfirmed(OrderStateChangeEvent event, String recipientEmail,
                                   CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Great news! Your order %s has been confirmed by %s

                Preparation will begin soon.""".formatted(event.orderNumber(), Sentences.withFullStop(shopLabel(ctx)));
        send(event, recipientEmail, "order-confirmed", subject(event, "Confirmed"), lead, ctx, false, true);
    }

    @Async
    public void sendOrderPreparing(OrderStateChangeEvent event, String recipientEmail,
                                   CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s is now being prepared by %s

                You'll get another email when it's ready.""".formatted(event.orderNumber(), Sentences.withFullStop(shopLabel(ctx)));
        send(event, recipientEmail, "order-preparing", subject(event, "Being Prepared"), lead, ctx, false, true);
    }

    /**
     * READY copy, branched on how the order is fulfilled (#502), read from the context.
     *
     * <p>This transition once sent collection-only copy unconditionally, so every DELIVERY
     * customer was told to come and pick the order up. Both order-creation paths now set the
     * fulfilment type explicitly ({@code FulfilmentPolicy}, COR-1); the V45 column default is
     * still {@code 'DELIVERY'} for pre-V45 history.
     *
     * <p>A {@code null} type resolves to the DELIVERY copy, matching the column default. The
     * asymmetry is deliberate: "come and collect" is the actively harmful answer when the
     * fulfilment mode is unknown, so it is never the fallback.
     *
     * <p><b>#458 note:</b> the DELIVERY copy says the order is ready and will be on its way, NOT
     * that it is out for delivery: there is no {@code DISPATCHED} status, so READY cannot
     * truthfully claim the order has left the shop.
     */
    @Async
    public void sendOrderReady(OrderStateChangeEvent event, String recipientEmail,
                               CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String subject = "Order " + event.orderNumber() + " — Ready!";

        if (ctx.fulfilmentType() == FulfilmentType.COLLECTION) {
            String lead = """
                    Your order %s is ready for collection from %s!

                    Please pick it up at your earliest convenience.""".formatted(event.orderNumber(), shopLabel(ctx));
            send(event, recipientEmail, "order-ready-collection", subject, lead, ctx, false, true);
            return;
        }

        String lead = """
                Your order %s is ready and will be on its way to you shortly.

                There's no need to come to the shop — %s will deliver it to the address on your order.\
                """.formatted(event.orderNumber(), shopLabel(ctx));
        send(event, recipientEmail, "order-ready-delivery", subject, lead, ctx, false, true);
    }

    @Async
    public void sendOrderCompletedNotification(OrderStateChangeEvent event, String recipientEmail,
                                               CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s from %s has been completed.

                Thank you for ordering from %s""".formatted(event.orderNumber(), shopLabel(ctx),
                Sentences.withFullStop(shopLabel(ctx)));
        send(event, recipientEmail, "order-completed", subject(event, "Completed"), lead, ctx, false, true);
    }

    @Async
    public void sendOrderCancelledNotification(OrderStateChangeEvent event, String recipientEmail,
                                               CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        // SW-ADE-12 (swarm epic #964): the customer's word for where the order had got to, never
        // the enum name (an unpaid card order read "Previous status: DRAFT"). No line at all when
        // the event carries no previous status, rather than "null".
        String previous = event.previousStatus() == null ? ""
                : "It was cancelled while it was: " + customerStatusLabel(event.previousStatus()) + ".\n";
        String lead = """
                Your order %s from %s has been cancelled.

                %sIf this was unexpected, please contact %s directly.""".formatted(
                event.orderNumber(), shopLabel(ctx), previous, shopLabel(ctx));
        // #981: order-cancelled is a non-suppressible transactional notice — never carries List-Unsubscribe.
        send(event, recipientEmail, "order-cancelled", subject(event, "Cancelled"), lead, ctx, false, false);
    }

    // ---- Staff invitation (D-07, 37-07) ------------------------------------------------------------

    /** "10 October 2026, 14:32": the expiry as a person in the UK reads it (UI-SPEC § Copywriting). */
    static final DateTimeFormatter INVITE_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.UK);

    /**
     * The invitation email (D-07, UI-SPEC § Copywriting B1-B3): plain text, one link, single use, the
     * expiry in UK time, and "Nothing happens unless you accept".
     *
     * <p>Sent from J'Toye itself (no shop Reply-To): the inviter is a person at the business, but the
     * link is the platform's. The inviter and business names are tenant-controlled, so they go through
     * {@link #sanitise} before they reach the subject header or a body line (T-31.1-85).
     *
     * <p><b>Logging (T-37-17).</b> The address and the link are never logged: the link carries a
     * bearer token until it is used or expires. Only the event name is logged, and on failure only the
     * exception class (a mail exception's message can quote the address).
     *
     * <p><b>SW-TND-02 (swarm epic #964).</b> The subject reads as an invitation ("{inviter} has
     * invited you to help run {business} on J'Toye"), and the body names the role, the shop (or all
     * shops) and, from {@link #inviteRoleCapabilities}, what the role can do.
     *
     * @param recipientEmail the normalised invitee address; the link goes here and nowhere else
     * @param role           the grant the invitation carries; named and described in the body
     * @param link           {accept-base-url}#token={tenantId}.{token} (token in the fragment, 37-08); never
     *                       logged, never stored readable
     * @param expiresAt      the same instant written to {@code staff_invite.expires_at}
     */
    @Async
    public void sendStaffInvite(String recipientEmail, String inviterName, String businessName,
                                ShopRole role, String shopLabel, String link, OffsetDateTime expiresAt) {
        if (!emailEnabled) {
            log.debug("event=staff_invite_email_skipped reason=email_disabled");
            return;
        }
        if (recipientEmail == null || recipientEmail.isBlank() || link == null || link.isBlank()
                || expiresAt == null || role == null) {
            log.warn("event=staff_invite_email_skipped reason=incomplete");
            return;
        }
        String inviter = Objects.requireNonNullElse(sanitise(inviterName), "A Group admin");
        String business = Objects.requireNonNullElse(sanitise(businessName), "a business");
        String roleName = StaffInviteService.roleLabel(role);
        String shop = sanitise(shopLabel);
        // SW-TND-02: "at {shop}" for one shop; "for all shops" when the grant is tenant-wide.
        String where = shop == null || shop.equals("all shops") ? ", for all shops." : " at " + Sentences.withFullStop(shop);
        String expiry = expiresAt.atZoneSameInstant(ClockConfig.UK_ZONE).format(INVITE_EXPIRY_FORMAT);
        String subject = inviter + " has invited you to help run " + business + " on " + PLATFORM_NAME;
        String body = """
                %s has invited you to help run %s on J'Toye.

                Your role: %s%s
                %s

                Accept the invitation: %s

                This link works once and expires on %s (UK time).

                If you weren't expecting this, you can ignore this email. Nothing happens unless you accept.
                """.formatted(inviter, business, roleName, where, inviteRoleCapabilities(role), link, expiry);
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, PLATFORM_NAME, "UTF-8"));
            helper.setTo(new InternetAddress(recipientEmail, true));
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(mime);
            log.info("event=staff_invite_email_sent");
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // The exception class only: a mail exception's message can quote the recipient (V7).
            log.error("event=staff_invite_email_failed error={}", e.getClass().getSimpleName());
        }
    }

    /**
     * SW-TND-02 (swarm epic #964): what the invited role can do, in one or two plain sentences. Each
     * is the {@link ShopRole} model as it is enforced, not marketing: STAFF reads the shop and moves
     * orders through their states but writes no catalogue; SHOP_MANAGER has full control of a granted
     * shop but no staff management and no shop create or delete; GROUP_ADMIN has every shop, shop
     * create and delete, and staff management.
     */
    public static String inviteRoleCapabilities(ShopRole role) {
        return switch (role) {
            case STAFF -> "As Staff you can see the shop's orders and move them along as they are prepared. "
                    + "You can't change products or prices.";
            case SHOP_MANAGER -> "As a Shop manager you can run the shop: its products and prices, its orders "
                    + "and its settings. You can't invite people or add or remove shops.";
            case GROUP_ADMIN -> "As a Group admin you can run every shop in the business, add and remove shops, "
                    + "and invite people and choose what they can do.";
        };
    }

    // ---- Webhook auto-pause (UXT-038, 37-43) ------------------------------------------------------

    /** UI-SPEC § Copywriting E1/E2, "Pause email". */
    public static final String WEBHOOK_PAUSED_SUBJECT = "Your J'Toye webhook endpoint was paused";
    static final String WEBHOOK_PAUSED_FIX_LINE = "Fix the endpoint, then resume it in J'Toye: Dashboard → Webhooks.";

    /** "14:32 on 13 October 2026": the pause time as a person in the UK reads it. */
    static final DateTimeFormatter WEBHOOK_PAUSED_AT_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm 'on' d MMMM yyyy", Locale.UK);

    /**
     * The held-events sentence (37-43 assumptions for the UI-SPEC's unresolved E2 rows): "1 event is
     * held" for one, "{n} events are held" for more, and null at 0, when the sentence is omitted
     * rather than claiming "0 events are held". The dashboard banner uses the same three forms.
     */
    static String webhookHeldSentence(long heldCount) {
        if (heldCount <= 0) {
            return null;
        }
        return (heldCount == 1 ? "1 event is held" : heldCount + " events are held")
                + ". They'll be delivered when you resume the endpoint.";
    }

    /**
     * The webhook auto-pause notice (UXT-038, UI-SPEC § Copywriting E1/E2 "Pause email"): plain text,
     * sent once per ACTIVE -> AUTO_PAUSED transition, after that transition commits, to the tenant's
     * contact address. Sent from J'Toye itself (no shop Reply-To): it is about the vendor's own
     * integration, not an order.
     *
     * <p>The endpoint URL is vendor-supplied, so it passes {@link #sanitise} before it reaches the body:
     * a control character cannot forge a line. <b>Logging:</b> neither the recipient nor the URL (a
     * receiver URL can carry a token in its query) is ever logged, only the event name and, on failure,
     * the exception class.
     *
     * @param heldCount the deliveries held at the moment of the pause, counted by the database
     */
    @Async
    public void sendWebhookPaused(String recipientEmail, String targetUrl, OffsetDateTime pausedAt, long heldCount) {
        if (!emailEnabled) {
            log.debug("event=webhook_paused_email_skipped reason=email_disabled");
            return;
        }
        String url = sanitise(targetUrl);
        if (recipientEmail == null || recipientEmail.isBlank() || url == null || pausedAt == null) {
            log.warn("event=webhook_paused_email_skipped reason=incomplete");
            return;
        }
        String at = pausedAt.atZoneSameInstant(ClockConfig.UK_ZONE).format(WEBHOOK_PAUSED_AT_FORMAT);
        StringBuilder body = new StringBuilder()
                .append("We paused deliveries to ").append(url).append(" at ").append(at)
                .append(" (UK time) because they kept failing.\n\n");
        String held = webhookHeldSentence(heldCount);
        if (held != null) {
            body.append(held).append("\n\n");
        }
        body.append(WEBHOOK_PAUSED_FIX_LINE).append('\n');
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, PLATFORM_NAME, "UTF-8"));
            helper.setTo(new InternetAddress(recipientEmail, true));
            helper.setSubject(WEBHOOK_PAUSED_SUBJECT);
            helper.setText(body.toString(), false);
            mailSender.send(mime);
            log.info("event=webhook_paused_email_sent");
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // The exception class only: a mail exception's message can quote the recipient (V7).
            log.error("event=webhook_paused_email_failed error={}", e.getClass().getSimpleName());
        }
    }

    // ---- Body ------------------------------------------------------------------------------------

    /**
     * The whole plain-text body: the status lead, the order summary (when the listener read one), the
     * tracking link, (order received only) the allergen record, the seller block, (order received
     * only) the cancellation statement, and the platform footer.
     */
    String body(OrderStateChangeEvent event, String lead, CustomerEmailContext ctx, boolean orderReceived) {
        return body(event, lead, ctx, orderReceived, null);
    }

    String body(OrderStateChangeEvent event, String lead, CustomerEmailContext ctx, boolean orderReceived,
                @Nullable String unsubscribePageUrl) {
        StringBuilder b = new StringBuilder();
        b.append(lead).append("\n\n");
        String summary = orderSummary(ctx);
        if (summary != null) {
            b.append(summary).append('\n');
        }
        b.append(trackingLink(event)).append('\n');
        if (orderReceived) {
            b.append('\n').append(allergenRecord(ctx));
        }
        b.append('\n').append(sellerBlock(ctx));
        if (orderReceived) {
            b.append('\n').append(CANCELLATION_STATEMENT_COPY).append('\n');
        }
        b.append('\n').append(footer(ctx, unsubscribePageUrl));
        return b.toString();
    }

    /**
     * SW-JRD-15 (swarm epic #964, issue #826): what was ordered, from which shop, for how much, and
     * where it goes. Every value but the total is customer- or vendor-written, so each passes
     * {@link #oneLine}: a product name cannot forge a second "Total:" line. Null when the listener
     * read no summary: the email then has no section rather than an empty one or a £0.00 total.
     */
    private String orderSummary(CustomerEmailContext ctx) {
        OrderEmailSummary summary = ctx.summary();
        if (summary == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        b.append("Your order from ").append(shopLabel(ctx)).append('\n');
        for (OrderEmailSummary.Line line : summary.lines()) {
            String name = oneLine(line.name());
            b.append(name == null ? "Item" : name).append(" \u00d7 ")
                    .append(line.quantity() == null ? "?" : line.quantity()).append('\n');
        }
        if (summary.totalPennies() != null) {
            b.append("Total: ").append(pounds(summary.totalPennies())).append('\n');
        }
        if (ctx.fulfilmentType() == FulfilmentType.COLLECTION) {
            String where = oneLine(summary.collectionAddress());
            b.append("Collect from: ").append(shopLabel(ctx));
            if (where != null) {
                b.append(", ").append(where);
            }
            b.append('\n');
        } else {
            List<String> address = summary.deliveryAddress().stream()
                    .map(EmailNotificationService::oneLine).filter(Objects::nonNull).toList();
            if (!address.isEmpty()) {
                b.append("Delivery to: ").append(String.join(", ", address)).append('\n');
            }
        }
        return b.toString();
    }

    /** "£12.50": pennies as a UK customer reads a price. */
    static String pounds(long pennies) {
        long abs = Math.abs(pennies);
        return (pennies < 0 ? "-" : "") + "\u00a3" + (abs / 100) + "." + String.format(Locale.UK, "%02d", abs % 100);
    }

    /** D-08: three statements, never merged; null is never rendered as "none". */
    private String allergenRecord(CustomerEmailContext ctx) {
        StringBuilder b = new StringBuilder();
        b.append(RECORDED_ALLERGEN_SET_TITLE_COPY).append('\n');

        List<String> acknowledged = ctx.acknowledgedAllergens();
        if (acknowledged == null) {
            b.append(ctx.placedVia() == OrderChannel.VENDOR ? VENDOR_PLACED_COPY : RECORDED_ACK_NOT_RECORDED_COPY);
        } else if (acknowledged.isEmpty()) {
            b.append(RECORDED_ACK_NONE_COPY);
        } else {
            b.append(RECORDED_ACK_HEADING_COPY).append(' ').append(String.join(", ", acknowledged));
        }
        b.append('\n');

        List<String> recorded = ctx.recordedAllergens();
        b.append(RECORDED_SET_HEADING_COPY).append(' ');
        if (recorded == null) {
            b.append(ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY);
        } else if (recorded.isEmpty()) {
            b.append(ALLERGEN_PANEL_EMPTY_HEADING_COPY);
        } else {
            b.append(String.join(", ", recorded));
        }
        b.append('\n');

        for (OrderAllergenFlagDto flag : ctx.flags()) {
            b.append(allergenFlagCopy(flag)).append('\n');
        }
        return b.toString();
    }

    /** The 31.1-24 seller block, same fields in the same fixed order, absent fields absent. */
    private String sellerBlock(CustomerEmailContext ctx) {
        StringBuilder b = new StringBuilder();
        b.append(SELLER_BLOCK_HEADING_COPY).append('\n');
        SellerIdentityDto seller = ctx.seller();
        if (seller == null) {
            b.append(SELLER_DETAILS_MISSING_COPY).append('\n');
            b.append(PLATFORM_CONTACT_INTRO_COPY).append(' ').append(PLATFORM_CONTACT_LINK_COPY)
                    .append(": ").append(trackingBaseUrl).append("/legal\n");
            return b.toString();
        }
        b.append("Seller: ").append(oneLine(seller.legalName()));
        String entityLabel = seller.entityType() == null ? null : SELLER_ENTITY_TYPE_LABELS.get(seller.entityType());
        if (entityLabel != null) {
            b.append(" (").append(entityLabel).append(')');
        }
        b.append('\n');
        appendField(b, "Company number", seller.companyNumber());
        appendField(b, "VAT number", seller.vatNumber());
        if (!seller.addressLines().isEmpty()) {
            appendField(b, "Address", String.join(", ", seller.addressLines()));
        }
        appendField(b, "Email", seller.email());
        appendField(b, "Phone", seller.phone());
        b.append('\n').append(PLATFORM_NOT_SELLER_COPY).append('\n');
        return b.toString();
    }

    private static void appendField(StringBuilder b, String term, String value) {
        String v = oneLine(value);
        if (v != null) {
            b.append(term).append(": ").append(v).append('\n');
        }
    }

    /** The platform's own identity (D-14). The registered office line is omitted, never blank. */
    private String footer(CustomerEmailContext ctx, @Nullable String unsubscribePageUrl) {
        StringBuilder b = new StringBuilder("—\n");
        String shop = sanitise(ctx.shopName());
        b.append("This email was sent by ").append(PLATFORM_NAME).append(" on behalf of ")
                .append(Sentences.withFullStop(shop == null ? "the seller" : shop)).append('\n');
        b.append(platformLegalName).append(", registered in ").append(platformRegistration)
                .append(", company number ").append(platformCompanyNumber).append(".\n");
        String office = oneLine(platformRegisteredOffice);
        if (office != null) {
            b.append("Registered office: ").append(office).append('\n');
        }
        if (unsubscribePageUrl != null && !unsubscribePageUrl.isBlank()) {
            b.append(UNSUBSCRIBE_FOOTER_PREFIX).append(unsubscribePageUrl).append('\n');
        }
        return b.toString();
    }

    private String trackingLink(OrderStateChangeEvent event) {
        return "Track your order: " + trackingBaseUrl + "/track?order=" + event.orderNumber();
    }

    private static String subject(OrderStateChangeEvent event, String status) {
        return "Order " + event.orderNumber() + " — " + status;
    }

    private static String shopLabel(CustomerEmailContext ctx) {
        String shop = sanitise(ctx.shopName());
        return shop == null ? "the shop" : shop;
    }

    private static CustomerEmailContext orUnknown(CustomerEmailContext ctx) {
        return ctx == null ? CustomerEmailContext.unknownShop(null) : ctx;
    }

    // ---- Header safety ---------------------------------------------------------------------------

    /**
     * T-31.1-85: replaces every control character (CR, LF, TAB, NUL, NEL, …) and the Unicode line
     * and paragraph separators with a space, collapses runs of whitespace and trims. Returns null
     * for null or blank. This runs BEFORE the value reaches {@link InternetAddress}: encoding alone
     * is not trusted to neutralise a line break in a value the vendor controls.
     */
    static String sanitise(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder b = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> b.appendCodePoint(isUnsafe(cp) ? ' ' : cp));
        String collapsed = b.toString().replaceAll("\\s+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static boolean isUnsafe(int cp) {
        int type = Character.getType(cp);
        return Character.isISOControl(cp)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** Body values are single lines too: a vendor-supplied value cannot forge a line of the record. */
    private static String oneLine(String value) {
        return sanitise(value);
    }

    /** "{shop} via J'Toye", or "J'Toye" alone when no shop name is known. */
    static String displayName(CustomerEmailContext ctx) {
        String shop = sanitise(ctx.shopName());
        return shop == null ? PLATFORM_NAME : shop + VIA_PLATFORM;
    }

    /** Exactly one strictly-parsed address, its bare form only; null (Reply-To omitted) otherwise. */
    private static InternetAddress replyTo(String shopEmail, String orderNumber) {
        if (shopEmail == null || shopEmail.isBlank()) {
            return null;
        }
        String trimmed = shopEmail.strip();
        if (trimmed.codePoints().anyMatch(EmailNotificationService::isUnsafe)) {
            log.warn("Shop email for order {} contains control characters; Reply-To omitted", orderNumber);
            return null;
        }
        try {
            InternetAddress parsed = new InternetAddress(trimmed, true);
            InternetAddress bare = new InternetAddress(parsed.getAddress(), true);
            bare.validate();
            return bare;
        } catch (AddressException e) {
            log.warn("Shop email for order {} is not a single valid address; Reply-To omitted", orderNumber);
            return null;
        }
    }

    // ---- Send ------------------------------------------------------------------------------------

    private record UnsubscribeLinks(String pageUrl, String oneClickUrl) {
        static final UnsubscribeLinks NONE = new UnsubscribeLinks(null, null);
    }

    private UnsubscribeLinks unsubscribeLinks(UUID tenantId, String recipientEmail, boolean unsubscribeable) {
        if (!unsubscribeable
                || tenantId == null
                || recipientEmail == null || recipientEmail.isBlank()
                || notificationProperties == null || !notificationProperties.configured()
                || unsubscribeTokenService == null) {
            return UnsubscribeLinks.NONE;
        }
        String token = unsubscribeTokenService.tokenFor(tenantId, recipientEmail, NotificationCategory.ORDERS);
        NotificationProperties.Unsubscribe cfg = notificationProperties.getUnsubscribe();
        String query = "tenant=" + urlEncode(tenantId.toString())
                + "&email=" + urlEncode(recipientEmail)
                + "&category=" + urlEncode(NotificationCategory.ORDERS.name())
                + "&token=" + urlEncode(token);
        String pageUrl = trimTrailingSlash(cfg.getBaseUrl()) + cfg.getPagePath() + "?" + query;
        String oneClickUrl = cfg.oneClickConfigured()
                ? trimTrailingSlash(cfg.getOneClickBaseUrl()) + cfg.getOneClickPath() + "?" + query
                : null;
        return new UnsubscribeLinks(pageUrl, oneClickUrl);
    }

    private static String urlEncode(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : (s == null ? "" : s);
    }

    private void send(OrderStateChangeEvent event, String recipientEmail, String template, String subject,
                      String lead, CustomerEmailContext ctx, boolean orderReceived, boolean unsubscribeable) {
        String orderNumber = event.orderNumber();
        if (!emailEnabled || recipientEmail == null || recipientEmail.isBlank()) {
            log.debug("Email notification skipped for order {} ({}): enabled={}, recipient present={}",
                    orderNumber, template, emailEnabled, recipientEmail != null && !recipientEmail.isBlank());
            return;
        }
        UnsubscribeLinks links = unsubscribeLinks(event.tenantId(), recipientEmail, unsubscribeable);
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(new InternetAddress(fromAddress, displayName(ctx), "UTF-8"));
            InternetAddress replyTo = replyTo(ctx.shopEmail(), orderNumber);
            if (replyTo != null) {
                helper.setReplyTo(replyTo);
            }
            helper.setTo(recipientEmail);
            helper.setSubject(subject);
            helper.setText(body(event, lead, ctx, orderReceived, links.pageUrl()), false);
            if (links.oneClickUrl() != null && !links.oneClickUrl().isBlank()) {
                mime.addHeader(HEADER_LIST_UNSUBSCRIBE, "<" + links.oneClickUrl() + ">");
                mime.addHeader(HEADER_LIST_UNSUBSCRIBE_POST, ONE_CLICK_POST_VALUE);
            } else if (links.pageUrl() != null && !links.pageUrl().isBlank()) {
                mime.addHeader(HEADER_LIST_UNSUBSCRIBE, "<" + links.pageUrl() + ">");
            }
            mailSender.send(mime);
            log.info("Email notification sent for order {} ({})", orderNumber, template);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // The exception class only: a mail exception's message can quote the recipient (V7).
            log.error("Failed to send email for order {} ({}): {}", orderNumber, template,
                    e.getClass().getSimpleName());
        }
    }
}
