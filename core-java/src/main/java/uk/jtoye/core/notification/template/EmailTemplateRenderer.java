package uk.jtoye.core.notification.template;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

/**
 * Renders a {@link RenderedEmail} ({subject, html, text}) for a lifecycle event
 * (D-01). One template family per {@code order.* / onboarding.* / payment.* /
 * refund.*}, with the copy selected per {@link RecipientRole} so customer and
 * vendor receive an audience-appropriate variant. Unknown event types fall back
 * to a generic branded template so this seam is TOTAL — {@link #render} never
 * throws.
 *
 * <p>No template engine (no Thymeleaf, zero new deps): inline-styled HTML
 * text-blocks + a plain-text alternative, substituted with
 * {@link String#formatted(Object...)} mirroring the existing
 * {@code EmailNotificationService} style. Every email carries the J'Toye brand
 * header/footer and a one-click unsubscribe footer link.
 *
 * <h2>Escaping contract (issue #279)</h2>
 * There is no template engine to auto-escape, so escaping is enforced by which
 * accessor a builder calls. There are exactly three contexts:
 * <ul>
 *   <li><b>HTML</b> — every model value reaching {@code bodyHtml}, the headline
 *       or an attribute goes through {@link #sHtml}/{@link #esc}. This is the
 *       DEFAULT: a new field is escaped by construction, not by remembering.</li>
 *   <li><b>Plain text</b> ({@link #wrapText}) — raw via {@link #s}. There is no
 *       markup context, and escaping here would show a vendor literal
 *       {@code &amp;lt;} in their inbox.</li>
 *   <li><b>MIME subject</b> — raw via {@link #s}. A header, not HTML; the mail
 *       library encodes it.</li>
 * </ul>
 * The one value deliberately NOT escaped in the HTML path is
 * {@code Copy.bodyHtml} itself — it IS the markup the builder composed, and
 * escaping it would ship every email as visible angle brackets.
 */
@Component
public class EmailTemplateRenderer {

    /**
     * The charset the whole email is built and sent in ({@code <meta charset>}
     * here, {@code MimeMessageHelper(mime, true, "UTF-8")} in
     * {@code EmailChannel}). Passing it to {@code HtmlUtils} keeps the escape
     * minimal — only the five markup-significant characters
     * ({@code < > " ' &}) become references, so {@code £12.50} and the em-dashes
     * already in this file stay literal instead of turning into
     * {@code &pound;}/{@code &mdash;}.
     */
    private static final String HTML_CHARSET = "UTF-8";

    /**
     * Intermediate per-event copy, before the branded wrapper is applied.
     *
     * @param subject   MIME subject — RAW (a header, not HTML)
     * @param headline  short plain heading; used raw in text, escaped in HTML
     * @param bodyHtml  composed MARKUP — already escaped where it interpolates
     *                  model values, and never escaped again by the wrapper
     * @param bodyText  plain-text alternative — RAW
     */
    private record Copy(String subject, String headline, String bodyHtml, String bodyText) {
    }

    /**
     * Render the branded HTML + plain-text email for an event.
     *
     * @param eventType      e.g. {@code "order.ready"}; the prefix before the first
     *                       dot selects the template family
     * @param role           the audience (customer vs vendor variant)
     * @param model          substitution values (missing keys render as empty — never NPEs)
     * @param unsubscribeUrl one-click unsubscribe URL; null/blank renders a safe placeholder
     */
    public RenderedEmail render(String eventType, RecipientRole role,
                                Map<String, Object> model, String unsubscribeUrl) {
        Map<String, Object> m = model == null ? Map.of() : model;
        RecipientRole audience = role == null ? RecipientRole.CUSTOMER : role;
        String family = familyOf(eventType);

        Copy copy = switch (family) {
            case "order" -> orderCopy(audience, m);
            case "onboarding" -> onboardingCopy(audience, m);
            case "payment" -> paymentCopy(audience, m);
            case "refund" -> refundCopy(audience, m);
            default -> genericCopy(audience, m);
        };

        return new RenderedEmail(
                copy.subject(),
                wrapHtml(copy.headline(), copy.bodyHtml(), unsubscribeUrl),
                wrapText(copy.headline(), copy.bodyText(), unsubscribeUrl));
    }

    // --- per-family copy -----------------------------------------------------

    private Copy orderCopy(RecipientRole role, Map<String, Object> m) {
        String order = s(m, "orderNumber");
        String orderHtml = sHtml(m, "orderNumber");
        String subject = "Order %s — an update".formatted(order);
        if (role == RecipientRole.VENDOR) {
            return new Copy(subject, "Order update",
                    "<p style=\"margin:0;font-size:15px;line-height:1.6;\">Order <strong>%s</strong> has changed state. Open your dashboard to view the latest status.</p>".formatted(orderHtml),
                    "Order %s has changed state. Open your dashboard to view the latest status.".formatted(order));
        }
        return new Copy(subject, "Your order",
                "<p style=\"margin:0;font-size:15px;line-height:1.6;\">There's an update on your order <strong>%s</strong>. We'll keep you posted as it progresses.</p>".formatted(orderHtml),
                "There's an update on your order %s. We'll keep you posted as it progresses.".formatted(order));
    }

    private Copy onboardingCopy(RecipientRole role, Map<String, Object> m) {
        // Onboarding notifications are vendor-only (D-04, arch_no_platform_operator).
        // WR-03: render the "reason" the model actually carries (shopName was never
        // populated, so the previous template rendered blank). The reason is the
        // whole point of the stall email — it tells the vendor WHY onboarding
        // paused. IN-02: HTML-escape the reason in the html path only (it can carry
        // vendor/system free text); the plain-text path has no markup context.
        // #279: the escape now rides the shared sHtml() helper rather than a
        // one-off HtmlUtils call, so it is the same default every field gets.
        String reason = s(m, "reason");
        String subject = "Your J'Toye onboarding — an update";
        if (reason.isBlank()) {
            return new Copy(subject, "Onboarding update",
                    "<p style=\"margin:0;font-size:15px;line-height:1.6;\">There's an update on your J'Toye onboarding. Open your dashboard to see what's needed next.</p>",
                    "There's an update on your J'Toye onboarding. Open your dashboard to see what's needed next.");
        }
        String reasonHtml = sHtml(m, "reason");
        return new Copy(subject, "Onboarding update",
                "<p style=\"margin:0;font-size:15px;line-height:1.6;\">There's an update on your J'Toye onboarding: <strong>%s</strong>. Open your dashboard to see what's needed next.</p>".formatted(reasonHtml),
                "There's an update on your J'Toye onboarding: %s. Open your dashboard to see what's needed next.".formatted(reason));
    }

    private Copy paymentCopy(RecipientRole role, Map<String, Object> m) {
        String order = s(m, "orderNumber");
        String amount = s(m, "amount");
        String orderHtml = sHtml(m, "orderNumber");
        String amountHtml = sHtml(m, "amount");
        // WR-02: payment.succeeded AND payment.failed share this family/prefix, so
        // branch on the outcome carried in the model — a failed payment must NEVER
        // render the "received / thank you" success copy.
        boolean failed = "FAILED".equalsIgnoreCase(s(m, "paymentType"));

        if (failed) {
            String subject = "Payment failed — order %s".formatted(order);
            if (role == RecipientRole.VENDOR) {
                return new Copy(subject, "Payment failed",
                        "<p style=\"margin:0;font-size:15px;line-height:1.6;\">A payment attempt of %s for order <strong>%s</strong> failed — no funds were captured. The customer has been asked to try again.</p>".formatted(amountHtml, orderHtml),
                        "A payment attempt of %s for order %s failed — no funds were captured. The customer has been asked to try again.".formatted(amount, order));
            }
            return new Copy(subject, "Payment failed",
                    "<p style=\"margin:0;font-size:15px;line-height:1.6;\">We couldn't process your payment of %s for order <strong>%s</strong>. Please try again or update your payment details — your order is not yet paid.</p>".formatted(amountHtml, orderHtml),
                    "We couldn't process your payment of %s for order %s. Please try again or update your payment details — your order is not yet paid.".formatted(amount, order));
        }

        String subject = "Payment received — order %s".formatted(order);
        if (role == RecipientRole.VENDOR) {
            return new Copy(subject, "Payment received",
                    "<p style=\"margin:0;font-size:15px;line-height:1.6;\">A payment of %s was received for order <strong>%s</strong>.</p>".formatted(amountHtml, orderHtml),
                    "A payment of %s was received for order %s.".formatted(amount, order));
        }
        return new Copy(subject, "Payment received",
                "<p style=\"margin:0;font-size:15px;line-height:1.6;\">We've received your payment of %s for order <strong>%s</strong>. Thank you!</p>".formatted(amountHtml, orderHtml),
                "We've received your payment of %s for order %s. Thank you!".formatted(amount, order));
    }

    private Copy refundCopy(RecipientRole role, Map<String, Object> m) {
        String order = s(m, "orderNumber");
        String amount = s(m, "amount");
        String orderHtml = sHtml(m, "orderNumber");
        String amountHtml = sHtml(m, "amount");
        String subject = "Refund processed — order %s".formatted(order);
        if (role == RecipientRole.VENDOR) {
            return new Copy(subject, "Refund processed",
                    "<p style=\"margin:0;font-size:15px;line-height:1.6;\">A refund of %s has been processed for order <strong>%s</strong>.</p>".formatted(amountHtml, orderHtml),
                    "A refund of %s has been processed for order %s.".formatted(amount, order));
        }
        return new Copy(subject, "Refund processed",
                "<p style=\"margin:0;font-size:15px;line-height:1.6;\">Your refund of %s for order <strong>%s</strong> has been processed. It may take a few days to appear on your statement.</p>".formatted(amountHtml, orderHtml),
                "Your refund of %s for order %s has been processed. It may take a few days to appear on your statement.".formatted(amount, order));
    }

    private Copy genericCopy(RecipientRole role, Map<String, Object> m) {
        return new Copy("An update from J'Toye", "An update from J'Toye",
                "<p style=\"margin:0;font-size:15px;line-height:1.6;\">There's an update on your J'Toye account.</p>",
                "There's an update on your J'Toye account.");
    }

    // --- branded wrappers ----------------------------------------------------

    /**
     * Apply the branded chrome to one {@link Copy}.
     *
     * <p>#279: {@code headline} is escaped (text context) and {@code unsub} is
     * escaped for the {@code href="…"} ATTRIBUTE context — without it a value
     * containing a double quote could close the attribute and add an event
     * handler. {@code bodyHtml} is the ONLY interpolation left unescaped,
     * because it is the markup the copy builder composed; its model values were
     * already escaped at the point they were read.
     */
    private String wrapHtml(String headline, String bodyHtml, String unsubscribeUrl) {
        String unsub = (unsubscribeUrl == null || unsubscribeUrl.isBlank()) ? "#" : esc(unsubscribeUrl);
        String headlineHtml = esc(headline);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                </head>
                <body style="margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <table role="presentation" width="600" cellpadding="0" cellspacing="0" align="center" style="max-width:600px;margin:24px auto;background:#ffffff;border-radius:12px;overflow:hidden;">
                    <tr>
                      <td style="background:#f97316;padding:20px 24px;">
                        <span style="color:#ffffff;font-size:20px;font-weight:700;letter-spacing:-0.5px;">J'Toye</span>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:28px 24px;color:#0f172a;">
                        <h1 style="margin:0 0 16px;font-size:20px;font-weight:600;color:#0f172a;">%s</h1>
                        %s
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 24px;border-top:1px solid #e2e8f0;background:#f8fafc;color:#64748b;font-size:12px;line-height:1.6;">
                        <p style="margin:0 0 6px;">You're receiving this because of activity on your J'Toye account.</p>
                        <p style="margin:0;"><a href="%s" style="color:#64748b;text-decoration:underline;">Unsubscribe</a> from these emails.</p>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>""".formatted(headlineHtml, bodyHtml, unsub);
    }

    /**
     * The plain-text alternative. Deliberately RAW end to end — there is no
     * markup context, so escaping here would put a literal {@code &lt;} in the
     * recipient's inbox. Do NOT "harden" this method.
     */
    private String wrapText(String headline, String bodyText, String unsubscribeUrl) {
        String unsub = (unsubscribeUrl == null || unsubscribeUrl.isBlank()) ? "" : unsubscribeUrl;
        return """
                J'Toye

                %s

                %s

                --
                You're receiving this because of activity on your J'Toye account.
                Unsubscribe: %s
                """.formatted(headline, bodyText, unsub);
    }

    // --- helpers -------------------------------------------------------------

    private static String familyOf(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "";
        }
        int dot = eventType.indexOf('.');
        return dot > 0 ? eventType.substring(0, dot) : eventType;
    }

    /**
     * RAW model accessor — for the plain-text body and the MIME subject ONLY.
     *
     * <p><b>Never use this for a value that lands in an HTML body or attribute</b>
     * — use {@link #sHtml}. Missing keys render as empty (never NPE).
     */
    private static String s(Map<String, Object> model, String key) {
        Object v = model.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * ESCAPED model accessor — the default for every value interpolated into an
     * HTML body or attribute (issue #279). Any future field wired from
     * vendor- or customer-controlled input (a real {@code Shop.getName()}, a
     * product name, an order note) is safe by construction as long as its
     * builder reads it through here.
     */
    private static String sHtml(Map<String, Object> model, String key) {
        return esc(s(model, key));
    }

    /**
     * Escape one already-extracted value for an HTML text or attribute context.
     * Split out from {@link #sHtml} for values that do not come from the model
     * (the headline, the unsubscribe URL).
     */
    private static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, HTML_CHARSET);
    }
}
