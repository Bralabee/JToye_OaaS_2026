package uk.jtoye.core.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.ResourceNotFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GdprController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class GdprControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GdprService gdprService;

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("GET /api/v1/gdpr/customers/{id}/export returns 200 with full data export")
    void exportData_returnsDataExportResponse() throws Exception {
        var customerExport = new GdprController.CustomerExport(
                CUSTOMER_ID, "Jane Doe", "jane@example.com", "+447700900000",
                5, "Prefers extra sauce",
                OffsetDateTime.parse("2025-01-15T10:00:00Z"),
                OffsetDateTime.parse("2025-06-01T14:30:00Z")
        );
        var orderExport = new GdprController.OrderExport(
                UUID.randomUUID(), "ORD-001", "COMPLETED",
                "Jane Doe", "jane@example.com",
                1500L, 300L, 200L, 2000L,
                "CARD", "No onions",
                OffsetDateTime.parse("2025-05-20T12:00:00Z")
        );
        var reviewExport = new GdprController.ReviewExport(
                UUID.randomUUID(), 5, 4, "Great food!",
                OffsetDateTime.parse("2025-05-21T09:00:00Z")
        );
        var response = new GdprController.DataExportResponse(
                CUSTOMER_ID,
                OffsetDateTime.parse("2025-06-15T08:00:00Z"),
                customerExport,
                List.of(orderExport),
                List.of(reviewExport)
        );

        when(gdprService.exportCustomerData(CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/gdpr/customers/{id}/export", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.customer.name").value("Jane Doe"))
                .andExpect(jsonPath("$.customer.email").value("jane@example.com"))
                .andExpect(jsonPath("$.orders").isArray())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].orderNumber").value("ORD-001"))
                .andExpect(jsonPath("$.reviews").isArray())
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].foodRating").value(5));
    }

    @Test
    @DisplayName("DELETE /api/v1/gdpr/customers/{id}/erase returns 200 with erasure confirmation")
    void eraseData_returnsErasureResponse() throws Exception {
        UUID recordId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var response = new GdprController.ErasureResponse(
                CUSTOMER_ID,
                OffsetDateTime.parse("2025-06-15T08:00:00Z"),
                3, 2, 5, 4, recordId
        );

        when(gdprService.eraseCustomerData(CUSTOMER_ID)).thenReturn(response);

        mockMvc.perform(delete("/api/v1/gdpr/customers/{id}/erase", CUSTOMER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.ordersAnonymised").value(3))
                .andExpect(jsonPath("$.reviewsAnonymised").value(2))
                .andExpect(jsonPath("$.auditRowsScrubbed").value(5))
                .andExpect(jsonPath("$.photosDeleted").value(4))
                .andExpect(jsonPath("$.recordId").value(recordId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/gdpr/customers/{id}/export returns 404 when customer not found")
    void exportData_returns404WhenNotFound() throws Exception {
        when(gdprService.exportCustomerData(CUSTOMER_ID))
                .thenThrow(new ResourceNotFoundException("Customer not found: " + CUSTOMER_ID));

        mockMvc.perform(get("/api/v1/gdpr/customers/{id}/export", CUSTOMER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/gdpr/customers/{id}/erase returns 404 when customer not found")
    void eraseData_returns404WhenNotFound() throws Exception {
        when(gdprService.eraseCustomerData(CUSTOMER_ID))
                .thenThrow(new ResourceNotFoundException("Customer not found: " + CUSTOMER_ID));

        mockMvc.perform(delete("/api/v1/gdpr/customers/{id}/erase", CUSTOMER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Tenant isolation: export receives exact customerId from path variable")
    void exportData_passesExactCustomerIdToService() throws Exception {
        var response = new GdprController.DataExportResponse(
                CUSTOMER_ID,
                OffsetDateTime.now(),
                new GdprController.CustomerExport(CUSTOMER_ID, "Test", "t@t.com", null, 0, null, null, null),
                List.of(),
                List.of()
        );
        when(gdprService.exportCustomerData(any(UUID.class))).thenReturn(response);

        mockMvc.perform(get("/api/v1/gdpr/customers/{id}/export", CUSTOMER_ID))
                .andExpect(status().isOk());

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(gdprService).exportCustomerData(captor.capture());
        assertEquals(CUSTOMER_ID, captor.getValue(),
                "Service must receive the exact customer ID from the path variable");
    }
}
