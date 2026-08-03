package uk.jtoye.core.notification.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmailTemplateRenderer} — the {subject, html, text} seam
 * (D-01). Verifies branded HTML + plain-text alternative per event family, the
 * unsubscribe footer link, and both-audience (customer/vendor) rendering.
 */
class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    private static final String UNSUB = "https://jtoye.uk/api/v1/public/unsubscribe?token=abc123";

    /** Classic script-tag payload — proves an HTML *text* context is escaped. */
    private static final String XSS = "<script>alert(1)</script>";

    /** Attribute-breakout payload — proves an HTML *attribute* context is escaped. */
    private static final String ATTR_BREAKOUT = "\"><img src=x onerror=alert(1)>";

    /** The single {@code <a href="...">} in the branded footer. */
    private static final Pattern HREF = Pattern.compile("<a href=\"([^\"]*)\"");

    @Test
    @DisplayName("render(refund.processed) — html carries the unsubscribe link and text is non-blank")
    void refundProcessedHasUnsubscribeLinkAndText() {
        RenderedEmail email = renderer.render(
                "refund.processed",
                RecipientRole.CUSTOMER,
                Map.of("orderNumber", "ORD-900", "amount", "£12.50"),
                UNSUB);

        assertNotNull(email);
        assertFalse(email.subject().isBlank(), "subject must be non-blank");
        assertTrue(email.html().contains(UNSUB), "html must contain the unsubscribe URL");
        assertTrue(email.html().toLowerCase().contains("unsubscribe"), "html must have an unsubscribe footer");
        assertTrue(email.html().contains("ORD-900"), "html must carry the key fact (order number)");
        assertFalse(email.text().isBlank(), "plain-text alternative must be non-blank");
        assertTrue(email.text().contains("ORD-900"), "text must carry the same key fact");
    }

    @Test
    @DisplayName("render — brand header present in html for a branded email")
    void htmlCarriesBrandHeader() {
        RenderedEmail email = renderer.render(
                "order.ready",
                RecipientRole.CUSTOMER,
                Map.of("orderNumber", "ORD-1"),
                UNSUB);

        assertTrue(email.html().contains("J'Toye"), "html must carry the brand header/footer");
        assertTrue(email.text().contains("J'Toye"), "text must carry the brand marker too");
    }

    @Test
    @DisplayName("render — every event family produces a non-blank subject/html/text and never throws")
    void everyFamilyRenders() {
        for (String eventType : new String[]{
                "order.confirmed", "onboarding.state.manual_review",
                "payment.succeeded", "refund.processed", "something.unknown"}) {
            for (RecipientRole role : RecipientRole.values()) {
                RenderedEmail email = assertDoesNotThrow(
                        () -> renderer.render(eventType, role, Map.of(), UNSUB),
                        "render must not throw for " + eventType + "/" + role);
                assertFalse(email.subject().isBlank(), "subject non-blank for " + eventType);
                assertFalse(email.html().isBlank(), "html non-blank for " + eventType);
                assertFalse(email.text().isBlank(), "text non-blank for " + eventType);
                assertTrue(email.html().contains(UNSUB), "unsubscribe link present for " + eventType);
            }
        }
    }

    @Test
    @DisplayName("render — vendor and customer variants differ for the same event")
    void audienceVariantsDiffer() {
        Map<String, Object> model = Map.of("orderNumber", "ORD-42");
        RenderedEmail customer = renderer.render("order.confirmed", RecipientRole.CUSTOMER, model, UNSUB);
        RenderedEmail vendor = renderer.render("order.confirmed", RecipientRole.VENDOR, model, UNSUB);

        assertNotEquals(customer.html(), vendor.html(),
                "customer and vendor html should differ (both-audience variants, D-01)");
    }

    @Test
    @DisplayName("WR-02 — payment.failed renders failure copy, NOT the 'received/thank you' success copy")
    void paymentFailedRendersFailureCopy() {
        Map<String, Object> model = Map.of(
                "orderNumber", "ORD-77", "amount", "£42.50", "paymentType", "FAILED");

        for (RecipientRole role : RecipientRole.values()) {
            RenderedEmail email = renderer.render("payment.failed", role, model, UNSUB);

            String html = email.html().toLowerCase();
            String text = email.text().toLowerCase();
            assertFalse(html.contains("thank you"),
                    "failed-payment html must NOT thank the recipient (" + role + ")");
            assertFalse(html.contains("received"),
                    "failed-payment html must NOT claim payment was received (" + role + ")");
            assertFalse(text.contains("thank you"),
                    "failed-payment text must NOT thank the recipient (" + role + ")");
            assertFalse(text.contains("received"),
                    "failed-payment text must NOT claim payment was received (" + role + ")");
            assertTrue(html.contains("fail"),
                    "failed-payment html must signal failure (" + role + ")");
            assertTrue(text.contains("fail"),
                    "failed-payment text must signal failure (" + role + ")");
            assertTrue(email.html().contains("ORD-77"), "failed-payment html carries the order number");
        }
    }

    @Test
    @DisplayName("WR-02 — payment.succeeded still renders the 'received / thank you' success copy")
    void paymentSucceededStillRendersSuccessCopy() {
        Map<String, Object> model = Map.of(
                "orderNumber", "ORD-88", "amount", "£42.50", "paymentType", "SUCCEEDED");

        RenderedEmail customer = renderer.render("payment.succeeded", RecipientRole.CUSTOMER, model, UNSUB);
        assertTrue(customer.html().toLowerCase().contains("received"),
                "successful-payment customer html confirms receipt");
        assertTrue(customer.html().contains("Thank you"),
                "successful-payment customer html thanks the customer");

        RenderedEmail vendor = renderer.render("payment.succeeded", RecipientRole.VENDOR, model, UNSUB);
        assertTrue(vendor.html().toLowerCase().contains("received"),
                "successful-payment vendor html confirms receipt");
    }

    @Test
    @DisplayName("WR-03 — onboarding email renders the stall reason (not a blank shopName)")
    void onboardingRendersReason() {
        String reason = "Manual review required: business verification pending";
        RenderedEmail email = renderer.render(
                "onboarding.state.changed", RecipientRole.VENDOR,
                Map.of("reason", reason), UNSUB);

        assertFalse(email.html().isBlank(), "onboarding html must not be blank");
        assertTrue(email.html().contains(reason),
                "onboarding html must state WHY onboarding stalled (the reason)");
        assertTrue(email.text().contains(reason),
                "onboarding text must state the reason too");
    }

    @Test
    @DisplayName("WR-03/IN-02 — the rendered reason is HTML-escaped in the html path, raw in the text path")
    void onboardingReasonIsHtmlEscaped() {
        String reason = "Blocked by <script>alert(1)</script> & manual review";
        RenderedEmail email = renderer.render(
                "onboarding.state.changed", RecipientRole.VENDOR,
                Map.of("reason", reason), UNSUB);

        assertFalse(email.html().contains("<script>"),
                "raw <script> must never reach the html body (injection guard)");
        assertTrue(email.html().contains("&lt;script&gt;"),
                "the reason must be HTML-escaped in the html path");
        assertTrue(email.text().contains(reason),
                "the plain-text path stays unescaped (no markup context)");
    }

    // --- issue #279: escaping is the DEFAULT for every HTML-context value ------

    @Test
    @DisplayName("#279 — orderNumber is HTML-escaped in the html body, raw in text and subject")
    void orderNumberIsEscapedInHtmlPath() {
        for (String eventType : new String[]{"order.confirmed", "payment.succeeded",
                "payment.failed", "refund.processed"}) {
            for (RecipientRole role : RecipientRole.values()) {
                RenderedEmail email = renderer.render(
                        eventType, role, Map.of("orderNumber", XSS, "amount", "£1.00"), UNSUB);

                String where = eventType + "/" + role;
                assertFalse(email.html().contains("<script>"),
                        "raw <script> must never reach the html body — " + where);
                assertTrue(email.html().contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
                        "orderNumber must be HTML-escaped in the html body — " + where);
                assertTrue(email.text().contains(XSS),
                        "the plain-text path stays raw (no markup context) — " + where);
                assertTrue(email.subject().contains(XSS),
                        "the MIME subject stays raw (a header, not HTML) — " + where);
            }
        }
    }

    @Test
    @DisplayName("#279 — amount is HTML-escaped in the html body, raw in text")
    void amountIsEscapedInHtmlPath() {
        for (String eventType : new String[]{"payment.succeeded", "payment.failed", "refund.processed"}) {
            for (RecipientRole role : RecipientRole.values()) {
                RenderedEmail email = renderer.render(
                        eventType, role, Map.of("orderNumber", "ORD-1", "amount", ATTR_BREAKOUT), UNSUB);

                String where = eventType + "/" + role;
                assertFalse(email.html().contains("<img src=x"),
                        "raw <img> must never reach the html body — " + where);
                assertTrue(email.html().contains("&quot;&gt;&lt;img src=x onerror=alert(1)&gt;"),
                        "amount must be HTML-escaped in the html body — " + where);
                assertTrue(email.text().contains(ATTR_BREAKOUT),
                        "the plain-text path stays raw — " + where);
            }
        }
    }

    @Test
    @DisplayName("#279 — the unsubscribe href is escaped for ATTRIBUTE context (no quote breakout)")
    void unsubscribeUrlIsEscapedInHrefAttribute() {
        String hostile = "https://evil.test/u?x=1\" onmouseover=\"alert(1)";
        RenderedEmail email = renderer.render(
                "order.confirmed", RecipientRole.CUSTOMER, Map.of("orderNumber", "ORD-3"), hostile);

        assertFalse(email.html().contains("onmouseover=\"alert(1)\""),
                "an unescaped href must not let a value close the attribute and add a handler");
        assertTrue(email.html().contains("&quot; onmouseover=&quot;alert(1)"),
                "the unsubscribe URL must be HTML-escaped inside the href attribute");
        assertTrue(email.text().contains(hostile),
                "the plain-text footer keeps the raw URL (and so does the List-Unsubscribe header)");
    }

    @Test
    @DisplayName("#279 — a real multi-parameter unsubscribe URL still round-trips to the same link")
    void unsubscribeUrlRoundTripsThroughEscaping() {
        String real = "https://jtoye.uk/api/v1/public/unsubscribe"
                + "?tenant=0f7c6f4e-2a1c-4b6a-9d3e-8f1b2c3d4e5f"
                + "&email=vendor%40shop.co.uk&category=ORDERS&token=abc.def";
        RenderedEmail email = renderer.render(
                "order.confirmed", RecipientRole.VENDOR, Map.of("orderNumber", "ORD-4"), real);

        Matcher m = HREF.matcher(email.html());
        assertTrue(m.find(), "the branded footer must carry exactly one <a href=\"...\">");
        assertEquals(real, HtmlUtils.htmlUnescape(m.group(1)),
                "escaping must not corrupt the link — unescaping the href returns the original URL");
    }

    @Test
    @DisplayName("#279 — the template's OWN markup is not double-escaped (legitimate-markup path)")
    void templateMarkupIsNotDoubleEscaped() {
        RenderedEmail email = renderer.render(
                "refund.processed", RecipientRole.CUSTOMER,
                Map.of("orderNumber", "ORD-5", "amount", "£12.50"), UNSUB);

        String html = email.html();
        // The per-event bodyHtml block is deliberately markup and must survive intact.
        assertTrue(html.contains("<p style=\"margin:0;font-size:15px;line-height:1.6;\">"),
                "the body block must stay real markup, not escaped text");
        assertTrue(html.contains("<strong>ORD-5</strong>"),
                "the emphasis around the order number must stay real markup");
        // The branded wrapper is markup too.
        assertTrue(html.contains("<!DOCTYPE html>"), "the wrapper doctype must stay markup");
        assertTrue(html.contains("<table role=\"presentation\""), "the wrapper table must stay markup");
        assertFalse(html.contains("&lt;p "), "the body block must not be escaped into text");
        assertFalse(html.contains("&lt;strong&gt;"), "the emphasis must not be escaped into text");
        assertFalse(html.contains("&lt;!DOCTYPE"), "the wrapper must not be escaped into text");
    }

    @Test
    @DisplayName("#279 — an escaped value is escaped exactly ONCE (no &amp;lt; double-encoding)")
    void escapedValuesAreNotDoubleEncoded() {
        RenderedEmail email = renderer.render(
                "onboarding.state.changed", RecipientRole.VENDOR,
                Map.of("reason", "Blocked by <script> & review"), UNSUB);

        assertTrue(email.html().contains("&lt;script&gt; &amp; review"),
                "the reason must be escaped once");
        assertFalse(email.html().contains("&amp;lt;"),
                "double-encoding would show literal '&lt;' text to the vendor");
    }

    @Test
    @DisplayName("#279 — UTF-8 escaping leaves £ and — literal (money must not render as &pound;)")
    void nonAsciiSurvivesEscaping() {
        RenderedEmail email = renderer.render(
                "refund.processed", RecipientRole.CUSTOMER,
                Map.of("orderNumber", "ORD-6", "amount", "£12.50"), UNSUB);

        assertTrue(email.html().contains("£12.50"),
                "the email is UTF-8 end-to-end — the pound sign must stay literal");
        assertFalse(email.html().contains("&pound;"),
                "escaping must not convert money into ISO-8859-1 entity references");
    }

    @Test
    @DisplayName("render — a null unsubscribe URL never produces a broken template")
    void nullUnsubscribeIsSafe() {
        RenderedEmail email = assertDoesNotThrow(() ->
                renderer.render("order.ready", RecipientRole.CUSTOMER, Map.of("orderNumber", "ORD-2"), null));
        assertFalse(email.html().isBlank());
        assertFalse(email.text().isBlank());
    }
}
