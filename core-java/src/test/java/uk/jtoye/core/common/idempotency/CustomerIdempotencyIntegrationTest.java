package uk.jtoye.core.common.idempotency;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.customer.CustomerController;
import uk.jtoye.core.customer.CustomerController.CreateCustomerRequest;
import uk.jtoye.core.customer.CustomerController.CustomerDto;
import uk.jtoye.core.security.TenantContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #204 (AI-2) — app-layer proof that {@code POST /api/v1/customers}
 * honors the {@code Idempotency-Key} contract (AC-3): a same-key replay returns
 * the ORIGINAL customer with no duplicate row.
 *
 * <p>The pre-existing {@code uq_customers_tenant_email} constraint would already
 * reject a true duplicate with a 409, so the MEANINGFUL assertion is that a
 * replay returns the original customer's id (a genuine replay) rather than a
 * conflict — i.e. the idempotency contract short-circuits BEFORE the unique
 * constraint fires.
 *
 * <p>Drives the real {@link CustomerController#create} method. That method
 * builds a {@code Location} URI from the current request via
 * {@code ServletUriComponentsBuilder.fromCurrentRequest()}, so a
 * {@link MockHttpServletRequest} is bound to the thread for the direct call.
 * Scaffold mirrors {@code ConcurrentStockDecrementIntegrationTest}; runs as the
 * SUPERUSER bootstrap role (app-layer behavior, not RLS enforcement).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerIdempotencyIntegrationTest {

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
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("rate-limiting.enabled", () -> "false");
        registry.add("spring.rabbitmq.host", () -> "localhost");
        registry.add("spring.rabbitmq.port", () -> "0");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    @Autowired CustomerController customerController;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "Customer Idempotency Tenant");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        TenantContext.clear();
    }

    @Test
    void sameKeyReplay_returnsOriginalCustomer_noDuplicate() {
        String key = "customer-key-" + UUID.randomUUID();
        String email = "idem-" + UUID.randomUUID() + "@example.com";
        TenantContext.set(TENANT_ID);
        try {
            CreateCustomerRequest request =
                    new CreateCustomerRequest("Idem Customer", email, "+441234567890", 0);

            ResponseEntity<CustomerDto> first = customerController.create(request, key);
            ResponseEntity<CustomerDto> second = customerController.create(request, key);

            assertThat(first.getBody()).isNotNull();
            assertThat(second.getBody()).isNotNull();
            assertThat(second.getBody().id())
                    .as("replay returns the ORIGINAL customer id, not a 409")
                    .isEqualTo(first.getBody().id());
            assertThat(second.getStatusCode().value()).isEqualTo(201);

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM customers WHERE tenant_id = ? AND email = ?",
                    Integer.class, TENANT_ID, email);
            assertThat(count)
                    .as("exactly one customer row despite the repeated key")
                    .isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }
}
