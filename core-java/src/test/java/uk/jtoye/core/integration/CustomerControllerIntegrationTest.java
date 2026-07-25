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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.UpdateCustomerRequest;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
// Phase 25 [CR-01]: all customer mutations (create/update/delete) are gated on
// SCOPE_customers:write; the dashboard customers page token carries it by default.
// A single class-level write-scoped principal restores each test's original intent
// (create/read/update/delete succeed). The @Valid-400 cases still 400 because bean
// validation runs BEFORE the @PreAuthorize gate (D-04 ordering), so the scope is inert there.
@WithMockUser(authorities = "SCOPE_customers:write")
class CustomerControllerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
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

        // Clean up existing customers
        jdbcTemplate.update("DELETE FROM customers");
    }

    @Test
    void createCustomerShouldSucceed() throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest(
                "John Doe",
                "john.doe@example.com",
                "+1234567890",
                5
        );

        mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.tenantId").value(testTenantId.toString()))
                .andExpect(jsonPath("$.name").value("John Doe"))
                .andExpect(jsonPath("$.email").value("john.doe@example.com"))
                .andExpect(jsonPath("$.phone").value("+1234567890"))
                .andExpect(jsonPath("$.allergenRestrictions").value(5));
    }

    @Test
    void listCustomersShouldReturnPaginatedResults() throws Exception {
        // Create test customers
        for (int i = 1; i <= 3; i++) {
            CreateCustomerRequest request = new CreateCustomerRequest(
                    "Customer " + i,
                    "customer" + i + "@example.com",
                    "+123456789" + i,
                    0
            );
            mockMvc.perform(post("/api/v1/customers")
                            .header("X-Tenant-ID", testTenantId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }

        // List customers
        mockMvc.perform(get("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getCustomerByIdShouldReturnCustomer() throws Exception {
        // Create customer
        CreateCustomerRequest request = new CreateCustomerRequest(
                "Jane Doe",
                "jane.doe@example.com",
                "+9876543210",
                3
        );

        String response = mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String customerId = objectMapper.readTree(response).get("id").asText();

        // Get customer by ID
        mockMvc.perform(get("/api/v1/customers/" + customerId)
                        .header("X-Tenant-ID", testTenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.email").value("jane.doe@example.com"));
    }

    @Test
    void updateCustomerShouldSucceed() throws Exception {
        // Create customer
        CreateCustomerRequest createRequest = new CreateCustomerRequest(
                "Original Name",
                "original@example.com",
                "+1111111111",
                0
        );

        String response = mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String customerId = objectMapper.readTree(response).get("id").asText();

        // Update customer
        UpdateCustomerRequest updateRequest = new UpdateCustomerRequest(
                "Updated Name",
                "updated@example.com",
                "+2222222222",
                7
        );

        mockMvc.perform(put("/api/v1/customers/" + customerId)
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.email").value("updated@example.com"))
                .andExpect(jsonPath("$.phone").value("+2222222222"))
                .andExpect(jsonPath("$.allergenRestrictions").value(7));
    }

    @Test
    void deleteCustomerShouldSucceed() throws Exception {
        // Create customer
        CreateCustomerRequest request = new CreateCustomerRequest(
                "To Delete",
                "delete@example.com",
                "+9999999999",
                0
        );

        String response = mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String customerId = objectMapper.readTree(response).get("id").asText();

        // Delete customer
        mockMvc.perform(delete("/api/v1/customers/" + customerId)
                        .header("X-Tenant-ID", testTenantId.toString()))
                .andExpect(status().isNoContent());

        // Verify customer is deleted
        mockMvc.perform(get("/api/v1/customers/" + customerId)
                        .header("X-Tenant-ID", testTenantId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createCustomerWithInvalidEmailShouldReturnBadRequest() throws Exception {
        CreateCustomerRequest request = new CreateCustomerRequest(
                "John Doe",
                "invalid-email",  // Invalid email format
                "+1234567890",
                0
        );

        mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCustomerWithOverLengthNameShouldReturn400NotConflict() throws Exception {
        // QA BE-2 regression: an over-length name (> the varchar(255) column) must fail
        // bean validation with a 400 — NOT reach the DB and surface as the misleading
        // 409 "Duplicate Entry" the blanket DataIntegrityViolation→409 mapping produced.
        CreateCustomerRequest request = new CreateCustomerRequest(
                "a".repeat(256),
                "overlength@example.com",
                "+1234567890",
                0
        );

        mockMvc.perform(post("/api/v1/customers")
                        .header("X-Tenant-ID", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
