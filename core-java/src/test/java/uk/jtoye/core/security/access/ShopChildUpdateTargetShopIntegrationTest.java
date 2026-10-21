package uk.jtoye.core.security.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * An update that changes a shop-child row's {@code shopId} is authorised against the NEW shop as well as the
 * current one, for the three shop-child resources whose PUT body carries a {@code shopId}: products,
 * promotions and announcements.
 *
 * <p>The PUT gate resolves the row's CURRENT shop and requires SHOP_MANAGER there. The request mappers used to
 * copy the body's {@code shopId} onto the row as well, so the shop named in the body was never itself checked
 * (the create paths always were). The fix is {@code ShopAccessService.requireForShopChange} plus an explicit
 * assignment in each service; the mappers no longer copy {@code shopId}.
 *
 * <p>Real Postgres with the Testcontainers role downgraded to NOSUPERUSER (inside the test transaction, rolled
 * back after each test) so FORCE RLS is enforced under every statement the requests issue, strict scoping ON,
 * production-shaped JWTs through the real filter chain (MockMvc). Seeding is done by direct SQL BEFORE the
 * downgrade; database state is read back with the tenant GUC pinned, because under RLS an unpinned read
 * returns nothing and "unchanged" would pass vacuously. Every refusal arm is paired with an allowed control
 * (a managed second shop, a Group admin, an unchanged shop), so a refusal can fail.
 *
 * <p>Both directions are exercised through the service-layer {@code deleteShop} of the OTHER tenant, flushed:
 * a row left pointing at that tenant's shop would block it on the foreign key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
@Import(NoScheduledTriggersTestConfig.class)
class ShopChildUpdateTargetShopIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** The three resources, with the table, the path, a seeded row and a valid PUT body for each. */
    enum Kind {
        PRODUCT("products", "/api/v1/products/"),
        PROMOTION("shop_promotions", "/api/v1/promotions/"),
        ANNOUNCEMENT("shop_announcements", "/api/v1/announcements/");

        final String table;
        final String path;

        Kind(String table, String path) {
            this.table = table;
            this.path = path;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopAccessService shopAccessService;
    @Autowired private ShopService shopService;
    @PersistenceContext private EntityManager em;

    private Object bootedStrictScoping;
    private UUID tenantA;
    private UUID tenantB;
    private UUID shopA1;
    private UUID shopA2;
    private UUID shopA3;
    private UUID shopAStaffOnly;
    private UUID foreignPublished;
    private UUID foreignUnpublished;
    private UUID managerOfA1A2;
    private UUID groupAdminA;
    private String suffix;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        suffix = tenantA.toString().substring(0, 8);
        tenant(tenantA, "ST-A-" + suffix);
        tenant(tenantB, "ST-B-" + suffix);
        shopA1 = shop(tenantA, "a1", true);
        shopA2 = shop(tenantA, "a2", true);
        shopA3 = shop(tenantA, "a3", true);
        shopAStaffOnly = shop(tenantA, "a4", true);
        foreignPublished = shop(tenantB, "bp", true);
        foreignUnpublished = shop(tenantB, "bu", false);

        managerOfA1A2 = UUID.randomUUID();
        grant(tenantA, managerOfA1A2, shopA1, "SHOP_MANAGER");
        grant(tenantA, managerOfA1A2, shopA2, "SHOP_MANAGER");
        grant(tenantA, managerOfA1A2, shopAStaffOnly, "STAFF");
        groupAdminA = UUID.randomUUID();
        grant(tenantA, groupAdminA, null, "GROUP_ADMIN");

        // From here every statement runs under FORCE RLS (rolled back with the test transaction).
        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("the connection role is NOSUPERUSER, so FORCE RLS applies").isFalse();
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Refusals: the target shop is not one the caller may manage
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("a manager of the row's shop cannot name another tenant's published shop (403); the row is unchanged")
    void managerCannotNameForeignPublishedShop(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);
        assertThat(shopOf(kind, row, tenantA)).as("precondition").isEqualTo(shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, foreignPublished);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(403);
        assertThat(shopOf(kind, row, tenantA)).as("the row was not re-pointed: %s", text(result)).isEqualTo(shopA1);
        assertForeignShopCanBeDeleted(foreignPublished, tenantB);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("a manager of the row's shop cannot name another tenant's unpublished shop; the row is unchanged")
    void managerCannotNameForeignUnpublishedShop(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, foreignUnpublished);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(403);
        assertThat(shopOf(kind, row, tenantA)).as(text(result)).isEqualTo(shopA1);
        assertForeignShopCanBeDeleted(foreignUnpublished, tenantB);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("a manager cannot name a sibling shop of the same business that they hold no grant on")
    void managerCannotNameSiblingShopWithoutGrant(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, shopA3);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(403);
        assertThat(shopOf(kind, row, tenantA)).as(text(result)).isEqualTo(shopA1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("a manager cannot name a shop on which they hold only STAFF")
    void managerCannotNameShopWhereOnlyStaff(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, shopAStaffOnly);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(403);
        assertThat(shopOf(kind, row, tenantA)).as(text(result)).isEqualTo(shopA1);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("a Group admin cannot name another tenant's shop either; the row is unchanged")
    void groupAdminCannotNameForeignShop(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult published = putRow(kind, groupAdminA, tenantA, row, foreignPublished);
        assertThat(published.getResponse().getStatus()).as(text(published)).isEqualTo(404);
        assertThat(shopOf(kind, row, tenantA)).as(text(published)).isEqualTo(shopA1);

        MvcResult unpublished = putRow(kind, groupAdminA, tenantA, row, foreignUnpublished);
        assertThat(unpublished.getResponse().getStatus()).as(text(unpublished)).isEqualTo(404);
        assertThat(shopOf(kind, row, tenantA)).as(text(unpublished)).isEqualTo(shopA1);
        assertForeignShopCanBeDeleted(foreignPublished, tenantB);
    }

    // ------------------------------------------------------------------
    // Controls: what must keep working
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("control: a manager can move a row to a second shop they ALSO manage")
    void managerCanMoveToSecondManagedShop(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, shopA2);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(shopOf(kind, row, tenantA)).as(text(result)).isEqualTo(shopA2);
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("control: naming the row's own shop is an ordinary edit and keeps working")
    void unchangedShopStillSaves(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, managerOfA1A2, tenantA, row, shopA1);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(shopOf(kind, row, tenantA)).isEqualTo(shopA1);
        assertThat(titleOf(kind, row, tenantA)).as("the edit itself was applied").isEqualTo("edited");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    @DisplayName("control: a Group admin can move a row to any shop of their own tenant")
    void groupAdminCanMoveWithinOwnTenant(Kind kind) throws Exception {
        UUID row = seedRow(kind, shopA1);

        MvcResult result = putRow(kind, groupAdminA, tenantA, row, shopA3);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(shopOf(kind, row, tenantA)).as(text(result)).isEqualTo(shopA3);
    }

    @Test
    @DisplayName("control: a shop-less product can be assigned a shop (Group admin; the shop-less assignment path)")
    void shopLessProductCanBeAssignedAShop() throws Exception {
        UUID row = seedRow(Kind.PRODUCT, null);
        assertThat(shopOf(Kind.PRODUCT, row, tenantA)).as("precondition: shop-less").isNull();

        MvcResult result = putRow(Kind.PRODUCT, groupAdminA, tenantA, row, shopA2);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(shopOf(Kind.PRODUCT, row, tenantA)).as(text(result)).isEqualTo(shopA2);
    }

    @Test
    @DisplayName("a shop-less product cannot be assigned another tenant's shop, even by a Group admin")
    void shopLessProductCannotBeAssignedForeignShop() throws Exception {
        UUID row = seedRow(Kind.PRODUCT, null);

        MvcResult result = putRow(Kind.PRODUCT, groupAdminA, tenantA, row, foreignPublished);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(404);
        assertThat(shopOf(Kind.PRODUCT, row, tenantA)).as(text(result)).isNull();
        assertForeignShopCanBeDeleted(foreignPublished, tenantB);
    }

    @Test
    @DisplayName("control: a product PUT that omits shopId keeps the stored shop")
    void productPutWithoutShopIdKeepsShop() throws Exception {
        UUID row = seedRow(Kind.PRODUCT, shopA1);

        MvcResult result = putRow(Kind.PRODUCT, managerOfA1A2, tenantA, row, null);

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        assertThat(shopOf(Kind.PRODUCT, row, tenantA)).isEqualTo(shopA1);
        assertThat(titleOf(Kind.PRODUCT, row, tenantA)).isEqualTo("edited");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The other tenant deletes its shop (through the service, flushed): a row pointing at it would block this. */
    private void assertForeignShopCanBeDeleted(UUID shop, UUID owner) {
        Jwt token = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", owner.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        TenantContext.set(owner);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
        try {
            shopService.deleteShop(shop);
            em.flush();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
        assertThat(pinned(owner, () -> jdbc.queryForObject("SELECT count(*) FROM shops WHERE id = ?",
                Integer.class, shop))).as("the other tenant's shop delete went through").isZero();
    }

    private MvcResult putRow(Kind kind, UUID user, UUID tenant, UUID row, UUID shopId) throws Exception {
        return mockMvc.perform(put(kind.path + row).with(userJwt(user, tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body(kind, shopId))))
                .andReturn();
    }

    private Map<String, Object> body(Kind kind, UUID shopId) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (kind) {
            case PRODUCT -> {
                body.put("sku", "ST-" + suffix + "-" + kind.ordinal());
                body.put("title", "edited");
                body.put("ingredientsText", "Water");
                body.put("allergenMask", 0);
                body.put("pricePennies", 900);
            }
            case PROMOTION -> {
                body.put("label", "edited");
                body.put("discountType", "PERCENTAGE");
                body.put("discountPercent", 10);
                body.put("validFrom", "2026-01-01T00:00:00Z");
                body.put("validUntil", "2030-01-01T00:00:00Z");
                body.put("active", true);
            }
            case ANNOUNCEMENT -> {
                body.put("title", "edited");
                body.put("active", true);
            }
        }
        if (shopId != null) {
            body.put("shopId", shopId.toString());
        }
        return body;
    }

    private UUID seedRow(Kind kind, UUID shop) {
        UUID id = UUID.randomUUID();
        // Seeded after the role downgrade, so the insert itself goes through the tenant's WITH CHECK.
        // JdbcTemplate calls re-apply the GUC from TenantContext, so the tenant is pinned there.
        TenantContext.set(tenantA);
        try {
            insertRow(kind, id, shop);
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private void insertRow(Kind kind, UUID id, UUID shop) {
        switch (kind) {
            case PRODUCT -> jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, vat_rate, available) "
                            + "VALUES (?, ?, ?, ?, 'seeded', 'Water', 0, 900, 'STANDARD', true)",
                    id, tenantA, shop, "ST-" + suffix + "-" + kind.ordinal());
            case PROMOTION -> jdbc.update("INSERT INTO shop_promotions (id, tenant_id, shop_id, label, discount_type, "
                            + "discount_percent, valid_from, valid_until, active) "
                            + "VALUES (?, ?, ?, 'seeded', 'PERCENTAGE', 10, now(), now() + interval '30 days', true)",
                    id, tenantA, shop);
            case ANNOUNCEMENT -> jdbc.update("INSERT INTO shop_announcements (id, tenant_id, shop_id, title, active) "
                    + "VALUES (?, ?, ?, 'seeded', true)", id, tenantA, shop);
        }
    }

    private UUID shopOf(Kind kind, UUID row, UUID tenant) {
        return pinned(tenant, () -> jdbc.queryForObject(
                "SELECT shop_id FROM " + kind.table + " WHERE id = ?", UUID.class, row));
    }

    private String titleOf(Kind kind, UUID row, UUID tenant) {
        String column = kind == Kind.PROMOTION ? "label" : "title";
        return pinned(tenant, () -> jdbc.queryForObject(
                "SELECT " + column + " FROM " + kind.table + " WHERE id = ?", String.class, row));
    }

    /**
     * Reads under RLS need the tenant pinned (an unpinned read returns nothing and would make "unchanged" vacuous).
     * JdbcTemplate calls re-apply the GUC from {@link TenantContext}, so that is what is set.
     */
    private <T> T pinned(UUID tenant, java.util.function.Supplier<T> read) {
        TenantContext.set(tenant);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void tenant(UUID id, String name) {
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", id, name);
    }

    private UUID shop(UUID tenant, String tag, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 0, 0)",
                id, tenant, "Shop " + tag + " " + suffix, "st-" + tag + "-" + suffix, published);
        return id;
    }

    private void grant(UUID tenant, UUID user, UUID shop, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, ?, ?, now(), 'OPERATOR')", UUID.randomUUID(), tenant, user, shop, role);
    }

    /** A vendor user with no realm role: what they may write comes from shop_staff alone. */
    private static RequestPostProcessor userJwt(UUID subject, UUID tenant) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_catalog:write"));
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
