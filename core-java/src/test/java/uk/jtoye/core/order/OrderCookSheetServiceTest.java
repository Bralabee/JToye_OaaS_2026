package uk.jtoye.core.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.order.dto.CookSheetDto;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCookSheetServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ShopAccessService shopAccessService;

    private OrderCookSheetService service;
    private UUID shopId;

    @BeforeEach
    void setUp() {
        service = new OrderCookSheetService(orderRepository, shopAccessService);
        shopId = UUID.randomUUID();
    }

    private static void setOrderId(Order order, UUID id) {
        try {
            Field f = Order.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(order, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("getCookSheet aggregates active orders by product, sums units, unions V63 allergens, and surfaces V73 allergy alerts")
    void getCookSheet_aggregatesItemsAndSurfacesAllergyAlerts() {
        UUID jollofId = UUID.randomUUID();
        UUID egusiId = UUID.randomUUID();

        Order o1 = new Order();
        UUID o1Id = UUID.randomUUID();
        setOrderId(o1, o1Id);
        o1.setShopId(shopId);
        o1.setOrderNumber("ORD-001");
        o1.setCustomerName("Adaeze");
        o1.setStatus(OrderStatus.CONFIRMED);
        o1.setAllergyNote("Severe peanut allergy — separate ladle please");
        OrderItem o1Jollof = new OrderItem(jollofId, 3, 2500L);
        o1Jollof.setProductName("2L Party Jollof Bowl");
        o1Jollof.setAllergenMask(256); // Celery (bit 8)
        o1.addItem(o1Jollof);

        Order o2 = new Order();
        UUID o2Id = UUID.randomUUID();
        setOrderId(o2, o2Id);
        o2.setShopId(shopId);
        o2.setOrderNumber("ORD-002");
        o2.setCustomerName("Tunde");
        o2.setStatus(OrderStatus.PREPARING);
        o2.setAllergyNote("No shellfish");
        o2.setAllergyNoteAckAt(OffsetDateTime.now());
        OrderItem o2Jollof = new OrderItem(jollofId, 2, 2500L);
        o2Jollof.setProductName("2L Party Jollof Bowl");
        o2Jollof.setAllergenMask(256);
        OrderItem o2Egusi = new OrderItem(egusiId, 1, 3000L);
        o2Egusi.setProductName("2L Egusi Soup Bowl");
        o2Egusi.setAllergenMask(2); // Crustaceans (bit 1)
        o2.addItem(o2Jollof);
        o2.addItem(o2Egusi);

        LocalDate serviceDate = LocalDate.of(2026, 10, 24);
        when(orderRepository.findCookSheetOrdersByShopAndDate(
                shopId, OrderCookSheetService.COOK_SHEET_STATUSES, serviceDate))
                .thenReturn(List.of(o1, o2));

        CookSheetDto sheet = service.getCookSheet(shopId, serviceDate, null);

        verify(shopAccessService).require(shopId, ShopRole.STAFF);
        assertEquals(shopId, sheet.shopId());
        assertEquals(serviceDate, sheet.scheduledForDate());
        assertEquals(2, sheet.totalOrders());
        assertEquals(6, sheet.totalUnits());

        assertEquals(2, sheet.items().size());
        CookSheetDto.CookSheetItemRow firstRow = sheet.items().get(0);
        assertEquals(jollofId, firstRow.productId());
        assertEquals("2L Party Jollof Bowl", firstRow.productName());
        assertEquals(5, firstRow.totalQuantity());
        assertEquals(2, firstRow.orderCount());
        assertEquals(256, firstRow.allergenMask());
        assertEquals(List.of("Celery"), firstRow.allergenNames());

        CookSheetDto.CookSheetItemRow secondRow = sheet.items().get(1);
        assertEquals(egusiId, secondRow.productId());
        assertEquals(1, secondRow.totalQuantity());
        assertEquals(1, secondRow.orderCount());
        assertEquals(2, secondRow.allergenMask());
        assertEquals(List.of("Crustaceans"), secondRow.allergenNames());

        assertEquals(2, sheet.allergyAlerts().size());
        assertEquals("ORD-001", sheet.allergyAlerts().get(0).orderNumber());
        assertFalse(sheet.allergyAlerts().get(0).acknowledged());
        assertEquals("ORD-002", sheet.allergyAlerts().get(1).orderNumber());
        assertTrue(sheet.allergyAlerts().get(1).acknowledged());
    }

    @Test
    @DisplayName("getCookSheet filters by fulfilmentSlotId when provided")
    void getCookSheet_bySlotId_queriesSlotRepositoryMethod() {
        UUID slotId = UUID.randomUUID();
        when(orderRepository.findCookSheetOrdersByShopAndSlot(
                shopId, OrderCookSheetService.COOK_SHEET_STATUSES, slotId))
                .thenReturn(List.of());

        CookSheetDto sheet = service.getCookSheet(shopId, null, slotId);

        verify(shopAccessService).require(shopId, ShopRole.STAFF);
        verify(orderRepository).findCookSheetOrdersByShopAndSlot(
                shopId, OrderCookSheetService.COOK_SHEET_STATUSES, slotId);
        assertEquals(0, sheet.totalOrders());
        assertEquals(0, sheet.totalUnits());
    }
}
