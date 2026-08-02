package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.AnnouncementService;
import uk.jtoye.core.shop.PromotionService;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.PromotionDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA-council FC-1 cross-tenant write BOLA proof (real Postgres 15 via Testcontainers).
 *
 * <p><strong>The confirmed Critical:</strong> a tenant-B vendor JWT (a day-one implicit
 * GROUP_ADMIN, no {@code X-Tenant-Id} header) could name a tenant-A {@code shopId} on
 * {@code POST /promotions|/announcements|/products} and the row persisted under tenant B on
 * tenant A's shop (HTTP 201). Root cause was TWO gaps, proven closed here:
 *
 * <ol>
 *   <li><b>Fix 1 — the write hole in {@link ShopAccessService#require(UUID, ShopRole)}:</b> the
 *       GROUP_ADMIN early-return granted access for ANY {@code shopId} without checking the
 *       shop's owning tenant. A GROUP_ADMIN is tenant-WIDE, not cross-tenant, so a foreign
 *       {@code shopId} must be denied. The critical RLS subtlety: {@code shops_public_read} is
 *       {@code (published = true) OR (tenant_id = current_tenant_id())}, so a foreign PUBLISHED
 *       shop is still returned by {@code findById} under the caller's tenant GUC — only an
 *       explicit {@code tenant_id} comparison (not a null/empty check) closes the hole.</li>
 *   <li><b>Fix 2 — the authenticated LIST read leak (F-H1):</b> {@code getAllPromotions} /
 *       {@code getAllAnnouncements} returned {@code findAll(pageable)} for a GROUP_ADMIN, and the
 *       {@code shop_*_read} RLS policy's {@code OR EXISTS(published shop)} storefront carve-out
 *       leaked OTHER tenants' published-shop rows into the authenticated list. The tenant-scoped
 *       {@code findByTenantId} finder confines it.</li>
 * </ol>
 *
 * <p><strong>Role-independence (deliberate):</strong> the Testcontainers bootstrap role is a
 * SUPERUSER that bypasses even FORCE RLS (see {@link IntegrationTestSupport}). These assertions
 * do NOT depend on the RLS role — Fix 1 asserts a SERVICE-layer exception produced by the
 * explicit {@code tenant_id} comparison, and Fix 2's confinement is produced by the explicit
 * {@code WHERE tenant_id = ?} finder. Both hold whether or not RLS fires, which is why the leak
 * is reproducible here and the fix is provably not merely leaning on RLS.
 *
 * <p>Harness mirrors {@code StaffManagementIntegrationTest}: NOT {@code @Transactional} (a
 * blocked write must roll back its own service transaction independently, and seeded rows must
 * commit for a separate-transaction read to observe them), fresh random tenants/shops per test,
 * strict-scoping forced OFF on the {@link AopTestUtils}-unwrapped bean so the tenant-B caller is
 * the day-one implicit GROUP_ADMIN that the exploit used.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CrossTenantAuthzIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private PromotionService promotionService;
    @Autowired private AnnouncementService announcementService;
    @Autowired private ProductService productService;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // Fix 1 — cross-tenant WRITE BOLA is blocked (promotions, announcements, products)
    // ---------------------------------------------------------------------

    /**
     * The core Critical: a tenant-B day-one GROUP_ADMIN naming a tenant-A shopId on a promotion
     * create is denied with the typed {@link ShopAccessDeniedException}. Pre-fix: the GROUP_ADMIN
     * early-return let the create succeed (201) and the row persisted under tenant B on shop A.
     */
    @Test
    void createPromotion_crossTenantShop_isBlocked() {
        setStrictScoping(false);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID shopA = seedTenantAndShop(tenantA, true);   // a PUBLISHED tenant-A shop (the RLS trap)

        actAsDayOneGroupAdmin(tenantB);

        assertThatThrownBy(() -> promotionService.createPromotion(promotionRequest(shopA)))
                .as("a tenant-B GROUP_ADMIN must NOT create a promotion on a tenant-A shop (cross-tenant BOLA)")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThat(promotionCount(shopA))
                .as("no promotion row was written on the foreign shop")
                .isZero();
    }

    /**
     * Positive control: the SAME tenant-B day-one GROUP_ADMIN CAN create a promotion on its OWN
     * tenant's shop. Proves Fix 1 tightens cross-tenant access without regressing the legitimate
     * same-tenant path (Incremental Betterment).
     */
    @Test
    void createPromotion_ownTenantShop_succeeds() {
        setStrictScoping(false);
        UUID tenantB = UUID.randomUUID();
        UUID shopB = seedTenantAndShop(tenantB, true);

        actAsDayOneGroupAdmin(tenantB);

        assertThatCode(() -> promotionService.createPromotion(promotionRequest(shopB)))
                .as("a GROUP_ADMIN can still create a promotion on a shop in its OWN tenant")
                .doesNotThrowAnyException();
        assertThat(promotionCount(shopB)).isEqualTo(1);
    }

    /**
     * The same gate covers the catalogue: a tenant-B GROUP_ADMIN naming a tenant-A shopId on a
     * product create is denied. Proves the fix covers {@code ProductService.createProduct} too.
     */
    @Test
    void createProduct_crossTenantShop_isBlocked() {
        setStrictScoping(false);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID shopA = seedTenantAndShop(tenantA, true);

        actAsDayOneGroupAdmin(tenantB);

        assertThatThrownBy(() -> productService.createProduct(productRequest(shopA)))
                .as("a tenant-B GROUP_ADMIN must NOT create a product on a tenant-A shop (cross-tenant BOLA)")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThat(productCount(shopA))
                .as("no product row was written on the foreign shop")
                .isZero();
    }

    /**
     * The same gate covers announcements: a tenant-B GROUP_ADMIN naming a tenant-A shopId is denied.
     */
    @Test
    void createAnnouncement_crossTenantShop_isBlocked() {
        setStrictScoping(false);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID shopA = seedTenantAndShop(tenantA, true);

        actAsDayOneGroupAdmin(tenantB);

        assertThatThrownBy(() -> announcementService.createAnnouncement(announcementRequest(shopA)))
                .as("a tenant-B GROUP_ADMIN must NOT create an announcement on a tenant-A shop (cross-tenant BOLA)")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThat(announcementCount(shopA))
                .as("no announcement row was written on the foreign shop")
                .isZero();
    }

    // ---------------------------------------------------------------------
    // Fix 2 — the authenticated LIST read is confined to the caller's tenant (F-H1)
    // ---------------------------------------------------------------------

    /**
     * F-H1: with tenant A and tenant B each owning a PUBLISHED shop carrying a promotion, a
     * tenant-B GROUP_ADMIN's {@code getAllPromotions} returns ONLY tenant B's promotion — never
     * tenant A's, which the RLS storefront carve-out used to leak through {@code findAll}.
     */
    @Test
    void getAllPromotions_forGroupAdmin_isConfinedToOwnTenant() {
        setStrictScoping(false);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID shopA = seedTenantAndShop(tenantA, true);
        UUID shopB = seedTenantAndShop(tenantB, true);
        UUID promoA = seedPromotion(tenantA, shopA, "Tenant A promo");
        UUID promoB = seedPromotion(tenantB, shopB, "Tenant B promo");

        actAsDayOneGroupAdmin(tenantB);

        List<UUID> ids = promotionService.getAllPromotions(PageRequest.of(0, 1000))
                .getContent().stream().map(PromotionDto::getId).toList();

        assertThat(ids).as("the GROUP_ADMIN sees its own tenant's promotion").contains(promoB);
        assertThat(ids)
                .as("the GROUP_ADMIN must NOT see another tenant's published-shop promotion (F-H1 leak)")
                .doesNotContain(promoA);
    }

    /**
     * F-H1 mirror for announcements.
     */
    @Test
    void getAllAnnouncements_forGroupAdmin_isConfinedToOwnTenant() {
        setStrictScoping(false);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID shopA = seedTenantAndShop(tenantA, true);
        UUID shopB = seedTenantAndShop(tenantB, true);
        UUID annA = seedAnnouncement(tenantA, shopA, "Tenant A notice");
        UUID annB = seedAnnouncement(tenantB, shopB, "Tenant B notice");

        actAsDayOneGroupAdmin(tenantB);

        List<UUID> ids = announcementService.getAllAnnouncements(PageRequest.of(0, 1000))
                .getContent().stream().map(AnnouncementDto::getId).toList();

        assertThat(ids).as("the GROUP_ADMIN sees its own tenant's announcement").contains(annB);
        assertThat(ids)
                .as("the GROUP_ADMIN must NOT see another tenant's published-shop announcement (F-H1 leak)")
                .doesNotContain(annA);
    }

    // ---------------------------------------------------------------------
    // Fixtures + auth plumbing
    // ---------------------------------------------------------------------

    /** Authenticate a fresh non-realm-admin vendor and pin {@code tenant}: the day-one implicit GROUP_ADMIN. */
    private void actAsDayOneGroupAdmin(UUID tenant) {
        UUID sub = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "vendor-" + sub + "@example.com")
                .claim("name", "Vendor " + sub)
                .build();
        List<GrantedAuthority> authorities = List.of();   // NOT a realm-admin — the day-one path
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        TenantContext.set(tenant);
    }

    private CreatePromotionRequest promotionRequest(UUID shopId) {
        CreatePromotionRequest req = new CreatePromotionRequest();
        req.setLabel("10% off");
        req.setDiscountPercent(10);
        req.setValidFrom(OffsetDateTime.now().minusDays(1));
        req.setValidUntil(OffsetDateTime.now().plusDays(30));
        req.setShopId(shopId);
        return req;
    }

    private CreateAnnouncementRequest announcementRequest(UUID shopId) {
        CreateAnnouncementRequest req = new CreateAnnouncementRequest();
        req.setTitle("Store notice");
        req.setBody("We are open");
        req.setShopId(shopId);
        return req;
    }

    private CreateProductRequest productRequest(UUID shopId) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku("SKU-" + UUID.randomUUID().toString().substring(0, 8));
        req.setTitle("Test Product");
        req.setIngredientsText("Wheat flour, sugar");
        req.setAllergenMask(0);
        req.setPricePennies(999L);
        req.setShopId(shopId);
        return req;
    }

    /** Seed the (RLS-free) tenants row plus a shop; returns the shop id. */
    private UUID seedTenantAndShop(UUID tenant, boolean published) {
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "authz-tenant-" + tenant);
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                shopId, tenant, "shop-" + shopId, "slug-" + shopId, "1 Test Street, London", published);
        return shopId;
    }

    private UUID seedPromotion(UUID tenant, UUID shop, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_percent, "
                        + "  valid_from, valid_until, active, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW(), NOW() + INTERVAL '30 days', true, NOW())",
                id, tenant, shop, label, 10);
        return id;
    }

    private UUID seedAnnouncement(UUID tenant, UUID shop, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_announcements (id, tenant_id, shop_id, title, active, created_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW())",
                id, tenant, shop, title);
        return id;
    }

    private long promotionCount(UUID shopId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_promotions WHERE shop_id = ?", Long.class, shopId);
        return n == null ? 0 : n;
    }

    private long announcementCount(UUID shopId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_announcements WHERE shop_id = ?", Long.class, shopId);
        return n == null ? 0 : n;
    }

    private long productCount(UUID shopId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM products WHERE shop_id = ?", Long.class, shopId);
        return n == null ? 0 : n;
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
