package uk.jtoye.core.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStateChangeListenerTest {

    private OrderStateChangeListener listener;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger listenerLogger;

    @BeforeEach
    void setUp() {
        listener = new OrderStateChangeListener(new OrderSseService());
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
    @DisplayName("Should handle COMPLETED status with completion log")
    void handleOrderStateChange_completed() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-TEST-20260401-CMP",
                OrderStatus.READY, OrderStatus.COMPLETED, OffsetDateTime.now()
        );

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("completed"));
    }

    @Test
    @DisplayName("Should handle CANCELLED status with cancellation log")
    void handleOrderStateChange_cancelled() {
        OrderStateChangeEvent event = new OrderStateChangeEvent(
                UUID.randomUUID(), UUID.randomUUID(), "ORD-TEST-20260401-CAN",
                OrderStatus.PENDING, OrderStatus.CANCELLED, OffsetDateTime.now()
        );

        listener.handleOrderStateChange(event);

        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("cancelled"));
    }
}
