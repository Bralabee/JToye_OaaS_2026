package uk.jtoye.core.onboarding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.onboarding.dto.CreateOnboardingRequest;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.TenantJwts;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #961 review F5: pins the CR-02 tenant finder in {@code VendorOnboardingService.createOnboarding}
 * ({@code shopRepository.findByIdAndTenantId}) on its own.
 *
 * <p>{@code OnboardingCreateCrossTenantIntegrationTest} runs behind the real {@code ShopAccessService} gate,
 * whose {@code requireShopInCallerTenant} also answers a foreign shop with 404. After #961 added that gate the
 * CR-02 test therefore stayed green with the finder deleted (measured: 3/3 green with the two lines removed),
 * so it no longer pinned the finder. Here the gate is a Mockito no-op, so the ONLY thing between a caller and
 * the V43 foreign key (Postgres referential integrity bypasses RLS) is the finder. The {@code verify} proves
 * the gate was neutralised rather than absent, and the own-shop arm is the control that the finder still
 * admits the caller's own shop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class OnboardingCreateFinderPinIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** A no-op gate: every {@code require} returns, so the service's own tenant finder is all that is left. */
    @MockitoBean private ShopAccessService shopAccessService;

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "finder-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "1 Test Street");
    }

    private String body(UUID shop) throws Exception {
        CreateOnboardingRequest req = new CreateOnboardingRequest();
        req.setModel(OnboardingModel.MARKETPLACE);
        req.setShopId(shop);
        req.setCompanyNumber("12345678");
        return objectMapper.writeValueAsString(req);
    }

    @Test
    void foreignPublishedShop_isRefusedByTheServiceFinderAlone_noCrossTenantBinding() throws Exception {
        UUID tenantB = UUID.randomUUID();
        UUID shopB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "finder-b-" + tenantB);
        // PUBLISHED: readable cross-tenant under shops_public_read, so only the explicit tenant filter stops it.
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopB, tenantB, "shop-" + shopB, "slug-" + shopB.toString().substring(0, 8), "9 Other Road");

        mockMvc.perform(post("/api/v1/onboarding")
                        .with(TenantJwts.adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(shopB)))
                .andExpect(status().isNotFound());

        verify(shopAccessService).require(shopB, ShopRole.SHOP_MANAGER); // the gate ran, as a no-op
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_onboarding WHERE shop_id = ?",
                Integer.class, shopB)).as("nothing was bound to the foreign shop").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_onboarding WHERE tenant_id = ?",
                Integer.class, tenantId)).isZero();
    }

    @Test
    void nonexistentShop_isRefusedByTheServiceFinderAlone() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .with(TenantJwts.adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void control_ownShop_isAdmittedByTheFinder() throws Exception {
        mockMvc.perform(post("/api/v1/onboarding")
                        .with(TenantJwts.adminJwt(tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(shopId)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vendor_onboarding WHERE shop_id = ?",
                Integer.class, shopId)).isEqualTo(1);
    }
}
