package uk.jtoye.core.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void webhookSuccess_returns200WithStatusOk() throws Exception {
        String payload = "{\"type\":\"payment_intent.succeeded\"}";
        String sigHeader = "t=123,v1=abc";

        doNothing().when(paymentService).handleWebhookEvent(eq(payload), eq(sigHeader));

        mockMvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", sigHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        verify(paymentService).handleWebhookEvent(eq(payload), eq(sigHeader));
    }

    @Test
    void webhookInvalidSignature_returns400WithError() throws Exception {
        String payload = "{\"type\":\"payment_intent.succeeded\"}";
        String sigHeader = "t=123,v1=invalid";

        doThrow(new IllegalArgumentException("Invalid signature"))
                .when(paymentService).handleWebhookEvent(eq(payload), eq(sigHeader));

        mockMvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", sigHeader))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid signature"));
    }

    @Test
    void webhookRejected_returns400WithErrorMessage() throws Exception {
        String payload = "{\"type\":\"charge.failed\"}";
        String sigHeader = "t=456,v1=xyz";

        doThrow(new IllegalArgumentException("Webhook rejected: unverified"))
                .when(paymentService).handleWebhookEvent(eq(payload), eq(sigHeader));

        mockMvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("Stripe-Signature", sigHeader))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Webhook rejected: unverified"));
    }

    @Test
    void webhookMissingSignatureHeader_returnsError() throws Exception {
        // Missing required @RequestHeader("Stripe-Signature") triggers MissingRequestHeaderException.
        // GlobalExceptionHandler's catch-all Exception handler returns 500 (not 400) because
        // MissingRequestHeaderException is not explicitly handled there.
        String payload = "{\"type\":\"payment_intent.succeeded\"}";

        mockMvc.perform(post("/public/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isInternalServerError());
    }
}
