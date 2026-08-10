package uk.jtoye.core.shop;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Regression guard for the QA-council M3(+extension) cross-tenant shop-write
 * IDOR fixed in PR #70 (issue #71 item 3): every shop write —
 * {@code updateShop}/{@code deleteShop} and the four image methods — must 404
 * for a caller from another tenant BEFORE any side effect runs. The original
 * defect let a cross-tenant {@code removeLogo}/{@code removeBanner} delete
 * another tenant's object from S3/MinIO (storage delete ran before the
 * FORCE-RLS write failed) and returned 200.
 *
 * <p>Runs against real Postgres with the role downgraded to NOSUPERUSER after
 * seeding, so FORCE RLS is genuinely enforced underneath the service-layer
 * {@code findByIdAndTenantId} scoping. {@link StorageService} is mocked — the
 * assertions on it ARE the point: "no side effect before the 404".
 *
 * <p>{@code @Transactional} (mirroring MultiTenantIsolationIntegrationTest) is
 * load-bearing: ALTER ROLE is transactional, so the per-test rollback restores
 * the superuser for the next test's seeding — a non-superuser cannot promote
 * itself back, and its tenants-insert would otherwise trip the Postgres
 * ON CONFLICT edge where an RLS-invisible conflicting row raises instead of
 * skipping.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("testcontainers")
// #283: same shape as CrossTenantMcpWriteRlsIntegrationTest — the subject is the cross-tenant
// IDOR 404 (PR #70 / issue #71), reached THROUGH the shop gate. Declaring the harness keeps
// requireShopInCallerTenant as the thing that answers, which is what these tests assert.
@uk.jtoye.core.testsupport.AsSystemHarness
class ShopImageCrossTenantIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopService shopService;
    @Autowired private ShopRepository shopRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @MockBean private StorageService storageService;

    private static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String LOGO_URL = "https://cdn.test/shops/a/logo.png";
    private static final String BANNER_URL = "https://cdn.test/shops/a/banner.png";

    private UUID shopAId;

    @BeforeEach
    void setUp() {
        // Names must be unique: V13 seeds (…01,'Tenant A')/(…02,'Tenant B') and
        // tenants.name is UNIQUE — ON CONFLICT (id) cannot arbitrate a NAME
        // collision from our distinct ids, so reusing those names 500s here.
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_A, "IDOR Tenant A");
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT_B, "IDOR Tenant B");

        // Seed tenant A's shop. saveAndFlush: the INSERT must execute NOW,
        // while the aspect-set tenant GUC is still A — Hibernate batching
        // would otherwise defer it into a later flush under tenant B's GUC
        // and violate the RLS WITH CHECK.
        TenantContext.set(TENANT_A);
        try {
            Shop shop = new Shop();
            shop.setTenantId(TENANT_A);
            shop.setName("Tenant A Shop");
            shop.setAddress("1 Test Way");
            shop.setSlug("idor-" + UUID.randomUUID().toString().substring(0, 8));
            shop.setLogoUrl(LOGO_URL);
            shop.setBannerUrl(BANNER_URL);
            shopAId = shopRepository.saveAndFlush(shop).getId();
        } finally {
            TenantContext.clear();
        }
        // Detach the seed: service/repository lookups below must hit SQL
        // (where tenant scoping + RLS apply), not the persistence context.
        entityManager.clear();

        // The Testcontainers bootstrap role is a SUPERUSER and would bypass
        // FORCE RLS. Downgrade inside the test transaction (rolled back after
        // each test — see class Javadoc); Flyway already ran at boot as superuser.
        jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void crossTenantRemoveLogoMustNotTouchStorage() {
        TenantContext.set(TENANT_B);

        assertThatThrownBy(() -> shopService.removeLogo(shopAId))
                .isInstanceOf(ResourceNotFoundException.class);

        // The original defect: storage delete executed BEFORE the RLS write failed.
        verify(storageService, never()).delete(anyString());
        assertThat(logoUrlAsTenantA()).isEqualTo(LOGO_URL);
    }

    @Test
    void crossTenantRemoveBannerMustNotTouchStorage() {
        TenantContext.set(TENANT_B);

        assertThatThrownBy(() -> shopService.removeBanner(shopAId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(storageService, never()).delete(anyString());
    }

    @Test
    void crossTenantUploadLogoMustNotTouchStorage() {
        TenantContext.set(TENANT_B);
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> shopService.uploadLogo(shopAId, file))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(storageService, never()).delete(anyString());
        verify(storageService, never()).uploadNamed(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void crossTenantUploadBannerMustNotTouchStorage() {
        TenantContext.set(TENANT_B);
        MockMultipartFile file = new MockMultipartFile("file", "banner.png", "image/png", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> shopService.uploadBanner(shopAId, file))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(storageService, never()).delete(anyString());
        verify(storageService, never()).uploadNamed(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void crossTenantUpdateShopMustReturn404() {
        TenantContext.set(TENANT_B);
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Hijacked Name");

        assertThatThrownBy(() -> shopService.updateShop(shopAId, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossTenantDeleteShopMustReturn404AndLeaveShopIntact() {
        TenantContext.set(TENANT_B);

        assertThatThrownBy(() -> shopService.deleteShop(shopAId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(storageService, never()).delete(anyString());
        assertThat(shopExistsAsTenantA()).isTrue();
    }

    /** Positive control: the same call from the OWNING tenant works — proves the
     *  cross-tenant 404s above come from tenant scoping, not broken wiring. */
    @Test
    void sameTenantRemoveLogoStillWorks() {
        TenantContext.set(TENANT_A);

        shopService.removeLogo(shopAId);

        verify(storageService, times(1)).delete(LOGO_URL);
        assertThat(logoUrlAsTenantA()).isNull();
    }

    private String logoUrlAsTenantA() {
        entityManager.clear();
        TenantContext.set(TENANT_A);
        try {
            // NOT Optional.map(Shop::getLogoUrl).orElse(...): a legitimately-null
            // logoUrl (post-remove) would collapse the Optional to empty and
            // return the sentinel, conflating "shop gone" with "logo removed".
            Shop shop = shopRepository.findById(shopAId)
                    .orElseThrow(() -> new AssertionError("Shop not visible to its own tenant"));
            return shop.getLogoUrl();
        } finally {
            TenantContext.set(TENANT_B);
        }
    }

    private boolean shopExistsAsTenantA() {
        entityManager.clear();
        TenantContext.set(TENANT_A);
        try {
            return shopRepository.findById(shopAId).isPresent();
        } finally {
            TenantContext.set(TENANT_B);
        }
    }
}
