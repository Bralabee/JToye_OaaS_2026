package uk.jtoye.core.media;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.TenantJwts;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/v1/media/review-queue} is shop-scoped (Phase 37-04, deviation from 37-03
 * deferred-items §2, threat T-37-06 read half).
 *
 * <p>Before this, {@code MediaAssetService.reviewQueue()} made no {@code ShopAccessService} call: the
 * queue was tenant-scoped by RLS only. Under strict scoping OFF that was invisible, because every
 * ungranted user was an implicit tenant-wide admin anyway. Under the D-06 default it is a leak: a
 * vendor with no grant, or a grant on one shop, received every FAILED, flagged and stalled asset in
 * the tenant — asset ids, product ids and failure reasons for shops they cannot otherwise read.
 *
 * <p>The rule now matches the product list read ({@code ProductService.getAllProducts}): a
 * GROUP_ADMIN (realm admin, honoured tenant-wide grant, declared system caller) sees the whole
 * tenant's queue; any other caller sees only assets whose owning shop is in its grant set; an
 * asset with no resolvable shop is GROUP_ADMIN-only, as every action on it (Keep, re-process) is.
 *
 * <p>Two shops in one tenant, one queued asset on each, plus one shop-less asset. Each test uses its
 * own tenant so a tenant-wide grant in one cannot leak into another. The Testcontainers role is a
 * SUPERUSER, so RLS does not filter here; this class tests the application-layer shop wall, and
 * the tenant wall is proven by {@code MediaReviewQueueIntegrationTest} and
 * {@code MediaRedriveControllerTest} on NOSUPERUSER roles. Assertions are therefore on the presence
 * or absence of THIS tenant's three asset ids, never on the list length alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaReviewQueueShopScopeIntegrationTest {

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
    @Autowired private JdbcTemplate jdbc;

    private UUID tenant;
    private UUID shopA;
    private UUID shopB;
    private UUID assetOnA;
    private UUID assetOnB;
    private UUID shoplessAsset;
    private static int seq;

    @BeforeEach
    void seed() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "review-scope-" + tenant);
        shopA = seedShop();
        shopB = seedShop();
        assetOnA = seedAsset(seedProduct(shopA), "FAILED", false, "dispatch stalled");
        assetOnB = seedAsset(seedProduct(shopB), "ACTIVE", true, null);
        shoplessAsset = seedAsset(null, "FAILED", false, "unsupported image format");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("an ungranted vendor sees none of the tenant's queued assets")
    void ungrantedVendor_seesNothing() throws Exception {
        List<String> ids = queueIds(TenantJwts.vendorJwt(UUID.randomUUID(), tenant));

        assertThat(ids).as("ungranted caller's review queue")
                .doesNotContain(assetOnA.toString(), assetOnB.toString(), shoplessAsset.toString());
    }

    @Test
    @DisplayName("a vendor granted shop A sees shop A's asset only (not shop B's, not the shop-less one)")
    void grantedOnShopA_seesOnlyShopA() throws Exception {
        UUID staffA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, staffA, shopA, "STAFF", "staff-" + staffA + "@example.com");

        List<String> ids = queueIds(TenantJwts.vendorJwt(staffA, tenant));

        assertThat(ids).as("shop A grantee's review queue").contains(assetOnA.toString());
        assertThat(ids).as("shop A grantee's review queue")
                .doesNotContain(assetOnB.toString(), shoplessAsset.toString());
    }

    @Test
    @DisplayName("an OPERATOR tenant-wide GROUP_ADMIN sees every queued asset in the tenant")
    void operatorGroupAdmin_seesTheWholeTenant() throws Exception {
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");

        List<String> ids = queueIds(TenantJwts.vendorJwt(admin, tenant));

        assertThat(ids).as("tenant-wide admin's review queue")
                .contains(assetOnA.toString(), assetOnB.toString(), shoplessAsset.toString());
    }

    @Test
    @DisplayName("a realm admin sees every queued asset in the tenant")
    void realmAdmin_seesTheWholeTenant() throws Exception {
        List<String> ids = queueIds(TenantJwts.adminJwt(tenant));

        assertThat(ids).as("realm admin's review queue")
                .contains(assetOnA.toString(), assetOnB.toString(), shoplessAsset.toString());
    }

    // ---- helpers ------------------------------------------------------------

    private List<String> queueIds(RequestPostProcessor caller) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/media/review-queue").with(caller)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("review-queue status: %s", body).isEqualTo(200);
        return JsonPath.read(body, "$[*].assetId");
    }

    private UUID seedShop() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, "Shop " + id, "rq-" + id);
        return id;
    }

    private UUID seedProduct(UUID shopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0, 0)",
                id, tenant, "SKU-RQ-" + id, "Suya", "beef, spice", shopId);
        return id;
    }

    private UUID seedAsset(UUID productId, String status, boolean flagged, String failureReason) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status, flagged, "
                        + "failure_reason, product_id) VALUES (?, ?, ?, ?, 'image/webp', ?, ?, ?, ?)",
                id, tenant, tenant + "/media/" + id + ".webp", String.format("%064d", ++seq),
                status, flagged, failureReason, productId);
        return id;
    }
}
