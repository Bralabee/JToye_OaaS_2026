package uk.jtoye.core.storefront;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #789 (P0, D-10/D-11/D-20): the public shop endpoint names the SELLER — the tenant's legal entity
 * from {@code trader_identity}, its company number from {@code vendor_onboarding} when it is a
 * company, and the shop's own email and phone — so the storefront, the checkout and the
 * confirmation can show the customer who they are buying from.
 *
 * <p>Proven against the real posture: Postgres 15 with every migration applied, the application's
 * connection role downgraded to NOSUPERUSER so FORCE row-level security is genuinely enforced, and
 * the real filter chain behind MockMvc with NO credentials (an anonymous shopper).
 * {@code trader_identity} is FORCE RLS tenant-scoped, so an anonymous read sees nothing unless the
 * SHOP's tenant is pinned; that pin is exactly what the seller read must do, and the cross-tenant
 * arm proves it pins the right one.
 *
 * <p>One arm each:
 * <ul>
 *   <li>a published shop of a tenant with an identity carries the full seller object, and the
 *       request thread's {@link TenantContext} is empty afterwards (T-31.1-82);</li>
 *   <li>tenant B's identity never appears on tenant A's shop, with B's row proven to exist
 *       (T-31.1-81), including when A has no identity of its own;</li>
 *   <li>a shop whose tenant has no identity carries NO seller — never an invented or blank one;</li>
 *   <li>a company number is shown only for a COMPANY;</li>
 *   <li>an unpublished shop's legal entity is not exposed (T-31.1-83);</li>
 *   <li>the shop LIST does not carry the seller (no per-row lookup, no N+1).</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: every seed runs in its own transaction with the
 * tenant GUC pinned on the same connection (the {@code TraderIdentityRlsIntegrationTest} recipe).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class PublicSellerIdentityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final String SHOP_PATH = "/api/v1/public/shops/";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Only a superuser may run ALTER ROLE, so this must happen exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses "
                        + "row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- 1. The tracer: the seller object on the public shop endpoint ---------------------------

    @Test
    @DisplayName("published shop of a tenant with an identity carries legalName, entityType, companyNumber, "
            + "vatNumber, addressLines, email and phone; TenantContext is empty after the request")
    void publishedShopCarriesTheSeller() throws Exception {
        UUID tenant = seedTenant();
        seedIdentity(tenant, "Mama Ade Foods Ltd", "COMPANY", "12 Market Street", "Unit 4", "Birmingham",
                "B1 1AA", "GB123456789");
        seedOnboarding(tenant, "16471464");
        String slug = seedShop(tenant, true, "orders@mama-ade.example.com", "0121 496 0000");

        mockMvc.perform(get(SHOP_PATH + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.seller.legalName").value("Mama Ade Foods Ltd"))
                .andExpect(jsonPath("$.seller.entityType").value("COMPANY"))
                .andExpect(jsonPath("$.seller.companyNumber").value("16471464"))
                .andExpect(jsonPath("$.seller.vatNumber").value("GB123456789"))
                .andExpect(jsonPath("$.seller.addressLines.length()").value(4))
                .andExpect(jsonPath("$.seller.addressLines[0]").value("12 Market Street"))
                .andExpect(jsonPath("$.seller.addressLines[1]").value("Unit 4"))
                .andExpect(jsonPath("$.seller.addressLines[2]").value("Birmingham"))
                .andExpect(jsonPath("$.seller.addressLines[3]").value("B1 1AA"))
                .andExpect(jsonPath("$.seller.email").value("orders@mama-ade.example.com"))
                .andExpect(jsonPath("$.seller.phone").value("0121 496 0000"))
                // the public seller object carries what the law requires the customer to see and
                // nothing else: no row id, no tenant, no version
                .andExpect(jsonPath("$.seller.id").doesNotExist())
                .andExpect(jsonPath("$.seller.tenantId").doesNotExist())
                .andExpect(jsonPath("$.seller.version").doesNotExist());

        assertThat(TenantContext.get())
                .as("T-31.1-82: the seller read pins the tenant transaction-locally and never leaves "
                        + "TenantContext set on the request thread")
                .isEmpty();
    }

    // ---- 2. Cross-tenant: B's identity never appears on A's shop ---------------------------------

    @Test
    @DisplayName("tenant B's legal name never appears on tenant A's shop (B's row proven to exist)")
    void anotherTenantsIdentityNeverAppears() throws Exception {
        UUID tenantA = seedTenant();
        UUID tenantB = seedTenant();
        seedIdentity(tenantA, "Alpha Kitchen Ltd", "COMPANY", "1 Alpha Road", null, "Leeds", "LS1 1AA", null);
        seedIdentity(tenantB, "Bravo Secret Trading Ltd", "COMPANY", "2 Bravo Road", null, "York", "YO1 7HH", null);
        String slugA = seedShop(tenantA, true, "a@alpha.example.com", null);
        // B also has a published shop, so B's identity is reachable through its OWN page: the
        // assertion below is about the pinning, not about B being unreadable everywhere.
        String slugB = seedShop(tenantB, true, "b@bravo.example.com", null);

        assertThat(this.<Long>inTenant(tenantB, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity WHERE legal_name = 'Bravo Secret Trading Ltd'")))
                .as("PRECONDITION: tenant B's identity row exists, so its absence below is the wall")
                .isEqualTo(1L);

        String bodyA = mockMvc.perform(get(SHOP_PATH + slugA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.legalName").value("Alpha Kitchen Ltd"))
                .andReturn().getResponse().getContentAsString();
        assertThat(bodyA).doesNotContain("Bravo Secret Trading Ltd").doesNotContain("2 Bravo Road");

        mockMvc.perform(get(SHOP_PATH + slugB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.legalName").value("Bravo Secret Trading Ltd"));
    }

    @Test
    @DisplayName("a tenant with NO identity never borrows another tenant's (B's row exists)")
    void aTenantWithoutIdentityNeverBorrowsAnother() throws Exception {
        UUID tenantA = seedTenant();
        UUID tenantB = seedTenant();
        seedIdentity(tenantB, "Charlie Hidden Foods Ltd", "COMPANY", "3 Charlie Road", null, "Hull", "HU1 1AA", null);
        String slugA = seedShop(tenantA, true, "a@nobody.example.com", null);

        assertThat(this.<Long>inTenant(tenantB, c -> queryLong(c,
                "SELECT count(*) FROM trader_identity WHERE legal_name = 'Charlie Hidden Foods Ltd'")))
                .as("PRECONDITION: tenant B's identity row exists")
                .isEqualTo(1L);

        String body = mockMvc.perform(get(SHOP_PATH + slugA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Charlie Hidden Foods Ltd");
    }

    // ---- 3. Absent identity is absent, never invented ----------------------------------------

    @Test
    @DisplayName("a published shop whose tenant has no identity carries no seller object at all")
    void noIdentityMeansNoSeller() throws Exception {
        UUID tenant = seedTenant();
        String slug = seedShop(tenant, true, "x@example.com", null);

        mockMvc.perform(get(SHOP_PATH + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.seller").doesNotExist());
        assertThat(TenantContext.get()).isEmpty();
    }

    // ---- 4. Company number only for a company ---------------------------------------------------

    @Test
    @DisplayName("a sole trader shows no company number even when onboarding holds one; no VAT, no phone -> absent")
    void soleTraderShowsNoCompanyNumber() throws Exception {
        UUID tenant = seedTenant();
        seedIdentity(tenant, "Ada Okafor", "SOLE_TRADER", "48 Rye Lane", null, "London", "SE15 5BS", null);
        seedOnboarding(tenant, "09999999");
        String slug = seedShop(tenant, true, "ada@example.com", null);

        mockMvc.perform(get(SHOP_PATH + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.legalName").value("Ada Okafor"))
                .andExpect(jsonPath("$.seller.entityType").value("SOLE_TRADER"))
                .andExpect(jsonPath("$.seller.addressLines.length()").value(3))
                .andExpect(jsonPath("$.seller.email").value("ada@example.com"))
                .andExpect(jsonPath("$.seller.companyNumber").doesNotExist())
                .andExpect(jsonPath("$.seller.vatNumber").doesNotExist())
                .andExpect(jsonPath("$.seller.phone").doesNotExist());
    }

    // ---- 5. Unpublished shops expose nothing ----------------------------------------------------

    @Test
    @DisplayName("an unpublished shop is 404 and its tenant's legal entity is not exposed")
    void unpublishedShopExposesNothing() throws Exception {
        UUID tenant = seedTenant();
        seedIdentity(tenant, "Delta Unpublished Ltd", "COMPANY", "4 Delta Road", null, "Bath", "BA1 1AA", null);
        String slug = seedShop(tenant, false, "d@delta.example.com", null);

        String body = mockMvc.perform(get(SHOP_PATH + slug))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Delta Unpublished Ltd");
    }

    // ---- 6. Lists do not carry it -------------------------------------------------------------

    @Test
    @DisplayName("the public shop list carries no seller on its entries (detail only: no per-row lookup)")
    void listEntriesCarryNoSeller() throws Exception {
        UUID tenant = seedTenant();
        seedIdentity(tenant, "Echo Listed Ltd", "COMPANY", "5 Echo Road", null, "Derby", "DE1 1AA", null);
        String slug = seedShop(tenant, true, "e@echo.example.com", null);

        // CONTROL: the same shop's detail DOES carry the seller, so the list's absence is a choice.
        mockMvc.perform(get(SHOP_PATH + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller.legalName").value("Echo Listed Ltd"));

        String list = mockMvc.perform(get("/api/v1/public/shops").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> entries = JsonPath.read(list, "$.content[?(@.slug == '" + slug + "')]");
        assertThat(entries).as("the shop is in the list (non-vacuity)").hasSize(1);
        assertThat(entries.get(0)).doesNotContainKey("seller");
        assertThat(list).doesNotContain("Echo Listed Ltd");
    }

    // ---- seeding ------------------------------------------------------------------------------

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        // tenants carries no RLS.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "789-seller-" + id);
        return id;
    }

    private void seedIdentity(UUID tenant, String legalName, String entityType, String line1, String line2,
                              String city, String postcode, String vat) {
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                        + "address_line2, address_city, address_postcode, vat_number) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), tenant.toString(), legalName, entityType, line1, line2, city,
                postcode, vat));
    }

    private void seedOnboarding(UUID tenant, String companyNumber) {
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO vendor_onboarding (id, tenant_id, model, company_number) "
                        + "VALUES (?::uuid, ?::uuid, 'MARKETPLACE', ?)",
                UUID.randomUUID().toString(), tenant.toString(), companyNumber));
    }

    private String seedShop(UUID tenant, boolean published, String email, String phone) {
        UUID id = UUID.randomUUID();
        String slug = "seller-789-" + id;
        this.<Integer>inTenant(tenant, c -> executeUpdate(c,
                "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, "
                        + "email, phone) VALUES (?::uuid, ?::uuid, ?, ?, 'Premises Address', ?, 0, ?, ?)",
                id.toString(), tenant.toString(), "Shop " + id, slug, published, email, phone));
        return slug;
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One fresh transaction with the tenant GUC pinned on the Hibernate transaction's own connection. */
    private <T> T inTenant(UUID tenant, ConnectionWork<T> work) {
        return new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                    return work.run(connection);
                }));
    }

    private static long queryLong(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
