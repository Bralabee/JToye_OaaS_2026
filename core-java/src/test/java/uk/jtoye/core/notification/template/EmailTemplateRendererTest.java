package uk.jtoye.core.notification.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmailTemplateRenderer} — the {subject, html, text} seam
 * (D-01). Verifies branded HTML + plain-text alternative per event family, the
 * unsubscribe footer link, and both-audience (customer/vendor) rendering.
 */
class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    private static final String UNSUB = "https://jtoye.uk/api/v1/public/unsubscribe?token=abc123";

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
    @DisplayName("render — a null unsubscribe URL never produces a broken template")
    void nullUnsubscribeIsSafe() {
        RenderedEmail email = assertDoesNotThrow(() ->
                renderer.render("order.ready", RecipientRole.CUSTOMER, Map.of("orderNumber", "ORD-2"), null));
        assertFalse(email.html().isBlank());
        assertFalse(email.text().isBlank());
    }
}
