package uk.jtoye.core.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderChannel;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

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

    /** The From display-name suffix (D-13). */
    static final String VIA_PLATFORM = " via J'Toye";
    static final String PLATFORM_NAME = "J'Toye";

    // ---- Configuration ---------------------------------------------------------------------------

    private final JavaMailSender mailSender;

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
        this.mailSender = mailSender;
    }

    // ---- One method per status; one send per call -------------------------------------------------

    @Async
    public void sendOrderConfirmation(OrderStateChangeEvent event, String recipientEmail,
                                      CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s has been sent to %s. They will confirm it shortly, and you'll get another \
                email when they start preparing it.""".formatted(event.orderNumber(), shopLabel(ctx));
        send(event, recipientEmail, "order-received", subject(event, "Received"), lead, ctx, true);
    }

    @Async
    public void sendOrderConfirmed(OrderStateChangeEvent event, String recipientEmail,
                                   CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Great news! Your order %s has been confirmed by %s.

                Preparation will begin soon.""".formatted(event.orderNumber(), shopLabel(ctx));
        send(event, recipientEmail, "order-confirmed", subject(event, "Confirmed"), lead, ctx, false);
    }

    @Async
    public void sendOrderPreparing(OrderStateChangeEvent event, String recipientEmail,
                                   CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s is now being prepared by %s.

                You'll get another email when it's ready.""".formatted(event.orderNumber(), shopLabel(ctx));
        send(event, recipientEmail, "order-preparing", subject(event, "Being Prepared"), lead, ctx, false);
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
            send(event, recipientEmail, "order-ready-collection", subject, lead, ctx, false);
            return;
        }

        String lead = """
                Your order %s is ready and will be on its way to you shortly.

                There's no need to come to the shop — %s will deliver it to the address on your order.\
                """.formatted(event.orderNumber(), shopLabel(ctx));
        send(event, recipientEmail, "order-ready-delivery", subject, lead, ctx, false);
    }

    @Async
    public void sendOrderCompletedNotification(OrderStateChangeEvent event, String recipientEmail,
                                               CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s from %s has been completed.

                Thank you for ordering from %s.""".formatted(event.orderNumber(), shopLabel(ctx), shopLabel(ctx));
        send(event, recipientEmail, "order-completed", subject(event, "Completed"), lead, ctx, false);
    }

    @Async
    public void sendOrderCancelledNotification(OrderStateChangeEvent event, String recipientEmail,
                                               CustomerEmailContext context) {
        CustomerEmailContext ctx = orUnknown(context);
        String lead = """
                Your order %s from %s has been cancelled.

                Previous status: %s
                If this was unexpected, please contact %s directly.""".formatted(
                event.orderNumber(), shopLabel(ctx), event.previousStatus(), shopLabel(ctx));
        send(event, recipientEmail, "order-cancelled", subject(event, "Cancelled"), lead, ctx, false);
    }

    // ---- Body ------------------------------------------------------------------------------------

    /**
     * The whole plain-text body: the status lead, the tracking link, (order received only) the
     * allergen record, the seller block, (order received only) the cancellation statement, and the
     * platform footer.
     */
    String body(OrderStateChangeEvent event, String lead, CustomerEmailContext ctx, boolean orderReceived) {
        StringBuilder b = new StringBuilder();
        b.append(lead).append("\n\n").append(trackingLink(event)).append('\n');
        if (orderReceived) {
            b.append('\n').append(allergenRecord(ctx));
        }
        b.append('\n').append(sellerBlock(ctx));
        if (orderReceived) {
            b.append('\n').append(CANCELLATION_STATEMENT_COPY).append('\n');
        }
        b.append('\n').append(footer(ctx));
        return b.toString();
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
    private String footer(CustomerEmailContext ctx) {
        StringBuilder b = new StringBuilder("—\n");
        String shop = sanitise(ctx.shopName());
        b.append("This email was sent by ").append(PLATFORM_NAME).append(" on behalf of ")
                .append(shop == null ? "the seller" : shop).append(".\n");
        b.append(platformLegalName).append(", registered in ").append(platformRegistration)
                .append(", company number ").append(platformCompanyNumber).append(".\n");
        String office = oneLine(platformRegisteredOffice);
        if (office != null) {
            b.append("Registered office: ").append(office).append('\n');
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

    private void send(OrderStateChangeEvent event, String recipientEmail, String template, String subject,
                      String lead, CustomerEmailContext ctx, boolean orderReceived) {
        String orderNumber = event.orderNumber();
        if (!emailEnabled || recipientEmail == null || recipientEmail.isBlank()) {
            log.debug("Email notification skipped for order {} ({}): enabled={}, recipient present={}",
                    orderNumber, template, emailEnabled, recipientEmail != null && !recipientEmail.isBlank());
            return;
        }
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
            helper.setText(body(event, lead, ctx, orderReceived), false);
            mailSender.send(mime);
            log.info("Email notification sent for order {} ({})", orderNumber, template);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // The exception class only: a mail exception's message can quote the recipient (V7).
            log.error("Failed to send email for order {} ({}): {}", orderNumber, template,
                    e.getClass().getSimpleName());
        }
    }
}
