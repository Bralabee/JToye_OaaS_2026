package uk.jtoye.core.notification;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.order.CancelReason;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.testsupport.SentMail;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * PR #1124 review round 1, two findings on {@link EmailNotificationService}:
 *
 * <ul>
 *   <li>a customer's OWN cancel ({@link CancelReason#CUSTOMER_REQUEST}) must not be followed by "If this was
 *       unexpected, please contact ..." and "Call ... on ...": that reads as a contradiction of "cancelled at your
 *       request". A shop-side reason (or none recorded) keeps both lines;</li>
 *   <li>"Estimated ready by 18:45" versus "18:45 on 15 June" is decided with the injected {@code Clock}, in UK
 *       time, so the choice is testable around UK midnight instead of depending on when the test runs.</li>
 * </ul>
 *
 * Every assertion reads the message as its recipient receives it (serialised and parsed back).
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceReviewFixesTest {

    @Mock
    private JavaMailSender mailSender;

    private static final String RECIPIENT = "customer@example.com";
    private static final String SHOP = "Mama Ade's Kitchen";
    private static final String PHONE = "0121 496 0000";

    private EmailNotificationService serviceAt(Clock clock) {
        EmailNotificationService service = new EmailNotificationService(mailSender, null, null, clock);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(service, "emailEnabled", true);
        ReflectionTestUtils.setField(service, "trackingBaseUrl", "https://shop.jtoye.uk");
        ReflectionTestUtils.setField(service, "platformLegalName", "J'Toye Digital Ltd");
        ReflectionTestUtils.setField(service, "platformCompanyNumber", "16471464");
        ReflectionTestUtils.setField(service, "platformRegistration", "England & Wales");
        ReflectionTestUtils.setField(service, "platformRegisteredOffice", "");
        return service;
    }

    @BeforeEach
    void setUp() {
        lenient().when(mailSender.createMimeMessage()).thenAnswer(inv -> SentMail.newMimeMessage());
    }

    private String sentBody() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return SentMail.text(SentMail.reparse(captor.getValue()));
    }

    private static OrderStateChangeEvent event(OrderStatus previous, OrderStatus next) {
        return new OrderStateChangeEvent(UUID.randomUUID(), UUID.randomUUID(), "ORD-200", previous, next,
                OffsetDateTime.now());
    }

    private static CustomerEmailContext cancelled(CancelReason reason, String phone) {
        return new CustomerEmailContext(SHOP, null, null, null, null, null, List.of(), FulfilmentType.COLLECTION,
                null, null, reason, phone);
    }

    // ---- finding 4: the customer's own cancel ------------------------------------------------------

    @Test
    @DisplayName("a CUSTOMER_REQUEST cancel says it was at the customer's request and nothing about it being unexpected or calling the shop")
    void customerRequestHasNoUnexpectedLineAndNoCallPrompt() {
        serviceAt(Clock.systemUTC()).sendOrderCancelledNotification(
                event(OrderStatus.PENDING, OrderStatus.CANCELLED), RECIPIENT,
                cancelled(CancelReason.CUSTOMER_REQUEST, PHONE));

        String body = sentBody();
        assertThat(body).contains("This order was cancelled at your request.");
        assertThat(body).doesNotContain("If this was unexpected");
        assertThat(body).doesNotContain("Call " + SHOP);
        assertThat(body).doesNotContain(PHONE);
    }

    @Test
    @DisplayName("a shop-initiated cancel keeps 'If this was unexpected' and the call prompt with the shop's phone")
    void shopInitiatedReasonsKeepTheUnexpectedLineAndCallPrompt() {
        for (CancelReason reason : List.of(CancelReason.SUSPECTED_FAKE, CancelReason.CANNOT_FULFIL, CancelReason.OTHER)) {
            org.mockito.Mockito.clearInvocations(mailSender);
            serviceAt(Clock.systemUTC()).sendOrderCancelledNotification(
                    event(OrderStatus.CONFIRMED, OrderStatus.CANCELLED), RECIPIENT, cancelled(reason, PHONE));

            String body = sentBody();
            assertThat(body).as(reason.name()).contains(reason.customerText());
            assertThat(body).as(reason.name()).contains("If this was unexpected, please contact " + SHOP + " directly.");
            assertThat(body).as(reason.name()).contains("Call " + SHOP + " on " + PHONE + ".");
        }
    }

    @Test
    @DisplayName("no recorded reason: the long-standing wording is unchanged (unexpected line and call prompt)")
    void noReasonIsUnchanged() {
        serviceAt(Clock.systemUTC()).sendOrderCancelledNotification(
                event(OrderStatus.PENDING, OrderStatus.CANCELLED), RECIPIENT, cancelled(null, PHONE));

        String body = sentBody();
        assertThat(body).contains("If this was unexpected, please contact " + SHOP + " directly.");
        assertThat(body).contains("Call " + SHOP + " on " + PHONE + ".");
    }

    // ---- finding 7: the injected Clock decides "today" ---------------------------------------------

    private CustomerEmailContext readyAt(OffsetDateTime estimatedReadyAt) {
        return new CustomerEmailContext(SHOP, null, null, null, null, null, List.of(), FulfilmentType.COLLECTION,
                null, estimatedReadyAt, null, null);
    }

    @Test
    @DisplayName("ready-by uses the injected Clock in UK time: 23:59 UK on the 14th and a ready-by at 00:30 UK on the 15th read as different days")
    void readyByNearUkMidnightFollowsTheInjectedClock() {
        // 14 June 2030, BST: 23:59 UK = 22:59 UTC. The ready-by is 00:30 UK on the 15th = 23:30 UTC on the 14th.
        // In UTC both instants are on the 14th; in London they are on different days. A system-clock or
        // UTC-zone "today" would print "00:30" with no day; the UK date from the injected clock prints the day.
        Instant nowInstant = Instant.parse("2030-06-14T22:59:00Z");
        Clock fixedUtc = Clock.fixed(nowInstant, ZoneOffset.UTC);
        OffsetDateTime readyBy = OffsetDateTime.parse("2030-06-14T23:30:00Z");

        assertThat(serviceAt(fixedUtc).readyByParagraph(readyAt(readyBy)))
                .contains("Estimated ready by 00:30 on 15 June.");
    }

    @Test
    @DisplayName("ready-by just after UK midnight: a UTC clock still on the 14th is already the 15th in London, so a ready-by on the 15th reads as TODAY (time alone)")
    void readyByJustAfterUkMidnightIsTodayInLondon() {
        // 00:10 BST on 15 June 2030 = 23:10 UTC on the 14th. The clock is in UTC, so a zone-less LocalDate.now(clock)
        // says the 14th; London says the 15th. The system clock (2026) says neither. Only the injected clock read in
        // the UK zone prints the time alone.
        Clock fixedUtc = Clock.fixed(Instant.parse("2030-06-14T23:10:00Z"), ZoneOffset.UTC);
        OffsetDateTime readyBy = OffsetDateTime.parse("2030-06-15T10:00:00Z"); // 11:00 BST on the 15th

        String paragraph = serviceAt(fixedUtc).readyByParagraph(readyAt(readyBy));
        assertThat(paragraph).contains("Estimated ready by 11:00.").doesNotContain(" on ");
    }

    @Test
    @DisplayName("ready-by on the same UK day as the injected Clock's now prints the time alone")
    void readyByTodayPrintsTheTimeAlone() {
        Clock fixed = Clock.fixed(Instant.parse("2030-06-14T10:00:00Z"), ZoneId.of("UTC"));
        OffsetDateTime readyBy = OffsetDateTime.parse("2030-06-14T17:45:00Z"); // 18:45 UK

        String paragraph = serviceAt(fixed).readyByParagraph(readyAt(readyBy));
        assertThat(paragraph).contains("Estimated ready by 18:45.").doesNotContain(" on ");
    }

    @Test
    @DisplayName("the clock decides: the same instant is 'today' for a clock on the 14th and 'on 14 June' for a clock on the 20th")
    void sameInstantReadsDifferentlyForDifferentClocks() {
        OffsetDateTime readyBy = OffsetDateTime.parse("2030-06-14T17:45:00Z");

        assertThat(serviceAt(Clock.fixed(Instant.parse("2030-06-14T08:00:00Z"), ZoneOffset.UTC))
                .readyByParagraph(readyAt(readyBy))).contains("ready by 18:45.");
        assertThat(serviceAt(Clock.fixed(Instant.parse("2030-06-20T08:00:00Z"), ZoneOffset.UTC))
                .readyByParagraph(readyAt(readyBy))).contains("ready by 18:45 on 14 June.");
    }

    @Test
    @DisplayName("no estimate: no paragraph at all")
    void noEstimateNoParagraph() {
        assertThat(serviceAt(Clock.systemUTC()).readyByParagraph(readyAt(null))).isEmpty();
    }
}
