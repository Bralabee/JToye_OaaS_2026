package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WR-03 — the Keep action ({@code POST /api/v1/media/{assetId}/keep} ->
 * {@link MediaAssetService#dismissFlag}) MUTATES asset state, so it must enforce the same
 * VSA-02 shop-scoped write gate ({@code SHOP_MANAGER}) as upload-accept and image-delete.
 * Before the fix it was only tenant-scoped, so a SHOP_MANAGER of shop A could clear the
 * content-review flag on shop B's flagged image — a shop-scoping bypass on a mutating action.
 *
 * <p>Mirrors {@code ShopAccessEnforcementIntegrationTest}: runs under {@code strict-scoping ON}
 * (so a scoped user is genuinely confined — no JIT GROUP_ADMIN), as the Testcontainers
 * superuser (this proves the APPLICATION-layer shop gate, not the RLS tenant wall — that is
 * proven under NOSUPERUSER in {@code MediaReviewQueueIntegrationTest}). {@code shop_staff}
 * grants are seeded directly; strict-scoping is toggled on the proxy-unwrapped bean.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class MediaKeepShopScopeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MediaAssetService mediaAssetService;
    @Autowired private ShopAccessService shopAccessService;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;
    private int seq;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- behaviours ------------------------------------------------------

    @Test
    void shopManagerOfOtherShop_isDeniedDismissingAnotherShopsFlag() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant);
        UUID shopB = seedShop(tenant);
        UUID productB = seedProduct(tenant, shopB);
        UUID assetB = seedFlaggedActiveAsset(tenant, productB);

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        // A SHOP_MANAGER of shop A must NOT be able to Keep (dismiss the flag on) shop B's asset.
        assertThatThrownBy(() -> mediaAssetService.dismissFlag(assetB))
                .as("a SHOP_MANAGER of shop A cannot dismiss shop B's content flag")
                .isInstanceOf(ShopAccessDeniedException.class);

        // The flag is untouched — the denied write never committed.
        assertThat(flagged(assetB)).as("the flag stays set after the denied Keep").isTrue();
    }

    @Test
    void shopManagerOfOwningShop_canDismissTheFlag() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopB = seedShop(tenant);
        UUID productB = seedProduct(tenant, shopB);
        UUID assetB = seedFlaggedActiveAsset(tenant, productB);

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopB, "SHOP_MANAGER");   // granted the OWNING shop

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        assertThatCode(() -> mediaAssetService.dismissFlag(assetB))
                .as("a SHOP_MANAGER of the owning shop may Keep (dismiss the flag)")
                .doesNotThrowAnyException();
        assertThat(flagged(assetB)).as("the flag is cleared").isFalse();
    }

    @Test
    void sharedAssetWithNoResolvableShop_fallsBackToGroupAdminOnly() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant);
        // A shared/legacy flagged asset with NO placement product_id and no product_media join
        // -> no resolvable shop -> the null-shop GROUP_ADMIN-only rule applies.
        UUID sharedAsset = seedFlaggedActiveAsset(tenant, null);

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        // A scoped (non-GROUP_ADMIN) SHOP_MANAGER is denied a null-shop asset (GROUP_ADMIN-only).
        assertThatThrownBy(() -> mediaAssetService.dismissFlag(sharedAsset))
                .as("a scoped SHOP_MANAGER cannot Keep a shared/legacy null-shop asset")
                .isInstanceOf(ShopAccessDeniedException.class);
        assertThat(flagged(sharedAsset)).isTrue();
    }

    // --- seeding helpers -------------------------------------------------

    private void ensureTenant(UUID tenant) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Keep Scope Tenant " + tenant);
    }

    private UUID seedShop(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version) VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)",
                id, tenant, "Shop " + id, "shop-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID seedProduct(UUID tenant, UUID shopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, display_order, available, featured, shop_id, quantity_in_stock, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 1000, 0, true, false, ?, 0, 0)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Product", "rice, tomato", shopId);
        return id;
    }

    /** A flagged ACTIVE asset carrying {@code productId} as its placement intent (null = shared/legacy). */
    private UUID seedFlaggedActiveAsset(UUID tenant, UUID productId) {
        UUID id = UUID.randomUUID();
        String sha = String.format("%064d", seq++);
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, flagged, product_id) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE', true, ?)",
                id, tenant, tenant + "/media/" + id + ".webp", sha, productId);
        return id;
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, userId, shopId, role);
    }

    private boolean flagged(UUID assetId) {
        Boolean f = jdbc.queryForObject("SELECT flagged FROM media_asset WHERE id = ?", Boolean.class, assetId);
        return Boolean.TRUE.equals(f);
    }

    // --- auth + strict-scoping plumbing (mirrors ShopAccessEnforcementIntegrationTest) ---

    private void authenticate(UUID sub, boolean realmAdmin) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "user-" + sub + "@example.com")
                .claim("name", "Test User " + sub)
                .build();
        List<GrantedAuthority> authorities = realmAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }
}
