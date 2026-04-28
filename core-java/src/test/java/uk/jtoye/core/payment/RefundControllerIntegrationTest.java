package uk.jtoye.core.payment;

import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.payment.dto.CreateRefundRequest;
import uk.jtoye.core.payment.dto.RefundDto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 17 VOPS-02 — RefundController integration test.
 *
 * <p>Uses {@code @WebMvcTest} (no full Spring context) plus
 * {@code @Import(GlobalExceptionHandler.class)} so the StripeException → 502
 * mapping is exercised end-to-end. {@link RefundService} is mocked via
 * {@link MockitoBean}.
 *
 * <p>Mirrors {@link PaymentControllerTest}'s pattern for security-bypass
 * ({@code addFilters = false}) — JWT validation is exercised by
 * {@code SecurityConfig} integration tests, not here.
 */
@WebMvcTest(RefundController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class RefundControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RefundService refundService;

    private UUID orderId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        orderId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
    }

    private RefundDto stubRefundDto(UUID id, RefundStatus status) {
        return new RefundDto(
                id,
                tenantId,
                orderId,
                "re_test_xyz",
                "idem-key-1",
                500L,
                "gbp",
                RefundReason.REQUESTED_BY_CUSTOMER,
                "customer requested",
                status,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    // ------------------------------------------------------------------
    // POST /api/v1/orders/{id}/refund — happy path + Idempotency-Key
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST refund with valid body returns 201 + Location + RefundDto")
    void postRefund_validBody_returns201WithRefundDtoAndLocationHeader() throws Exception {
        UUID refundId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        RefundDto stub = stubRefundDto(refundId, RefundStatus.succeeded);
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), eq(idempotencyKey)))
                .thenReturn(stub);

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER","note":"customer requested"}
                """;

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + orderId + "/refunds/" + refundId))
                .andExpect(jsonPath("$.id").value(refundId.toString()))
                .andExpect(jsonPath("$.stripeRefundId").value("re_test_xyz"))
                .andExpect(jsonPath("$.status").value("succeeded"));

        verify(refundService, times(1)).createRefund(eq(orderId), any(CreateRefundRequest.class), eq(idempotencyKey));
    }

    @Test
    @DisplayName("POST refund without Idempotency-Key still works (header optional)")
    void postRefund_noIdempotencyKey_returns201() throws Exception {
        UUID refundId = UUID.randomUUID();
        RefundDto stub = stubRefundDto(refundId, RefundStatus.succeeded);
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), isNull()))
                .thenReturn(stub);

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER"}
                """;

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(refundId.toString()));

        verify(refundService, times(1)).createRefund(eq(orderId), any(CreateRefundRequest.class), isNull());
    }

    @Test
    @DisplayName("Two POSTs with the same Idempotency-Key return same refund id; service called twice (replay handled inside service)")
    void postRefund_sameIdempotencyKeyTwice_returnsSameRefundId() throws Exception {
        UUID refundId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        RefundDto stub = stubRefundDto(refundId, RefundStatus.succeeded);
        // RefundService.createRefund handles the replay short-circuit internally
        // (stored-first idempotency); both POSTs return the same DTO. The
        // controller invokes the service twice but Stripe is only hit once —
        // RefundServiceTest already verifies that contract.
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), eq(idempotencyKey)))
                .thenReturn(stub);

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER"}
                """;

        // First POST
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(refundId.toString()));

        // Second POST — same body, same key → same id observable
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(refundId.toString()));

        verify(refundService, times(2))
                .createRefund(eq(orderId), any(CreateRefundRequest.class), eq(idempotencyKey));
    }

    // ------------------------------------------------------------------
    // POST /api/v1/orders/{id}/refund — validation + state errors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("amountPennies > order total returns 400 ProblemDetail (invalid-argument)")
    void postRefund_amountExceedsTotal_returns400ProblemDetail() throws Exception {
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), any()))
                .thenThrow(new IllegalArgumentException(
                        "amountPennies 20000 exceeds remaining refundable 10000"));

        String body = """
                {"amountPennies":20000,"reason":"REQUESTED_BY_CUSTOMER"}
                """;

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-argument"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("exceeds remaining refundable")));
    }

    @Test
    @DisplayName("Refund on DRAFT order returns 400 (InvalidStateTransition)")
    void postRefund_orderInDraftStatus_returns400() throws Exception {
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), any()))
                .thenThrow(new InvalidStateTransitionException(
                        "Cannot refund order in status DRAFT"));

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER"}
                """;

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-state-transition"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("DRAFT")));
    }

    @Test
    @DisplayName("StripeException from RefundService maps to 502 ProblemDetail with stripeCode")
    void postRefund_stripeThrows_returns502ProblemDetail() throws Exception {
        // Constructor: (message, param, requestId, code, statusCode, cause)
        StripeException stripeFail = new InvalidRequestException(
                "amount too high", "amount", "req_abc", "amount_too_large", 400, null);
        when(refundService.createRefund(eq(orderId), any(CreateRefundRequest.class), any()))
                .thenThrow(stripeFail);

        String body = """
                {"amountPennies":500,"reason":"REQUESTED_BY_CUSTOMER"}
                """;

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/payment-provider"))
                .andExpect(jsonPath("$.title").value("Payment Provider Error"))
                .andExpect(jsonPath("$.stripeCode").value("amount_too_large"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("amount too high")));
    }

    // ------------------------------------------------------------------
    // GET /api/v1/orders/{id}/refunds
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/v1/orders/{id}/refunds returns list ordered as service returns it")
    void getRefunds_returnsList() throws Exception {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        RefundDto newest = stubRefundDto(r1, RefundStatus.succeeded);
        RefundDto older = stubRefundDto(r2, RefundStatus.succeeded);
        when(refundService.findByOrderId(orderId)).thenReturn(List.of(newest, older));

        mockMvc.perform(get("/api/v1/orders/" + orderId + "/refunds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(r1.toString()))
                .andExpect(jsonPath("$[1].id").value(r2.toString()));

        verify(refundService, times(1)).findByOrderId(orderId);
    }
}
