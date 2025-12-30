package uk.jtoye.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.FinancialTransactionController.CreateTransactionRequest;
import uk.jtoye.core.finance.VatRate;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FinancialTransactionControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testTenantId;

    @BeforeEach
    void setup() {
        testTenantId = UUID.randomUUID();
        String uniqueTenantName = "Test Tenant " + testTenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)",
                testTenantId, uniqueTenantName);

        // Clean up existing transactions
        jdbcTemplate.update("DELETE FROM financial_transactions");
    }

    @Test
    @WithMockUser
    void createTransactionShouldSucceed() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                10000L,  // £100.00
                VatRate.STANDARD,
                "Test payment"
        );

        mockMvc.perform(post("/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tenantId").value(testTenantId.toString()))
                .andExpect(jsonPath("$.amountPennies").value(10000))
                .andExpect(jsonPath("$.vatRate").value("STANDARD"))
                .andExpect(jsonPath("$.vatAmountPennies").value(2000))  // 20% of 10000
                .andExpect(jsonPath("$.description").value("Test payment"));
    }

    @Test
    @WithMockUser
    void listTransactionsShouldReturnPaginatedResults() throws Exception {
        // Create test transactions
        for (int i = 1; i <= 3; i++) {
            CreateTransactionRequest request = new CreateTransactionRequest(
                    (long) (i * 1000),
                    VatRate.STANDARD,
                    "Transaction " + i
            );
            mockMvc.perform(post("/financial-transactions")
                            .header("X-Tenant-ID", testTenantId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // List transactions
        mockMvc.perform(get("/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @WithMockUser
    void getTransactionByIdShouldReturnTransaction() throws Exception {
        // Create transaction
        CreateTransactionRequest request = new CreateTransactionRequest(
                5000L,
                VatRate.REDUCED,
                "Reduced VAT item"
        );

        String response = mockMvc.perform(post("/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transactionId = objectMapper.readTree(response).get("id").asText();

        // Get transaction by ID
        mockMvc.perform(get("/financial-transactions/" + transactionId)
                        .header("X-Tenant-ID", testTenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId))
                .andExpect(jsonPath("$.amountPennies").value(5000))
                .andExpect(jsonPath("$.vatRate").value("REDUCED"))
                .andExpect(jsonPath("$.vatAmountPennies").value(250));  // 5% of 5000
    }

    @Test
    @WithMockUser
    void createTransactionWithZeroVatShouldCalculateCorrectly() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                8000L,
                VatRate.ZERO,
                "Zero VAT item"
        );

        mockMvc.perform(post("/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPennies").value(8000))
                .andExpect(jsonPath("$.vatRate").value("ZERO"))
                .andExpect(jsonPath("$.vatAmountPennies").value(0));  // 0% VAT
    }

    @Test
    @WithMockUser
    void createTransactionWithoutTenantShouldFail() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                5000L,
                VatRate.STANDARD,
                "Test transaction"
        );

        // Expect 500 because TenantContext.get().orElseThrow() throws NoSuchElementException
        mockMvc.perform(post("/financial-transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser
    void createTransactionWithNullAmountShouldReturnBadRequest() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                null,  // Null amount
                VatRate.STANDARD,
                "Invalid transaction"
        );

        mockMvc.perform(post("/financial-transactions")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
