package uk.jtoye.core.storefront;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SW-JRD-12 (swarm epic #964): the guest order lookup takes the email in the request body, so the
 * address never appears in a URL. Same service call, same 404 for a mismatch, and the answer is
 * {@code no-store} because it carries the customer's details.
 */
@WebMvcTest(PublicStorefrontController.class)
@Import(uk.jtoye.core.security.ClientIpResolver.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicOrderLookupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicStorefrontService storefrontService;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private uk.jtoye.core.security.CustomerJwtVerifier customerJwtVerifier;

    @Test
    void lookupWithEmailInTheBodyReturnsTheOrderUncached() throws Exception {
        PublicOrderStatus order = new PublicOrderStatus();
        order.setOrderNumber("ORD-2026-0001");
        order.setStatus("PREPARING");
        when(storefrontService.trackOrder("ORD-2026-0001", "alice@example.com")).thenReturn(order);

        mockMvc.perform(post("/public/orders/ORD-2026-0001/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("ORD-2026-0001"))
                .andExpect(jsonPath("$.status").value("PREPARING"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(storefrontService).trackOrder("ORD-2026-0001", "alice@example.com");
    }

    @Test
    void theVersionedAliasServesTheSameLookup() throws Exception {
        when(storefrontService.trackOrder("ORD-1", "a@b.co")).thenReturn(new PublicOrderStatus());

        mockMvc.perform(post("/api/v1/public/orders/ORD-1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.co\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aMismatchIsTheSame404AsTheQueryStringLookup() throws Exception {
        doThrow(new ResourceNotFoundException("Order not found. Check your order number and email address."))
                .when(storefrontService).trackOrder("ORD-1", "mallory@example.com");

        mockMvc.perform(post("/public/orders/ORD-1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mallory@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingOrBlankEmailIsRefusedBeforeAnyLookup() throws Exception {
        mockMvc.perform(post("/public/orders/ORD-1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/public/orders/ORD-1/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  \"}"))
                .andExpect(status().isBadRequest());

        verify(storefrontService, never()).trackOrder(anyString(), anyString());
    }

    @Test
    void anEmailInTheQueryStringIsNotReadByTheLookup() throws Exception {
        mockMvc.perform(post("/public/orders/ORD-1/lookup")
                        .param("email", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(storefrontService, never()).trackOrder(anyString(), anyString());
    }
}
