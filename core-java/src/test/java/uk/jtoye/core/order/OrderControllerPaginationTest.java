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
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.order.dto.OrderDto;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #95 [P2-4]: the formerly-unbounded order list endpoints
 * ({@code /orders/status/{s}}, {@code /orders/shop/{id}},
 * {@code /orders/customer/{id}}) must paginate, and the documented
 * "max 100" page size must be enforced server-side.
 *
 * <p>Enforcement lives in ONE shared place —
 * {@code spring.data.web.pageable.max-page-size: 100} in application.yml,
 * applied by Spring's {@code PageableHandlerMethodArgumentResolver} to every
 * {@code Pageable}-resolved endpoint. The contract is CLAMP (not 400):
 * OpenApiConfig documents "size (default: 20, max: 100)" as a bound, not a
 * rejection contract, and pre-existing dashboard callers send {@code size=200}
 * expecting a successful response.
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class OrderControllerPaginationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private OrderSseService sseService;

    private static final UUID SHOP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private OrderDto buildOrderDto() {
        OrderDto dto = new OrderDto();
        dto.setId(UUID.randomUUID());
        dto.setOrderNumber("ORD-TEST-0001");
        dto.setStatus(OrderStatus.PENDING);
        return dto;
    }

    private Page<OrderDto> singlePage(Pageable pageable) {
        return new PageImpl<>(List.of(buildOrderDto()), pageable, 1);
    }

    @Test
    @DisplayName("GET /orders/status/{s} without params uses documented defaults: size 20, createdAt DESC")
    void getOrdersByStatus_defaultsToSize20SortedByCreatedAtDesc() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByStatus(eq(OrderStatus.PENDING), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/orders/status/PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-TEST-0001"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(orderService).getOrdersByStatus(eq(OrderStatus.PENDING), pageableCaptor.capture());
        Pageable resolved = pageableCaptor.getValue();
        assertThat(resolved.getPageSize()).isEqualTo(20);
        assertThat(resolved.getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET /orders/status/{s}?size=200 is clamped to the global max of 100")
    void getOrdersByStatus_size200_clampedTo100() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByStatus(eq(OrderStatus.PENDING), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/orders/status/PENDING").param("size", "200"))
                .andExpect(status().isOk());

        verify(orderService).getOrdersByStatus(eq(OrderStatus.PENDING), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /orders/shop/{id}?size=200 is clamped to the global max of 100")
    void getOrdersByShop_size200_clampedTo100() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByShop(eq(SHOP_ID), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/orders/shop/{shopId}", SHOP_ID).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        verify(orderService).getOrdersByShop(eq(SHOP_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /orders/customer/{id}?size=200 is clamped to the global max of 100")
    void getOrdersByCustomer_size200_clampedTo100() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByCustomer(eq(CUSTOMER_ID), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/orders/customer/{customerId}", CUSTOMER_ID).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        verify(orderService).getOrdersByCustomer(eq(CUSTOMER_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /orders?size=200 (pre-existing paginated endpoint) is clamped to 100 too")
    void getAllOrders_size200_clampedTo100() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getAllOrders(any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(0)));

        mockMvc.perform(get("/api/v1/orders").param("size", "200"))
                .andExpect(status().isOk());

        verify(orderService).getAllOrders(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Explicit page/size within the cap are honoured, not clamped")
    void getOrdersByStatus_size50_honoured() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(orderService.getOrdersByStatus(eq(OrderStatus.COMPLETED), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<OrderDto>(List.of(), inv.getArgument(1), 0));

        mockMvc.perform(get("/api/v1/orders/status/COMPLETED")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk());

        verify(orderService).getOrdersByStatus(eq(OrderStatus.COMPLETED), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(2);
    }
}
