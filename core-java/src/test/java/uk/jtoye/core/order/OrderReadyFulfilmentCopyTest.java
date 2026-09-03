package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.config.BusinessMetricsService;
import uk.jtoye.core.notification.EmailNotificationService;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #502 — a DELIVERY customer must never be told to come and collect.
 *
 * <p><b>Why this test wires the REAL {@link EmailNotificationService} into the
 * REAL {@link OrderStateChangeListener} instead of mocking the email service:</b>
 * the defect is in the rendered <em>copy</em>, not in whether a send was
 * dispatched. A test that asserts "{@code sendOrderReady} was called" is green on
 * the broken tree and on the fixed one alike — it cannot fail in the direction
 * that matters. So the only mock below the assertion is {@link JavaMailSender}
 * itself, and every assertion reads the actual {@link SimpleMailMessage} body
 * that would have gone to the SMTP sink. This is the unit-level analogue of
 * reading MailHog: it inspects the message, not the invocation.
 *
 * <p>{@code @Async} on the send methods is inert here (no Spring proxy), so the
 * send is synchronous — the same assumption {@code EmailNotificationServiceTest}
 * already relies on.
 *
 * <p>Note for #458: {@code READY} is deliberately described as "ready, on its way
 * shortly" rather than "out for delivery". There is no {@code DISPATCHED} state
 * on {@link OrderStatus} and no such edge in {@code OrderStateMachineConfig}, so
 * READY cannot truthfully claim the order has left the shop. The wording leaves
 * "out for delivery" free for the real dispatch state when #458 introduces it.
 */
@ExtendWith(MockitoExtension.class)
class OrderReadyFulfilmentCopyTest {

    private static final String RECIPIENT = "customer@example.com";
    private static final String TRACKING_BASE_URL = "https://shop.jtoye.uk";

    @Mock private OrderRepository orderRepository;
    @Mock private EntityManager entityManager;
    @Mock private Session hibernateSession;
    @Mock private BusinessMetricsService metrics;
    @Mock private SimpMessagingTemplate simpMessagingTemplate;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private JavaMailSender mailSender;

    @Captor private ArgumentCaptor<SimpleMailMessage> messageCaptor;

    private OrderStateChangeListener listener;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        java.sql.Connection mockConn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement mockStmt = mock(java.sql.PreparedStatement.class);
        lenient().when(mockConn.prepareStatement(anyString())).thenReturn(mockStmt);
        lenient().doAnswer(inv -> {
            inv.<org.hibernate.jdbc.Work>getArgument(0).execute(mockConn);
            return null;
        }).when(hibernateSession).doWork(any());
        // Fresh delivery (not a duplicate) so the side-effect pipeline runs.
        lenient().when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1);

        EmailNotificationService emailService = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(emailService, "fromAddress", "noreply@jtoye.uk");
        ReflectionTestUtils.setField(emailService, "emailEnabled", true);
        ReflectionTestUtils.setField(emailService, "trackingBaseUrl", TRACKING_BASE_URL);

        listener = new OrderStateChangeListener(
                orderRepository, emailService, entityManager, metrics, simpMessagingTemplate, jdbcTemplate);
    }

    private SimpleMailMessage readyEmailFor(FulfilmentType fulfilmentType, String orderNumber) {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), orderNumber,
                OrderStatus.PREPARING, OrderStatus.READY, OffsetDateTime.now());

        Order order = new Order();
        order.setCustomerEmail(RECIPIENT);
        order.setFulfilmentType(fulfilmentType);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        // "Exactly one email per transition" — a branch that sent both copies,
        // or sent nothing, fails here before any body assertion is reached.
        verify(mailSender, times(1)).send(messageCaptor.capture());
        return messageCaptor.getValue();
    }

    @Test
    @DisplayName("#502: a DELIVERY order reaching READY is NOT told to collect")
    void deliveryOrderReadyDoesNotSayCollection() {
        SimpleMailMessage msg = readyEmailFor(FulfilmentType.DELIVERY, "ORD-DEL-001");

        assertThat(msg.getText())
                .as("DELIVERY customer must not be instructed to collect (#502)")
                .doesNotContainIgnoringCase("collection")
                .doesNotContainIgnoringCase("collect")
                .doesNotContainIgnoringCase("pick it up");
    }

    @Test
    @DisplayName("#502: a DELIVERY order reaching READY is told it will be delivered")
    void deliveryOrderReadySaysDelivery() {
        SimpleMailMessage msg = readyEmailFor(FulfilmentType.DELIVERY, "ORD-DEL-002");

        assertThat(msg.getText())
                .as("DELIVERY customer must be told the order comes to them")
                .containsIgnoringCase("deliver");
        assertThat(msg.getSubject()).isEqualTo("Order ORD-DEL-002 — Ready!");
        assertThat(msg.getText()).contains(TRACKING_BASE_URL + "/track?order=ORD-DEL-002");
    }

    @Test
    @DisplayName("#502 regression guard: a COLLECTION order reaching READY keeps the existing copy")
    void collectionOrderReadyKeepsExistingCopy() {
        SimpleMailMessage msg = readyEmailFor(FulfilmentType.COLLECTION, "ORD-COL-001");

        assertThat(msg.getSubject()).isEqualTo("Order ORD-COL-001 — Ready!");
        assertThat(msg.getText())
                .as("behaviour that is correct today must not regress")
                .contains("ready for collection")
                .contains("Please pick it up at your earliest convenience");
        assertThat(msg.getText()).contains(TRACKING_BASE_URL + "/track?order=ORD-COL-001");
    }

    /**
     * {@code orders.fulfilment_type} is {@code NOT NULL DEFAULT 'DELIVERY'} (V45)
     * and the entity field defaults to {@link FulfilmentType#DELIVERY}, so a null
     * is not reachable through the schema — but the branch must not fall back to
     * collection copy if it ever were, because "come and collect" is the actively
     * harmful answer. Null resolves the same way the column default does.
     *
     * <p>COR-1 note (2026-09-02): the entity default is now only a HISTORY device. Both
     * writers set the value explicitly — the storefront from the customer's choice, the
     * vendor / REST / MCP path from an optional request field defaulting to COLLECTION
     * ({@code FulfilmentPolicy}). This arm therefore covers a genuinely unreachable input,
     * which is exactly why it is worth keeping: the fallback direction is a safety property,
     * not a live path, and nothing else asserts it.
     */
    @Test
    @DisplayName("#502: a null fulfilment type falls back to the DELIVERY copy, never collection")
    void nullFulfilmentTypeFallsBackToDelivery() {
        SimpleMailMessage msg = readyEmailFor(null, "ORD-NUL-001");

        assertThat(msg.getText())
                .doesNotContainIgnoringCase("collect")
                .containsIgnoringCase("deliver");
    }
}
