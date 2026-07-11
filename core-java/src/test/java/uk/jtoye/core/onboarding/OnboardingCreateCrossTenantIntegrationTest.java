package uk.jtoye.core.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CR-02 regression proof on real Postgres 15 (Testcontainers): {@code POST
 * /onboarding} rejects a {@code shopId} the caller's tenant does not own — or that
 * does not exist — with a clean 404, so no cross-tenant binding is ever persisted.
 *
 * <p>The V43 FK {@code shop_id -> shops(id)} is enforced by Postgres
 * referential-integrity, which <strong>bypasses RLS</strong>, so app-layer ownership
 * validation ({@code shopRepository.findByIdAndTenantId}) is the only guard. Tenant
 * B's shop is seeded {@code published = true} on purpose: published shop UUIDs are
 * public (storefront API) and readable cross-tenant under the V16
 * {@code shops_public_read} policy, so the explicit tenant filter — not RLS alone —
 * is what defeats the bind.
 *
 * <p>Not {@code @Transactional}: create commits through the service's own
 * transaction. Each test uses fresh random tenants/shops so rows never collide;
 * seeds run via {@link JdbcTemplate} (bootstrap role bypasses RLS) so the FK targets
 * exist before the guarded request runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingCreateCrossTenantIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantAndShop() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
    }

    private String createBody(UUID shop) throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shop);
        req.setCompanyNumber("12345678");
        return objectMapper.writeValueAsString(req);
    }

    @Test
    @WithMockUser
    void createWithForeignPublishedShopIsRejected404_noCrossTenantBinding() throws Exception {
        // Tenant B owns a PUBLISHED shop, readable cross-tenant under shops_public_read.
        UUID tenantB = UUID.randomUUID();
        UUID shopB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopB, tenantB, "shop-" + shopB, "slug-" + shopB.toString().substring(0, 8), "9 Other Road");

        // Tenant A points its onboarding at tenant B's published shop -> 404, not bound.
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(shopB)))
                .andExpect(status().isNotFound());

        // No onboarding row was persisted for tenant A (nor bound to shop B).
        Integer forTenantA = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(forTenantA).isZero();
        Integer boundToShopB = jdbc.queryForObject(
                "SELECT count(*) FROM vendor_onboarding WHERE shop_id = ?", Integer.class, shopB);
        assertThat(boundToShopB).isZero();
    }

    @Test
    @WithMockUser
    void createWithNonexistentShopIsRejected404NotDuplicate409() throws Exception {
        // A nonexistent shopId used to surface as an FK DataIntegrityViolationException
        // mapped to 409 "Duplicate Entry" (a shop-UUID existence oracle); it must now
        // be a clean 404.
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void createWithOwnShopSucceeds() throws Exception {
        // The ownership guard must not over-reject the caller's own shop.
        mockMvc.perform(post("/api/v1/onboarding")
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(shopId)))
                .andExpect(status().isCreated());
    }
}
