package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #179 defect 1: {@code GET /public/orders/mine} — the session-
 * authenticated order-history variant used by the customer "My Orders" page.
 *
 * <p>Contract locked here:
 * <ul>
 *   <li>No {@code X-Customer-Token} header → 401, order query never runs</li>
 *   <li>Invalid/unverified token (verifier throws) → 401, order query never runs</li>
 *   <li>Valid token → 200, orders listed for EXACTLY the verifier-proven email</li>
 *   <li>No email parameter exists on this surface — a client-supplied email
 *       can never influence whose orders are returned</li>
 * </ul>
 *
 * <p>The AUDIT-W0-02 enumeration protection on the sibling
 * {@code /public/orders?email=&verify=} endpoint is locked separately in
 * {@link PublicStorefrontControllerIdorTest} and is unchanged by this variant.
 */
@WebMvcTest(PublicStorefrontController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PublicStorefrontControllerMyOrdersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicStorefrontService storefrontService;

    @MockitoBean
    private ReviewService reviewService;

    @MockitoBean
    private CustomerJwtVerifier customerJwtVerifier;

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required");
    }

    @Test
    @DisplayName("GET /public/orders/mine without a token → 401, order query never invoked")
    void myOrders_withoutToken_returns401() throws Exception {
        when(customerJwtVerifier.verifiedEmail(isNull())).thenThrow(unauthorized());

        mockMvc.perform(get("/public/orders/mine"))
                .andExpect(status().isUnauthorized());

        verify(storefrontService, never()).getCustomerOrders(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /public/orders/mine with an invalid token → 401, order query never invoked")
    void myOrders_withInvalidToken_returns401() throws Exception {
        when(customerJwtVerifier.verifiedEmail("forged-token")).thenThrow(unauthorized());

        mockMvc.perform(get("/public/orders/mine").header("X-Customer-Token", "forged-token"))
                .andExpect(status().isUnauthorized());

        verify(customerJwtVerifier).verifiedEmail("forged-token");
        verify(storefrontService, never()).getCustomerOrders(anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /public/orders/mine with a valid token → 200 with the token-proven customer's orders")
    void myOrders_withValidToken_listsOwnOrders() throws Exception {
        when(customerJwtVerifier.verifiedEmail("good-token")).thenReturn("alice@example.com");

        PublicOrderStatus order = new PublicOrderStatus();
        order.setOrderNumber("ORD-2026-0042");
        order.setStatus("PENDING");
        Page<PublicOrderStatus> page = new PageImpl<>(List.of(order));
        when(storefrontService.getCustomerOrders(eq("alice@example.com"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/public/orders/mine")
                        .header("X-Customer-Token", "good-token")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderNumber").value("ORD-2026-0042"));

        verify(storefrontService).getCustomerOrders(eq("alice@example.com"), any(Pageable.class));
    }

    @Test
    @DisplayName("A client-supplied email param cannot override the token-proven identity")
    void myOrders_ignoresClientSuppliedEmailParam() throws Exception {
        when(customerJwtVerifier.verifiedEmail("good-token")).thenReturn("alice@example.com");
        when(storefrontService.getCustomerOrders(eq("alice@example.com"), any(Pageable.class)))
                .thenReturn(Page.empty());

        // Attacker holds a legitimate session but tries to read someone else's history.
        mockMvc.perform(get("/public/orders/mine")
                        .header("X-Customer-Token", "good-token")
                        .param("email", "victim@example.com"))
                .andExpect(status().isOk());

        // The lookup ran for the token's email — never for the query param.
        verify(storefrontService).getCustomerOrders(eq("alice@example.com"), any(Pageable.class));
        verify(storefrontService, never()).getCustomerOrders(eq("victim@example.com"), any(Pageable.class));
    }

    @Test
    @DisplayName("The versioned alias /api/v1/public/orders/mine serves the same handler")
    void myOrders_versionedAlias_works() throws Exception {
        when(customerJwtVerifier.verifiedEmail("good-token")).thenReturn("alice@example.com");
        when(storefrontService.getCustomerOrders(eq("alice@example.com"), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/public/orders/mine").header("X-Customer-Token", "good-token"))
                .andExpect(status().isOk());

        verify(storefrontService).getCustomerOrders(eq("alice@example.com"), any(Pageable.class));
    }
}
