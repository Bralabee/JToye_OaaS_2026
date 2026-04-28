package uk.jtoye.core.storefront;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUDIT-W0-02 regression: GET /public/orders without 'verify' must 400, not leak
 * the customer's order history. Pre-fix behaviour returned 200 with the full list
 * for any email known to the system, allowing trivial enumeration of orders by email.
 *
 * <p>This locks the fix in PublicStorefrontController.getCustomerOrders so that:
 * <ul>
 *   <li>Missing {@code verify} param → 400 (Spring's MissingServletRequestParameterException)</li>
 *   <li>Blank {@code verify} param → 400 (explicit ResponseStatusException guard)</li>
 *   <li>Wrong {@code verify} → 404 (existing trackOrder ResourceNotFoundException flow)</li>
 *   <li>Valid {@code verify} → 200 with the customer's orders</li>
 * </ul>
 *
 * <p>In all rejection cases (400/404), {@code getCustomerOrders} on the service must NOT
 * be invoked — that is the proof the IDOR is closed.
 */
@WebMvcTest(PublicStorefrontController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicStorefrontControllerIdorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicStorefrontService storefrontService;

    @MockitoBean
    private ReviewService reviewService;

    @Test
    void getCustomerOrders_withoutVerify_returns400() throws Exception {
        mockMvc.perform(get("/public/orders").param("email", "victim@example.com"))
                .andExpect(status().isBadRequest());

        verify(storefrontService, never()).trackOrder(anyString(), anyString());
        verify(storefrontService, never()).getCustomerOrders(anyString());
    }

    @Test
    void getCustomerOrders_withBlankVerify_returns400() throws Exception {
        mockMvc.perform(get("/public/orders")
                        .param("email", "victim@example.com")
                        .param("verify", ""))
                .andExpect(status().isBadRequest());

        verify(storefrontService, never()).trackOrder(anyString(), anyString());
        verify(storefrontService, never()).getCustomerOrders(anyString());
    }

    @Test
    void getCustomerOrders_withWrongVerify_returns404() throws Exception {
        doThrow(new ResourceNotFoundException(
                "Order not found. Check your order number and email address."))
                .when(storefrontService).trackOrder("BAD-NUMBER", "victim@example.com");

        mockMvc.perform(get("/public/orders")
                        .param("email", "victim@example.com")
                        .param("verify", "BAD-NUMBER"))
                .andExpect(status().isNotFound());

        verify(storefrontService).trackOrder("BAD-NUMBER", "victim@example.com");
        verify(storefrontService, never()).getCustomerOrders(anyString());
    }

    @Test
    void getCustomerOrders_withValidVerify_returns200() throws Exception {
        // trackOrder doesn't throw → ownership proven. Stub returns a status object;
        // the controller ignores the return value but the mock must answer cleanly.
        PublicOrderStatus tracked = new PublicOrderStatus();
        tracked.setOrderNumber("ORD-2026-0001");
        when(storefrontService.trackOrder("ORD-2026-0001", "alice@example.com"))
                .thenReturn(tracked);
        when(storefrontService.getCustomerOrders("alice@example.com"))
                .thenReturn(List.of());

        mockMvc.perform(get("/public/orders")
                        .param("email", "alice@example.com")
                        .param("verify", "ORD-2026-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(storefrontService).trackOrder("ORD-2026-0001", "alice@example.com");
        verify(storefrontService).getCustomerOrders("alice@example.com");
    }
}
