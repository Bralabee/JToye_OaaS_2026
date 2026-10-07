package uk.jtoye.core.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.onboarding.TraderEntityType;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderChannel;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.dto.OrderAllergenFlagDto;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.SentMail;

import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailNotificationService: message construction, conditional sending, error
 * handling, and (31.1-25, #789/#785/D-13) the seller-facing content and header safety.
 *
 * <p>Every assertion reads the {@link MimeMessage} AS ITS RECIPIENT RECEIVES IT: serialised to
 * RFC 5322 bytes and parsed back ({@link SentMail}). An injected header only becomes a header on
 * the way back, so a header test that read the builder object could not fail.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> messageCaptor;

    private EmailNotificationService service;

    private static final String FROM_ADDRESS = "noreply@jtoye.uk";
    private static final String TRACKING_BASE_URL = "https://shop.jtoye.uk";
    private static final String RECIPIENT = "customer@example.com";
    private static final String SHOP_NAME = "Mama Adé's Kitchen";
    private static final String SHOP_EMAIL = "kitchen@x.example.com";

    private static final SellerIdentityDto SELLER = new SellerIdentityDto(
            "Mama Ade Foods Ltd", TraderEntityType.COMPANY, "09876543", "GB123456789",
            List.of("12 Market Street", "Birmingham", "B1 1AA"), SHOP_EMAIL, "0121 496 0000");

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", FROM_ADDRESS);
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        ReflectionTestUtils.setField(service, "trackingBaseUrl", TRACKING_BASE_URL);
        ReflectionTestUtils.setField(service, "platformLegalName", "J'Toye Digital Ltd");
        ReflectionTestUtils.setField(service, "platformCompanyNumber", "16471464");
        ReflectionTestUtils.setField(service, "platformRegistration", "England & Wales");
        ReflectionTestUtils.setField(service, "platformRegisteredOffice", "");
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
    }

    /** The message as its recipient receives it. */
    private record Sent(MimeMessage parsed) {
        String getText() { return SentMail.text(parsed); }
        String getSubject() { return SentMail.subject(parsed); }
        String getFrom() { return SentMail.from(parsed).getAddress(); }
        String getFromPersonal() { return SentMail.from(parsed).getPersonal(); }
        String[] getTo() { return SentMail.recipients(parsed, Message.RecipientType.TO).toArray(String[]::new); }
        List<String> getReplyTo() { return SentMail.replyTo(parsed); }
    }

    private Sent sent() {
        verify(mailSender).send(messageCaptor.capture());
        return new Sent(SentMail.reparse(messageCaptor.getValue()));
    }

    private static CustomerEmailContext ctx(FulfilmentType type) {
        return CustomerEmailContext.unknownShop(type);
    }

    /** The persona fixture: a storefront order the customer acknowledged, with one advisory flag. */
    private static CustomerEmailContext full(String shopName, String shopEmail) {
        return new CustomerEmailContext(shopName, shopEmail, SELLER, OrderChannel.STOREFRONT,
                List.of("Gluten", "Milk"), List.of("Gluten", "Milk"),
                List.of(new OrderAllergenFlagDto("Puff Puff", 2, "Eggs")), FulfilmentType.COLLECTION);
    }

    private OrderStateChangeEvent createTestEvent(String orderNumber,
                                                   OrderStatus previous,
                                                   OrderStatus next) {
        return new OrderStateChangeEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderNumber,
                previous,
                next,
                OffsetDateTime.now()
        );
    }

    private OrderStateChangeEvent createTestEvent() {
        return createTestEvent("ORD-100", OrderStatus.PENDING, OrderStatus.CONFIRMED);
    }

    // --- Order confirmation ---

    @Test
    @DisplayName("sendOrderConfirmation - Sends email with correct subject and tracking link")
    void testSendOrderConfirmation() {
        OrderStateChangeEvent event = createTestEvent();

        service.sendOrderConfirmation(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertEquals(FROM_ADDRESS, msg.getFrom());
        assertArrayEquals(new String[]{RECIPIENT}, msg.getTo());
        assertEquals("Order ORD-100 \u2014 Received", msg.getSubject());
        assertTrue(msg.getText().contains("ORD-100"));
        assertTrue(msg.getText().contains(TRACKING_BASE_URL + "/track?order=ORD-100"));
    }

    // --- Order confirmed ---

    @Test
    @DisplayName("sendOrderConfirmed - Subject contains Confirmed and body has tracking link")
    void testSendOrderConfirmed() {
        OrderStateChangeEvent event = createTestEvent();

        service.sendOrderConfirmed(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertEquals("Order ORD-100 \u2014 Confirmed", msg.getSubject());
        assertTrue(msg.getText().contains("has been confirmed"));
        assertTrue(msg.getText().contains("/track?order=ORD-100"));
    }

    // --- Order preparing ---

    @Test
    @DisplayName("sendOrderPreparing - Subject contains Being Prepared")
    void testSendOrderPreparing() {
        OrderStateChangeEvent event = createTestEvent("ORD-200",
                OrderStatus.CONFIRMED, OrderStatus.PREPARING);

        service.sendOrderPreparing(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertEquals("Order ORD-200 \u2014 Being Prepared", msg.getSubject());
        assertTrue(msg.getText().contains("now being prepared"));
    }

    // --- Order ready ---

    @Test
    @DisplayName("sendOrderReady - COLLECTION keeps the existing subject and collection copy")
    void testSendOrderReadyCollection() {
        OrderStateChangeEvent event = createTestEvent("ORD-300",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, ctx(FulfilmentType.COLLECTION));

        Sent msg = sent();

        assertEquals("Order ORD-300 \u2014 Ready!", msg.getSubject());
        assertTrue(msg.getText().contains("ready for collection"));
    }

    @Test
    @DisplayName("sendOrderReady - DELIVERY says it will be delivered and never says collect (#502)")
    void testSendOrderReadyDelivery() {
        OrderStateChangeEvent event = createTestEvent("ORD-301",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, ctx(FulfilmentType.DELIVERY));

        Sent msg = sent();

        assertEquals("Order ORD-301 \u2014 Ready!", msg.getSubject());
        assertTrue(msg.getText().contains("deliver it to the address on your order"));
        assertFalse(msg.getText().toLowerCase().contains("collect"),
                "a DELIVERY customer must never be told to collect (#502)");
        assertTrue(msg.getText().contains("/track?order=ORD-301"));
    }

    /**
     * {@code orders.fulfilment_type} is {@code NOT NULL DEFAULT 'DELIVERY'} (V45),
     * so null is unreachable through the schema \u2014 but if it ever were, the copy
     * must not fall back to "come and collect", which is the actively harmful
     * answer when the fulfilment mode is unknown.
     *
     * <p>COR-1 note (2026-09-02): since both order-creation paths now set the fulfilment
     * type explicitly ({@code FulfilmentPolicy}), the column default is a history device
     * only. This arm asserts a safety property on an unreachable input; that is its value,
     * and it is recorded here so nobody deletes it as "dead".
     */
    @Test
    @DisplayName("sendOrderReady - null fulfilment type falls back to DELIVERY copy (#502)")
    void testSendOrderReadyNullFulfilmentType() {
        OrderStateChangeEvent event = createTestEvent("ORD-302",
                OrderStatus.PREPARING, OrderStatus.READY);

        service.sendOrderReady(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertFalse(msg.getText().toLowerCase().contains("collect"));
        assertTrue(msg.getText().contains("will deliver it to the address on your order"));
    }

    // --- Order completed ---

    @Test
    @DisplayName("sendOrderCompletedNotification - Subject contains Completed")
    void testSendOrderCompleted() {
        OrderStateChangeEvent event = createTestEvent("ORD-400",
                OrderStatus.READY, OrderStatus.COMPLETED);

        service.sendOrderCompletedNotification(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertEquals("Order ORD-400 \u2014 Completed", msg.getSubject());
        assertTrue(msg.getText().contains("has been completed"));
    }

    // --- Order cancelled ---

    @Test
    @DisplayName("sendOrderCancelledNotification - Subject contains Cancelled and body shows previous status")
    void testSendOrderCancelled() {
        OrderStateChangeEvent event = createTestEvent("ORD-500",
                OrderStatus.PREPARING, OrderStatus.CANCELLED);

        service.sendOrderCancelledNotification(event, RECIPIENT, ctx(null));

        Sent msg = sent();

        assertEquals("Order ORD-500 \u2014 Cancelled", msg.getSubject());
        assertTrue(msg.getText().contains("has been cancelled"));
        assertTrue(msg.getText().contains("PREPARING"));
    }

    // --- Conditional sending ---

    @Test
    @DisplayName("sendNotification - Skips sending when emailEnabled is false")
    void testSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "emailEnabled", false);

        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, ctx(null));

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sendNotification - Skips sending when recipient email is null")
    void testSkipsWhenEmailNull() {
        service.sendOrderConfirmation(createTestEvent(), null, ctx(null));

        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("sendNotification - Skips sending when recipient email is blank")
    void testSkipsWhenEmailBlank() {
        service.sendOrderConfirmation(createTestEvent(), "   ", ctx(null));

        verifyNoInteractions(mailSender);
    }

    // --- Error handling ---

    @Test
    @DisplayName("send - Handles MailException gracefully without propagating")
    void testHandlesMailException() {
        doThrow(new MailSendException("SMTP down"))
                .when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() ->
                service.sendOrderConfirmation(createTestEvent(), RECIPIENT, ctx(null)));

        verify(mailSender).send(any(MimeMessage.class));
    }

    // =============================================================================================
    // 31.1-25 (#789, #785, D-08, D-13): the shop is the seller, safely
    // =============================================================================================

    @Test
    @DisplayName("D-13: From '{shop} via J'Toye' at the platform address, Reply-To the shop email")
    void fromShopViaPlatformWithReplyTo() {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));

        Sent msg = sent();
        assertThat(msg.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(msg.getFromPersonal()).isEqualTo(SHOP_NAME + " via J'Toye");
        assertThat(msg.getReplyTo()).containsExactly(SHOP_EMAIL);
    }

    @Test
    @DisplayName("PGC-789 encoding: a non-ASCII shop name travels RFC 2047-encoded and decodes exactly")
    void nonAsciiShopNameIsEncodedAndDecodesExactly() {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));

        verify(mailSender).send(messageCaptor.capture());
        String raw = SentMail.raw(messageCaptor.getValue());
        String fromLine = raw.lines().filter(l -> l.startsWith("From:")).findFirst().orElseThrow();
        assertThat(fromLine)
                .as("the wire form is an RFC 2047 encoded-word, never raw 8-bit bytes in a header")
                .contains("=?UTF-8?")
                .doesNotContain("é");
        assertThat(SentMail.from(SentMail.reparse(messageCaptor.getValue())).getPersonal())
                .isEqualTo("Mama Adé's Kitchen via J'Toye");
    }

    @Test
    @DisplayName("T-31.1-85: a shop name with CR/LF + a header produces NO extra header; display is one line")
    void crlfInShopNameCannotInjectAHeader() throws Exception {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full("X\r\nBcc: a@b", SHOP_EMAIL));

        Sent msg = sent();
        assertThat(SentMail.header(msg.parsed(), "Bcc")).as("no Bcc header was injected").isNull();
        assertThat(msg.parsed().getRecipients(Message.RecipientType.BCC)).isNull();
        assertThat(SentMail.recipients(msg.parsed(), Message.RecipientType.TO)).containsExactly(RECIPIENT);
        assertThat(msg.getFromPersonal()).isEqualTo("X Bcc: a@b via J'Toye");
        assertThat(msg.getText()).as("the body names the shop on one line too").doesNotContain("X\r\nBcc");
    }

    @Test
    @DisplayName("T-31.1-85: other control characters (NUL, TAB, NEL, U+2028) are stripped from the display name")
    void otherControlCharactersAreStripped() {
        assertThat(EmailNotificationService.sanitise("A\u0000B\tC\u0085D\u2028E\nF")).isEqualTo("A B C D E F");
        assertThat(EmailNotificationService.sanitise(" \r\n ")).isNull();
        assertThat(EmailNotificationService.displayName(full("  \r\n", null))).isEqualTo("J'Toye");
    }

    @Test
    @DisplayName("D-13: a shop with no email gets no Reply-To header at all")
    void noShopEmailMeansNoReplyTo() {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full(SHOP_NAME, null));

        Sent msg = sent();
        assertThat(SentMail.header(msg.parsed(), "Reply-To")).isNull();
    }

    @Test
    @DisplayName("T-31.1-85: a shop email that is not exactly one valid address is dropped, never sent")
    void invalidShopEmailIsDropped() {
        for (String bad : List.of("a@b.example, c@d.example", "kitchen@x.example.com\r\nBcc: evil@x.example", "not an address")) {
            reset(mailSender);
            lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());

            service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full(SHOP_NAME, bad));

            Sent msg = sent();
            assertThat(SentMail.header(msg.parsed(), "Reply-To")).as("Reply-To for %s", bad).isNull();
            assertThat(SentMail.header(msg.parsed(), "Bcc")).as("Bcc for %s", bad).isNull();
        }
    }

    @Test
    @DisplayName("#789: the order-received body names the seller, the platform, the cancellation position "
            + "and the D-08 allergen record, with the flag as a separate advisory line")
    void orderReceivedBodyCarriesTheSellerAndTheRecord() {
        service.sendOrderConfirmation(createTestEvent("ORD-789", OrderStatus.DRAFT, OrderStatus.PENDING),
                RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));

        String body = sent().getText();
        assertThat(body)
                .contains("Your order ORD-789 has been sent to " + SHOP_NAME + ".")
                .contains("Seller: Mama Ade Foods Ltd (Registered company)\n"
                        + "Company number: 09876543\n"
                        + "VAT number: GB123456789\n"
                        + "Address: 12 Market Street, Birmingham, B1 1AA\n"
                        + "Email: " + SHOP_EMAIL + "\n"
                        + "Phone: 0121 496 0000\n")
                .contains(EmailNotificationService.PLATFORM_NOT_SELLER_COPY)
                .contains(EmailNotificationService.CANCELLATION_STATEMENT_COPY)
                .contains("You confirmed you had read: Gluten, Milk\n")
                .contains("Recorded on your order: Gluten, Milk\n")
                .contains("Check — Puff Puff: the ingredients list mentions Eggs, which the kitchen has not declared");
        assertThat(body.indexOf("Recorded on your order"))
                .as("the advisory flag is its own line after the declared set, never merged into it")
                .isLessThan(body.indexOf("Check — Puff Puff"));
        assertThat(body).doesNotContain("Recorded on your order: Gluten, Milk, Eggs");
    }

    @Test
    @DisplayName("D-07: a vendor-placed order says so and claims no customer acknowledgement")
    void vendorPlacedOrderSaysNoConfirmationWasRecorded() {
        CustomerEmailContext vendorPlaced = new CustomerEmailContext(SHOP_NAME, SHOP_EMAIL, SELLER,
                OrderChannel.VENDOR, null, List.of("Gluten"), List.of(), FulfilmentType.COLLECTION);

        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, vendorPlaced);

        String body = sent().getText();
        assertThat(body)
                .contains(EmailNotificationService.VENDOR_PLACED_COPY)
                .contains("Recorded on your order: Gluten")
                .doesNotContain(EmailNotificationService.RECORDED_ACK_HEADING_COPY);
    }

    @Test
    @DisplayName("D-08: null is never 'none' — not-recorded, declared-none and acknowledged-none read differently")
    void notRecordedIsNotNone() {
        CustomerEmailContext notRecorded = new CustomerEmailContext(SHOP_NAME, SHOP_EMAIL, SELLER,
                null, null, null, List.of(), FulfilmentType.COLLECTION);
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, notRecorded);
        String body = sent().getText();
        assertThat(body)
                .contains(EmailNotificationService.RECORDED_ACK_NOT_RECORDED_COPY)
                .contains("Recorded on your order: " + EmailNotificationService.ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY)
                .doesNotContain(EmailNotificationService.ALLERGEN_PANEL_EMPTY_HEADING_COPY);

        reset(mailSender);
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
        CustomerEmailContext declaredNone = new CustomerEmailContext(SHOP_NAME, SHOP_EMAIL, SELLER,
                OrderChannel.STOREFRONT, List.of(), List.of(), List.of(), FulfilmentType.COLLECTION);
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, declaredNone);
        String none = sent().getText();
        assertThat(none)
                .contains(EmailNotificationService.RECORDED_ACK_NONE_COPY)
                .contains("Recorded on your order: " + EmailNotificationService.ALLERGEN_PANEL_EMPTY_HEADING_COPY);
    }

    @Test
    @DisplayName("31.1-24 missing state: no seller on file says so and points at J'Toye's /legal page")
    void noSellerSaysSoAndNamesThePlatform() {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT,
                new CustomerEmailContext(SHOP_NAME, SHOP_EMAIL, null, OrderChannel.STOREFRONT,
                        List.of(), List.of(), List.of(), FulfilmentType.COLLECTION));

        String body = sent().getText();
        assertThat(body)
                .contains(EmailNotificationService.SELLER_DETAILS_MISSING_COPY)
                .contains(EmailNotificationService.PLATFORM_CONTACT_INTRO_COPY + " "
                        + EmailNotificationService.PLATFORM_CONTACT_LINK_COPY + ": " + TRACKING_BASE_URL + "/legal")
                .as("'the seller named here' would name nobody")
                .doesNotContain(EmailNotificationService.PLATFORM_NOT_SELLER_COPY)
                .doesNotContain("Seller:");
    }

    @Test
    @DisplayName("D-14: the registered office line is omitted while unset, and printed once set")
    void registeredOfficeOmittedUntilSet() {
        service.sendOrderConfirmed(createTestEvent(), RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));
        String unset = sent().getText();
        assertThat(unset)
                .contains("J'Toye Digital Ltd, registered in England & Wales, company number 16471464.")
                .doesNotContain("Registered office");

        reset(mailSender);
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
        ReflectionTestUtils.setField(service, "platformRegisteredOffice", "1 Fixture Lane, Testtown, TT1 1TT");
        service.sendOrderConfirmed(createTestEvent(), RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));
        assertThat(sent().getText()).contains("Registered office: 1 Fixture Lane, Testtown, TT1 1TT\n");
    }

    @Test
    @DisplayName("a vendor-supplied value cannot forge a line of the seller block")
    void sellerValuesAreSingleLines() {
        SellerIdentityDto forged = new SellerIdentityDto("Real Ltd\nVAT number: GB999999999",
                TraderEntityType.SOLE_TRADER, null, null, List.of("1 Road"), SHOP_EMAIL, null);
        service.sendOrderConfirmed(createTestEvent(), RECIPIENT, new CustomerEmailContext(SHOP_NAME, SHOP_EMAIL,
                forged, OrderChannel.STOREFRONT, List.of(), List.of(), List.of(), FulfilmentType.COLLECTION));

        String body = sent().getText();
        assertThat(body).contains("Seller: Real Ltd VAT number: GB999999999 (Sole trader)\n");
        assertThat(body.lines().filter(l -> l.startsWith("VAT number:")).toList()).isEmpty();
    }

    /** One status method, as a call. */
    private record Status(String name, String subjectSuffix, boolean received,
                          BiConsumer<EmailNotificationService, CustomerEmailContext> send) {
    }

    private static final OrderStateChangeEvent STATUS_EVENT = new OrderStateChangeEvent(
            UUID.randomUUID(), UUID.randomUUID(), "ORD-ALL", OrderStatus.PREPARING, OrderStatus.CANCELLED,
            OffsetDateTime.now());

    private static final List<Status> STATUSES = List.of(
            new Status("received", "Received", true, (s, c) -> s.sendOrderConfirmation(STATUS_EVENT, RECIPIENT, c)),
            new Status("confirmed", "Confirmed", false, (s, c) -> s.sendOrderConfirmed(STATUS_EVENT, RECIPIENT, c)),
            new Status("preparing", "Being Prepared", false, (s, c) -> s.sendOrderPreparing(STATUS_EVENT, RECIPIENT, c)),
            new Status("ready", "Ready!", false, (s, c) -> s.sendOrderReady(STATUS_EVENT, RECIPIENT, c)),
            new Status("completed", "Completed", false,
                    (s, c) -> s.sendOrderCompletedNotification(STATUS_EVENT, RECIPIENT, c)),
            new Status("cancelled", "Cancelled", false,
                    (s, c) -> s.sendOrderCancelledNotification(STATUS_EVENT, RECIPIENT, c)));

    @Test
    @DisplayName("every status sends ONE message from the shop with the seller block and platform statement; "
            + "only 'received' carries the cancellation statement and the allergen record")
    void everyStatusNamesTheSellerOnce() {
        for (Status status : STATUSES) {
            reset(mailSender);
            lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());

            status.send().accept(service, full(SHOP_NAME, SHOP_EMAIL));

            verify(mailSender, times(1)).send(messageCaptor.capture());
            verify(mailSender, times(1)).createMimeMessage();
            verifyNoMoreInteractions(mailSender);
            Sent msg = new Sent(SentMail.reparse(messageCaptor.getValue()));
            String body = msg.getText();
            assertThat(msg.getSubject()).as(status.name()).isEqualTo("Order ORD-ALL — " + status.subjectSuffix());
            assertThat(msg.getFromPersonal()).as(status.name()).isEqualTo(SHOP_NAME + " via J'Toye");
            assertThat(msg.getReplyTo()).as(status.name()).containsExactly(SHOP_EMAIL);
            assertThat(body).as(status.name())
                    .contains(SHOP_NAME)
                    .contains(EmailNotificationService.SELLER_BLOCK_HEADING_COPY)
                    .contains("Seller: Mama Ade Foods Ltd")
                    .contains(EmailNotificationService.PLATFORM_NOT_SELLER_COPY)
                    .contains("This email was sent by J'Toye on behalf of " + SHOP_NAME + ".")
                    .doesNotContain("— J'Toye")
                    .doesNotContain("We'll ")
                    .doesNotContain("we'll ");
            if (status.received()) {
                assertThat(body).as(status.name())
                        .contains(EmailNotificationService.CANCELLATION_STATEMENT_COPY)
                        .contains(EmailNotificationService.RECORDED_ALLERGEN_SET_TITLE_COPY);
            } else {
                assertThat(body).as(status.name())
                        .doesNotContain(EmailNotificationService.CANCELLATION_STATEMENT_COPY)
                        .doesNotContain(EmailNotificationService.RECORDED_ALLERGEN_SET_TITLE_COPY)
                        .doesNotContain(EmailNotificationService.RECORDED_SET_HEADING_COPY);
            }
        }
    }

    // --- T-31.1-88 / ASVS V7: the recipient never reaches a log line ---------------------------------

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;
    private Level previousLevel;

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            serviceLogger.detachAppender(appender);
            serviceLogger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("T-31.1-88: no log line (sent, skipped, failed, Reply-To dropped) contains the recipient")
    void noLogLineContainsTheRecipient() {
        serviceLogger = (Logger) LoggerFactory.getLogger(EmailNotificationService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        for (Status status : STATUSES) {
            status.send().accept(service, full(SHOP_NAME, SHOP_EMAIL));
        }
        service.sendOrderConfirmation(STATUS_EVENT, RECIPIENT, full(SHOP_NAME, "not an address"));
        doThrow(new MailSendException("Invalid Addresses: " + RECIPIENT)).when(mailSender).send(any(MimeMessage.class));
        service.sendOrderConfirmation(STATUS_EVENT, RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));
        ReflectionTestUtils.setField(service, "emailEnabled", false);
        service.sendOrderConfirmation(STATUS_EVENT, RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines)
                .as("non-vacuity: the sent, failed and skipped paths all logged, naming the order")
                .anyMatch(l -> l.startsWith("Email notification sent for order ORD-ALL (order-received)"))
                .anyMatch(l -> l.startsWith("Failed to send email for order ORD-ALL"))
                .anyMatch(l -> l.startsWith("Email notification skipped for order ORD-ALL"));
        assertThat(lines).noneMatch(l -> l.contains(RECIPIENT));
        assertThat(lines).noneMatch(l -> l.contains("customer@"));
    }

    // --- One source of words: the Java copy equals the storefront's --------------------------------

    private static String tsConstant(String source, String name) {
        Matcher m = Pattern.compile("export const " + name + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
                Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), "constant " + name + " not found in the storefront source");
        return m.group(1).replace("\\\"", "\"");
    }

    @Test
    @DisplayName("parity: every mirrored sentence equals the storefront constant it quotes (no drift, no paraphrase)")
    void wordingMatchesTheStorefront() throws Exception {
        String sellerBlock = Files.readString(
                IntegrationTestSupport.locateRepoFile("frontend/components/storefront/seller-block.tsx"));
        String recorded = Files.readString(
                IntegrationTestSupport.locateRepoFile("frontend/components/storefront/recorded-allergen-set.tsx"));
        String panel = Files.readString(
                IntegrationTestSupport.locateRepoFile("frontend/components/storefront/order-allergen-panel.tsx"));

        assertEquals(tsConstant(sellerBlock, "SELLER_BLOCK_HEADING_COPY"), EmailNotificationService.SELLER_BLOCK_HEADING_COPY);
        assertEquals(tsConstant(sellerBlock, "PLATFORM_NOT_SELLER_COPY"), EmailNotificationService.PLATFORM_NOT_SELLER_COPY);
        assertEquals(tsConstant(sellerBlock, "CANCELLATION_STATEMENT_COPY"), EmailNotificationService.CANCELLATION_STATEMENT_COPY);
        assertEquals(tsConstant(sellerBlock, "SELLER_DETAILS_MISSING_COPY"), EmailNotificationService.SELLER_DETAILS_MISSING_COPY);
        assertEquals(tsConstant(sellerBlock, "PLATFORM_CONTACT_INTRO_COPY"), EmailNotificationService.PLATFORM_CONTACT_INTRO_COPY);
        assertEquals(tsConstant(sellerBlock, "PLATFORM_CONTACT_LINK_COPY"), EmailNotificationService.PLATFORM_CONTACT_LINK_COPY);
        for (TraderEntityType type : TraderEntityType.values()) {
            assertTrue(sellerBlock.contains(type.name() + ": \""
                            + EmailNotificationService.SELLER_ENTITY_TYPE_LABELS.get(type) + "\""),
                    "entity label for " + type);
        }

        assertEquals(tsConstant(recorded, "RECORDED_ALLERGEN_SET_TITLE_COPY"), EmailNotificationService.RECORDED_ALLERGEN_SET_TITLE_COPY);
        assertEquals(tsConstant(recorded, "RECORDED_ACK_HEADING_COPY"), EmailNotificationService.RECORDED_ACK_HEADING_COPY);
        assertEquals(tsConstant(recorded, "RECORDED_SET_HEADING_COPY"), EmailNotificationService.RECORDED_SET_HEADING_COPY);
        assertEquals(tsConstant(recorded, "RECORDED_ACK_NOT_RECORDED_COPY"), EmailNotificationService.RECORDED_ACK_NOT_RECORDED_COPY);
        assertEquals(tsConstant(recorded, "RECORDED_ACK_NONE_COPY"), EmailNotificationService.RECORDED_ACK_NONE_COPY);
        assertEquals(tsConstant(recorded, "VENDOR_PLACED_COPY"), EmailNotificationService.VENDOR_PLACED_COPY);

        assertEquals(tsConstant(panel, "ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY"), EmailNotificationService.ALLERGEN_PANEL_NOT_RECORDED_HEADING_COPY);
        assertEquals(tsConstant(panel, "ALLERGEN_PANEL_EMPTY_HEADING_COPY"), EmailNotificationService.ALLERGEN_PANEL_EMPTY_HEADING_COPY);
        String flagTemplate = EmailNotificationService.allergenFlagCopy(
                new OrderAllergenFlagDto("${flag.productName}", 0, "${flag.allergenName}"));
        assertTrue(panel.contains("`" + flagTemplate + "`"), "allergenFlagCopy template drifted: " + flagTemplate);
    }

    @Test
    @DisplayName("InternetAddress used for From is the platform's own sender address, never the shop's")
    void fromAddressIsNeverTheShops() throws Exception {
        service.sendOrderConfirmation(createTestEvent(), RECIPIENT, full(SHOP_NAME, SHOP_EMAIL));
        InternetAddress from = SentMail.from(sent().parsed());
        assertThat(from.getAddress()).isEqualTo(FROM_ADDRESS).isNotEqualTo(SHOP_EMAIL);
    }
}
