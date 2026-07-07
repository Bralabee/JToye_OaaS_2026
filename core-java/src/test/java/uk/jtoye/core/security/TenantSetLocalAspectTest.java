package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests verifying that TenantSetLocalAspect correctly sets app.current_tenant_id
 * on the database connection before transactional operations.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class TenantSetLocalAspectTest {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void shouldSetLocalVariableWhenTenantContextPresent() {
        // Given
        UUID testTenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)", testTenantId, "Test Tenant");
        TenantContext.set(testTenantId);

        // When: Flush to trigger aspect
        entityManager.flush();

        // Then: Check that app.current_tenant_id is set
        String currentTenantId = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_tenant_id', true)",
                String.class
        );

        assertThat(currentTenantId).isEqualTo(testTenantId.toString());
    }

    @Test
    @Transactional
    void shouldHandleNullTenantContextGracefully() {
        // Given: No tenant context set
        TenantContext.clear();

        // When: Flush (aspect should not fail)
        entityManager.flush();

        // Then: current_setting should return null or empty
        String currentTenantId = jdbcTemplate.queryForObject(
                "SELECT current_setting('app.current_tenant_id', true)",
                String.class
        );

        // Setting should be empty when not set
        assertThat(currentTenantId).isNullOrEmpty();
    }
}
