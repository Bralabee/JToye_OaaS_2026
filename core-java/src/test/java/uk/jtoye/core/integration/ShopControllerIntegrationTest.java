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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.shop.dto.CreateShopRequest;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class ShopControllerIntegrationTest {

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
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", testTenantId, "Test Tenant");
    }

    @Test
    void healthEndpointShouldBePublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void listShopsWithoutAuthShouldReturn401() throws Exception {
        mockMvc.perform(get("/shops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void createShopWithoutTenantHeaderShouldReturn400() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Test Shop");
        request.setAddress("123 Test St");

        mockMvc.perform(post("/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void createShopWithValidTenantShouldSucceed() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Test Shop");
        request.setAddress("123 Test St");

        mockMvc.perform(post("/shops")
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Shop"))
                .andExpect(jsonPath("$.address").value("123 Test St"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @WithMockUser
    void listShopsShouldReturnPaginatedResults() throws Exception {
        // Create test shops
        for (int i = 1; i <= 5; i++) {
            CreateShopRequest request = new CreateShopRequest();
            request.setName("Shop " + i);
            request.setAddress("Address " + i);

            mockMvc.perform(post("/shops")
                    .header("X-Tenant-Id", testTenantId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));
        }

        // Verify pagination
        mockMvc.perform(get("/shops")
                        .header("X-Tenant-Id", testTenantId.toString())
                        .param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @WithMockUser
    void createShopWithInvalidDataShouldReturnValidationError() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        // Missing required name field

        mockMvc.perform(post("/shops")
                        .header("X-Tenant-Id", testTenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"));
    }
}
