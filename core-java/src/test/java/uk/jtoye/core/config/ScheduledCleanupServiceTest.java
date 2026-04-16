package uk.jtoye.core.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledCleanupServiceTest {

    private static final UUID TEST_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock private OrderRepository orderRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query tenantQuery;
    @InjectMocks private ScheduledCleanupService cleanupService;

    @BeforeEach
    void setUp() throws Exception {
        Field f = ScheduledCleanupService.class.getDeclaredField("staleDraftHours");
        f.setAccessible(true);
        f.set(cleanupService, 24);

        // Mock tenant lookup so cleanupStaleDraftOrders iterates one test tenant
        when(entityManager.createNativeQuery("SELECT id FROM tenants")).thenReturn(tenantQuery);
        when(tenantQuery.getResultList()).thenReturn(List.of(TEST_TENANT));
    }

    @Test
    @DisplayName("Deletes stale DRAFT orders older than threshold")
    void cleanupStaleDraftOrders_deletesOldDrafts() {
        Order staleDraft = new Order();
        staleDraft.setStatus(OrderStatus.DRAFT);
        // Set createdAt to 48 hours ago via reflection
        try {
            Field f = Order.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(staleDraft, OffsetDateTime.now().minusHours(48));
        } catch (Exception e) { throw new RuntimeException(e); }

        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(staleDraft));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository).deleteAll(List.of(staleDraft));
    }

    @Test
    @DisplayName("Skips recent DRAFT orders")
    void cleanupStaleDraftOrders_skipsRecentDrafts() {
        Order recentDraft = new Order();
        recentDraft.setStatus(OrderStatus.DRAFT);
        try {
            Field f = Order.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(recentDraft, OffsetDateTime.now().minusMinutes(30));
        } catch (Exception e) { throw new RuntimeException(e); }

        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of(recentDraft));

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("No-op when no DRAFT orders exist")
    void cleanupStaleDraftOrders_noOpWhenEmpty() {
        when(orderRepository.findByStatus(OrderStatus.DRAFT)).thenReturn(List.of());

        cleanupService.cleanupStaleDraftOrders();

        verify(orderRepository, never()).deleteAll(any());
    }
}
