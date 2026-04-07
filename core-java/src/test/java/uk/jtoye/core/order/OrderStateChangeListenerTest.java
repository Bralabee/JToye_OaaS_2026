package uk.jtoye.core.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.jtoye.core.config.BusinessMetricsService;
import uk.jtoye.core.notification.EmailNotificationService;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStateChangeListenerTest {

    private OrderStateChangeListener listener;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private EmailNotificationService emailService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Session hibernateSession;

    @Mock
    private BusinessMetricsService metrics;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        java.sql.Connection mockConn = mock(java.sql.Connection.class);
        java.sql.PreparedStatement mockStmt = mock(java.sql.PreparedStatement.class);
        lenient().when(mockConn.prepareStatement(any(String.class))).thenReturn(mockStmt);
        lenient().doAnswer(inv -> { inv.<org.hibernate.jdbc.Work>getArgument(0).execute(mockConn); return null; }).when(hibernateSession).doWork(any());
        listener = new OrderStateChangeListener(new OrderSseService(), orderRepository, emailService, entityManager, metrics);
        listenerLogger = (Logger) LoggerFactory.getLogger(OrderStateChangeListener.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        listenerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        listenerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("Should log state change for any transition")
    void handleOrderStateChange_logsTransition() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-TEST-20260401-ABC",
                OrderStatus.DRAFT, OrderStatus.PENDING, OffsetDateTime.now()
        );

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("ORD-TEST-20260401-ABC")
                        && e.getFormattedMessage().contains("DRAFT")
                        && e.getFormattedMessage().contains("PENDING"));
    }

    @Test
    @DisplayName("Should handle COMPLETED status and send email")
    void handleOrderStateChange_completed() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-20260401-CMP",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setCustomerEmail("test@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("COMPLETED"));

        verify(emailService).sendOrderCompletedNotification(event, "test@example.com");
    }

    @Test
    @DisplayName("Should handle CANCELLED status and send email")
    void handleOrderStateChange_cancelled() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-20260401-CAN",
                OrderStatus.PENDING, OrderStatus.CANCELLED, OffsetDateTime.now()
        );

        Order order = new Order();
        order.setCustomerEmail("cancel@example.com");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("CANCELLED"));

        verify(emailService).sendOrderCancelledNotification(event, "cancel@example.com");
    }

    @Test
    @DisplayName("Should handle missing order gracefully")
    void handleOrderStateChange_orderNotFound() {
        UUID orderId = UUID.randomUUID();
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                orderId, UUID.randomUUID(), "ORD-TEST-MISSING",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        listener.handleOrderStateChange(event);

        verifyNoInteractions(emailService);
    }
}
