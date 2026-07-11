package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.order.dto.OrderDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #179 defect 2: the kitchen display calls {@code GET /orders?shopId=...}
 * but the endpoint used to ignore the parameter entirely, so every shop's
 * orders were returned regardless of the selected shop. This locks the fix in
 * {@link OrderController#getAllOrders}:
 *
 * <ul>
 *   <li>{@code ?shopId=<uuid>} present → delegates to the shop-scoped
 *       {@code OrderService.getOrdersByShop} (RLS still scopes the tenant;
 *       a foreign tenant's shopId yields an empty page, not a leak)</li>
 *   <li>no {@code shopId} → unchanged behaviour, all tenant orders via
 *       {@code OrderService.getAllOrders}</li>
 *   <li>malformed {@code shopId} → 400, no service call</li>
 * </ul>
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class OrderControllerShopFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderSseService sseService;

    private static final UUID SHOP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private Page<OrderDto> singlePage(Pageable pageable) {
        OrderDto dto = new OrderDto();
        dto.setId(UUID.randomUUID());
        dto.setOrderNumber("ORD-KDS-0001");
        dto.setStatus(OrderStatus.CONFIRMED);
        dto.setShopId(SHOP_ID);
        return new PageImpl<>(List.of(dto), pageable, 1);
    }

    @Test
    @DisplayName("GET /orders?shopId=... delegates to the shop-scoped query")
    void getAllOrders_withShopId_filtersByShop() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByShop(eq(SHOP_ID), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/orders")
                        .param("shopId", SHOP_ID.toString())
                        .param("size", "100")
                        .param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-KDS-0001"))
                .andExpect(jsonPath("$.content[0].shopId").value(SHOP_ID.toString()));

        verify(orderService).getOrdersByShop(eq(SHOP_ID), pageableCaptor.capture());
        verify(orderService, never()).getAllOrders(any(Pageable.class));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /orders without shopId keeps the unfiltered tenant-wide behaviour")
    void getAllOrders_withoutShopId_returnsAllTenantOrders() throws Exception {
        when(orderService.getAllOrders(any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(0)));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(orderService).getAllOrders(any(Pageable.class));
        verify(orderService, never()).getOrdersByShop(any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /orders?shopId=not-a-uuid is rejected with 400 before any service call")
    void getAllOrders_malformedShopId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("shopId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(orderService, never()).getAllOrders(any(Pageable.class));
        verify(orderService, never()).getOrdersByShop(any(UUID.class), any(Pageable.class));
    }
}
