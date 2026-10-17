package uk.jtoye.core.shop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.CreateFulfilmentSlotRequest;
import uk.jtoye.core.shop.dto.FulfilmentSlotDto;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopFulfilmentSlotServiceTest {

    @Mock private ShopFulfilmentSlotRepository slotRepository;
    @Mock private ShopRepository shopRepository;
    @Mock private ShopAccessService shopAccessService;

    private Clock fixedClock;
    private ShopFulfilmentSlotService service;
    private UUID tenantId;
    private UUID shopId;
    private Shop shop;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);
        service = new ShopFulfilmentSlotService(slotRepository, shopRepository, shopAccessService, fixedClock);
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        TenantContext.set(tenantId);

        shop = new Shop();
        shop.setId(shopId);
        shop.setTenantId(tenantId);
        shop.setName("Lagos Kitchen");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("createSlot requires SHOP_MANAGER and persists slot with zero reserved counters")
    void createSlot_validRequest_persistsSlot() {
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));
        when(slotRepository.save(any(ShopFulfilmentSlot.class))).thenAnswer(inv -> {
            ShopFulfilmentSlot saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        CreateFulfilmentSlotRequest req = new CreateFulfilmentSlotRequest();
        req.setServiceDate(LocalDate.of(2026, 10, 24));
        req.setWindowStart(LocalTime.of(14, 0));
        req.setWindowEnd(LocalTime.of(18, 0));
        req.setCutoffAt(OffsetDateTime.parse("2026-10-22T22:00:00Z"));
        req.setMaxOrders(40);
        req.setMaxUnits(80);

        FulfilmentSlotDto dto = service.createSlot(shopId, req);

        verify(shopAccessService).require(shopId, ShopRole.SHOP_MANAGER);
        assertNotNull(dto.id());
        assertEquals(LocalDate.of(2026, 10, 24), dto.serviceDate());
        assertEquals("2026-10-24 14:00-18:00", dto.windowLabel());
        assertEquals(40, dto.maxOrders());
        assertEquals(40, dto.remainingOrders());
        assertEquals(80, dto.maxUnits());
        assertEquals(80, dto.remainingUnits());
        assertTrue(dto.active());
    }

    @Test
    @DisplayName("createSlot rejects windowEnd <= windowStart with 400 IllegalArgumentException")
    void createSlot_invalidWindow_rejected() {
        when(shopRepository.findByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));

        CreateFulfilmentSlotRequest req = new CreateFulfilmentSlotRequest();
        req.setServiceDate(LocalDate.of(2026, 10, 24));
        req.setWindowStart(LocalTime.of(18, 0));
        req.setWindowEnd(LocalTime.of(14, 0));
        req.setCutoffAt(OffsetDateTime.parse("2026-10-22T22:00:00Z"));

        assertThrows(IllegalArgumentException.class, () -> service.createSlot(shopId, req));
        verify(slotRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserveSlotForOrder atomically reserves capacity and snapshots schedule onto Order")
    void reserveSlotForOrder_available_stampsOrder() {
        UUID slotId = UUID.randomUUID();
        ShopFulfilmentSlot slot = new ShopFulfilmentSlot();
        slot.setId(slotId);
        slot.setTenantId(tenantId);
        slot.setShopId(shopId);
        slot.setServiceDate(LocalDate.of(2026, 10, 24));
        slot.setWindowStart(LocalTime.of(14, 0));
        slot.setWindowEnd(LocalTime.of(18, 0));
        slot.setCutoffAt(OffsetDateTime.parse("2026-10-22T22:00:00Z"));
        slot.setMaxOrders(40);
        slot.setMaxUnits(80);
        slot.setActive(true);

        when(slotRepository.findByIdAndShopId(slotId, shopId)).thenReturn(Optional.of(slot));
        when(slotRepository.tryReserveCapacity(eq(slotId), eq(shopId), eq(3), any(OffsetDateTime.class)))
                .thenReturn(1);

        Order order = new Order();
        order.setTenantId(tenantId);
        order.setShopId(shopId);
        order.setUnitCount(3);

        service.reserveSlotForOrder(shop, slotId, order);

        assertEquals(slotId, order.getFulfilmentSlotId());
        assertEquals(LocalDate.of(2026, 10, 24), order.getScheduledForDate());
        assertEquals("2026-10-24 14:00-18:00", order.getScheduledWindowLabel());
    }

    @Test
    @DisplayName("reserveSlotForOrder rejects past-cutoff slot with 409 Conflict before touching capacity")
    void reserveSlotForOrder_pastCutoff_throws409() {
        UUID slotId = UUID.randomUUID();
        ShopFulfilmentSlot slot = new ShopFulfilmentSlot();
        slot.setId(slotId);
        slot.setTenantId(tenantId);
        slot.setShopId(shopId);
        slot.setServiceDate(LocalDate.of(2026, 10, 24));
        slot.setWindowStart(LocalTime.of(14, 0));
        slot.setWindowEnd(LocalTime.of(18, 0));
        // Cutoff is 2 hours BEFORE fixedClock (2026-10-20T12:00:00Z)
        slot.setCutoffAt(OffsetDateTime.parse("2026-10-20T10:00:00Z"));
        slot.setActive(true);

        when(slotRepository.findByIdAndShopId(slotId, shopId)).thenReturn(Optional.of(slot));

        Order order = new Order();
        order.setUnitCount(2);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reserveSlotForOrder(shop, slotId, order));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(slotRepository, never()).tryReserveCapacity(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("reserveSlotForOrder rejects exhausted capacity with 409 Conflict when atomic UPDATE affects 0 rows")
    void reserveSlotForOrder_capacityExhausted_throws409() {
        UUID slotId = UUID.randomUUID();
        ShopFulfilmentSlot slot = new ShopFulfilmentSlot();
        slot.setId(slotId);
        slot.setTenantId(tenantId);
        slot.setShopId(shopId);
        slot.setServiceDate(LocalDate.of(2026, 10, 24));
        slot.setWindowStart(LocalTime.of(14, 0));
        slot.setWindowEnd(LocalTime.of(18, 0));
        slot.setCutoffAt(OffsetDateTime.parse("2026-10-22T22:00:00Z"));
        slot.setMaxOrders(40);
        slot.setReservedOrders(40);
        slot.setActive(true);

        when(slotRepository.findByIdAndShopId(slotId, shopId)).thenReturn(Optional.of(slot));
        when(slotRepository.tryReserveCapacity(eq(slotId), eq(shopId), eq(2), any(OffsetDateTime.class)))
                .thenReturn(0);

        Order order = new Order();
        order.setUnitCount(2);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.reserveSlotForOrder(shop, slotId, order));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertNull(order.getFulfilmentSlotId());
    }

    @Test
    @DisplayName("releaseSlotForOrder decrements reserved capacity when order has a fulfilmentSlotId")
    void releaseSlotForOrder_withSlot_releasesCapacity() {
        UUID slotId = UUID.randomUUID();
        Order order = new Order();
        order.setTenantId(tenantId);
        order.setFulfilmentSlotId(slotId);
        order.setUnitCount(4);

        service.releaseSlotForOrder(order);

        verify(slotRepository).releaseCapacity(eq(slotId), eq(tenantId), eq(4), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("listAvailablePublicSlots filters out full slots")
    void listAvailablePublicSlots_filtersOutFullSlots() {
        ShopFulfilmentSlot openSlot = new ShopFulfilmentSlot();
        openSlot.setId(UUID.randomUUID());
        openSlot.setShopId(shopId);
        openSlot.setServiceDate(LocalDate.of(2026, 10, 24));
        openSlot.setWindowStart(LocalTime.of(14, 0));
        openSlot.setWindowEnd(LocalTime.of(18, 0));
        openSlot.setCutoffAt(OffsetDateTime.parse("2026-10-22T22:00:00Z"));
        openSlot.setMaxOrders(10);
        openSlot.setReservedOrders(9);
        openSlot.setActive(true);

        ShopFulfilmentSlot fullSlot = new ShopFulfilmentSlot();
        fullSlot.setId(UUID.randomUUID());
        fullSlot.setShopId(shopId);
        fullSlot.setServiceDate(LocalDate.of(2026, 10, 25));
        fullSlot.setWindowStart(LocalTime.of(14, 0));
        fullSlot.setWindowEnd(LocalTime.of(18, 0));
        fullSlot.setCutoffAt(OffsetDateTime.parse("2026-10-23T22:00:00Z"));
        fullSlot.setMaxOrders(10);
        fullSlot.setReservedOrders(10);
        fullSlot.setActive(true);

        when(slotRepository.findByShopIdAndActiveTrueAndCutoffAtAfterOrderByServiceDateAscWindowStartAsc(
                eq(shopId), any(OffsetDateTime.class)))
                .thenReturn(List.of(openSlot, fullSlot));

        List<FulfilmentSlotDto> available = service.listAvailablePublicSlots(shopId);
        assertEquals(1, available.size());
        assertEquals(openSlot.getId(), available.get(0).id());
    }
}
