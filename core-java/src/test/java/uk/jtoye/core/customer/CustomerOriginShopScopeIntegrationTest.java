package uk.jtoye.core.customer;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 39-08 (RWO-CUST-OPT-C): Option C per-shop customer narrowing.
 *
 * <p>Proves:
 * <ol>
 *   <li>Flyway {@code V85__customer_origin_shop_scope.sql} attributes existing customers to their
 *       earliest order's shop across multiple tenants under a {@code NOSUPERUSER NOBYPASSRLS}
 *       migration role, leaves order-less customers {@code NULL}, mirrors {@code origin_shop_id} on
 *       {@code customers_aud}, and sets {@code origin_shop_id = NULL} on shop deletion
 *       ({@code ON DELETE SET NULL}).</li>
 *   <li>Callers without {@code allShops} access (per-shop {@code STAFF} or {@code SHOP_MANAGER}) see
 *       and act only on customers where {@code origin_shop_id IN (:grantedShopIds)} OR the customer
 *       has an order at a granted shop; out-of-scope customer IDs return {@code 404} (never {@code 403},
 *       preventing an existence oracle).</li>
 *   <li>{@code POST /api/v1/customers} by a per-shop {@code SHOP_MANAGER} defaults {@code originShopId}
 *       to the caller's deterministic managed shop when omitted, accepts an explicit granted shop, and
 *       refuses an ungranted {@code originShopId} with {@code 403 shop-access-denied}.</li>
 *   <li>{@code OrderService.createOrder} and {@code updateOrder} refuse an out-of-scope
 *       {@code customerId} with {@code 404} for a per-shop manager, and {@code createOrder} backfills
 *       {@code origin_shop_id} on a customer whose {@code origin_shop_id} was {@code NULL}.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CustomerOriginShopScopeIntegrationTest {

    private static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";
    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String RLS_MIGRATOR = "rls_v85_migrator";
    private static final String RLS_MIGRATOR_PW = "rls_v85_migrator_pw";

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
    @Autowired private JsonMapper jsonMapper;
    @Autowired private ShopAccessService shopAccessService;

    private Object bootedStrictScoping;

    @BeforeEach
    void captureStrictScoping() {
        bootedStrictScoping = StrictScopingGuard.capture(shopAccessService);
        StrictScopingGuard.set(shopAccessService, true);
    }

    @AfterEach
    void restoreStrictScoping() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
    }

    record MigrationTenantSeed(UUID tenant, UUID shopX, UUID shopY, UUID shopZ,
                               UUID custEarliestX, UUID custOnlyY, UUID custOrderless, UUID custAtZ) {}

    @Test
    @DisplayName("V85 backfills origin_shop_id from earliest order across tenants under NOSUPERUSER NOBYPASSRLS and honours ON DELETE SET NULL")
    void v85BackfillsOriginShopIdFromEarliestOrderAcrossTenantsUnderRls() throws Exception {
        String migDb = "jtoye_v85_mig";
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + migDb);
            s.execute("CREATE DATABASE " + migDb);
        }
        String migUrl = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?.*)?$", "/" + migDb + "$1");

        Flyway toV84 = flywayConfig(migUrl, postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("84"))
                .load();
        toV84.migrate();
        assertThat(appliedVersions(toV84)).as("V84 applied, V85 not yet").contains("84").doesNotContain("85");

        MigrationTenantSeed t1;
        MigrationTenantSeed t2;
        try (Connection su = DriverManager.getConnection(migUrl, postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + RLS_MIGRATOR);
            s.execute("CREATE ROLE " + RLS_MIGRATOR
                    + " NOSUPERUSER NOBYPASSRLS LOGIN PASSWORD '" + RLS_MIGRATOR_PW + "'");
            s.execute("GRANT ALL ON SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_MIGRATOR);
            s.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO " + RLS_MIGRATOR);
            for (String table : new String[]{"customers", "customers_aud"}) {
                s.execute("ALTER TABLE " + table + " OWNER TO " + RLS_MIGRATOR);
            }

            t1 = seedMigrationTenant(su, "1");
            t2 = seedMigrationTenant(su, "2");

            assertThat(columnCount(su, "customers", "origin_shop_id"))
                    .as("pre-V85: origin_shop_id must not exist yet").isZero();
        }

        Flyway toV85 = flywayConfig(migUrl, RLS_MIGRATOR, RLS_MIGRATOR_PW)
                .target(MigrationVersion.fromVersion("85"))
                .load();
        var result = toV85.migrate();
        assertThat(result.migrationsExecuted).as("exactly V85 applied on top of V84").isEqualTo(1);
        assertThat(result.migrations.get(0).version).isEqualTo("85");

        try (Connection su = DriverManager.getConnection(migUrl, postgres.getUsername(), postgres.getPassword());
             Statement s = su.createStatement()) {
            for (MigrationTenantSeed t : new MigrationTenantSeed[]{t1, t2}) {
                String tag = "tenant " + t.tenant() + ": ";
                assertThat(originShopOf(su, t.custEarliestX()))
                        .as(tag + "customer with earliest order at X and later order at Y -> X")
                        .isEqualTo(t.shopX());
                assertThat(originShopOf(su, t.custOnlyY()))
                        .as(tag + "customer with order only at Y -> Y")
                        .isEqualTo(t.shopY());
                assertThat(originShopOf(su, t.custOrderless()))
                        .as(tag + "order-less customer stays NULL")
                        .isNull();
            }

            assertThat(columnCount(su, "customers", "origin_shop_id")).isEqualTo(1);
            assertThat(columnCount(su, "customers_aud", "origin_shop_id")).isEqualTo(1);

            // Prove ON DELETE SET NULL on customers.origin_shop_id -> shops(id)
            try (PreparedStatement ps = su.prepareStatement("UPDATE customers SET origin_shop_id = ? WHERE id = ?")) {
                ps.setObject(1, t1.shopZ());
                ps.setObject(2, t1.custAtZ());
                ps.executeUpdate();
            }
            assertThat(originShopOf(su, t1.custAtZ())).isEqualTo(t1.shopZ());
            try (PreparedStatement ps = su.prepareStatement("DELETE FROM shops WHERE id = ?")) {
                ps.setObject(1, t1.shopZ());
                ps.executeUpdate();
            }
            assertThat(originShopOf(su, t1.custAtZ()))
                    .as("deleting a shop nulls customers.origin_shop_id via ON DELETE SET NULL")
                    .isNull();
        }
    }

    @Test
    @DisplayName("Option C: per-shop STAFF and SHOP_MANAGER see only customers with origin_shop_id or an order in their grant set; out-of-scope ID is 404")
    void perShopStaffAndManager_listAndGetById_narrowedToOriginOrOrderShop_outOfScopeIs404() throws Exception {
        UUID tenant = seedTenant();
        UUID shopA = seedShop(tenant, "shop-a");
        UUID shopB = seedShop(tenant, "shop-b");

        UUID custOriginA = seedCustomerWithOrigin(tenant, "Origin A", "origina-" + UUID.randomUUID() + "@example.com", shopA);
        UUID custOrderedAtA = seedCustomerWithOrigin(tenant, "Ordered At A", "ordereda-" + UUID.randomUUID() + "@example.com", shopB);
        seedOrderForCustomer(tenant, shopA, custOrderedAtA);
        UUID custOnlyB = seedCustomerWithOrigin(tenant, "Only B", "onlyb-" + UUID.randomUUID() + "@example.com", shopB);
        seedOrderForCustomer(tenant, shopB, custOnlyB);
        UUID custNullNoOrders = seedCustomerWithOrigin(tenant, "Shop-less Legacy", "legacy-" + UUID.randomUUID() + "@example.com", null);

        UUID staffA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, staffA, shopA, "STAFF", "staffa-" + staffA + "@example.com");

        UUID managerA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, managerA, shopA, "SHOP_MANAGER", "managera-" + managerA + "@example.com");

        UUID groupAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, groupAdmin, null, "GROUP_ADMIN", "ga-" + groupAdmin + "@example.com");

        UUID twStaff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, twStaff, null, "STAFF", "twstaff-" + twStaff + "@example.com");

        // 1. List as per-shop STAFF on shopA -> sees custOriginA and custOrderedAtA, NOT custOnlyB or custNullNoOrders
        MvcResult listStaffA = mockMvc.perform(get("/api/v1/customers").with(vendor(tenant, staffA))).andReturn();
        assertThat(listStaffA.getResponse().getStatus()).as(body(listStaffA)).isEqualTo(200);
        String listStaffBody = listStaffA.getResponse().getContentAsString();
        assertThat(listStaffBody).contains(custOriginA.toString(), custOrderedAtA.toString());
        assertThat(listStaffBody).doesNotContain(custOnlyB.toString(), custNullNoOrders.toString(), "Only B", "Shop-less Legacy");

        // 2. getById as per-shop STAFF on shopA -> 200 for in-scope, 404 (not 403) for out-of-scope
        MvcResult getOriginA = mockMvc.perform(get("/api/v1/customers/" + custOriginA).with(vendor(tenant, staffA))).andReturn();
        assertThat(getOriginA.getResponse().getStatus()).as(body(getOriginA)).isEqualTo(200);

        MvcResult getOrderedAtA = mockMvc.perform(get("/api/v1/customers/" + custOrderedAtA).with(vendor(tenant, staffA))).andReturn();
        assertThat(getOrderedAtA.getResponse().getStatus()).as(body(getOrderedAtA)).isEqualTo(200);

        MvcResult getOnlyB = mockMvc.perform(get("/api/v1/customers/" + custOnlyB).with(vendor(tenant, staffA))).andReturn();
        assertNotFoundWithoutLeak(getOnlyB, "Only B");

        MvcResult getNull = mockMvc.perform(get("/api/v1/customers/" + custNullNoOrders).with(vendor(tenant, staffA))).andReturn();
        assertNotFoundWithoutLeak(getNull, "Shop-less Legacy");

        // 3. allShops callers (GROUP_ADMIN and tenant-wide STAFF) see all 4 customers
        for (UUID allShopsCaller : List.of(groupAdmin, twStaff)) {
            MvcResult listAll = mockMvc.perform(get("/api/v1/customers").with(vendor(tenant, allShopsCaller))).andReturn();
            assertThat(listAll.getResponse().getStatus()).as(body(listAll)).isEqualTo(200);
            String allBody = listAll.getResponse().getContentAsString();
            assertThat(allBody).contains(
                    custOriginA.toString(),
                    custOrderedAtA.toString(),
                    custOnlyB.toString(),
                    custNullNoOrders.toString());
        }
    }

    @Test
    @DisplayName("Option C: per-shop SHOP_MANAGER create defaults originShopId to managed shop, accepts granted shop, and refuses ungranted shop with 403")
    void perShopManager_createCustomer_defaultsToGrantedShop_orRespectsExplicitOriginShop_andRefusesForeignShop() throws Exception {
        UUID tenant = seedTenant();
        UUID shopA = seedShop(tenant, "create-a");
        UUID shopB = seedShop(tenant, "create-b");

        UUID managerA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, managerA, shopA, "SHOP_MANAGER", "mgr-a-" + managerA + "@example.com");

        // 1. Omitted originShopId -> defaults to shopA so managerA can immediately read and update the customer
        String emailDefault = "default-origin-" + UUID.randomUUID() + "@example.com";
        MvcResult createdDefault = mockMvc.perform(post("/api/v1/customers").with(vendor(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Default Origin\",\"email\":\"" + emailDefault + "\",\"phone\":\"07700900111\"}"))
                .andReturn();
        assertThat(createdDefault.getResponse().getStatus()).as(body(createdDefault)).isEqualTo(201);
        JsonNode createdNode = jsonMapper.readTree(createdDefault.getResponse().getContentAsString());
        UUID createdId = UUID.fromString(createdNode.path("id").asString());
        assertThat(createdNode.path("originShopId").asString()).isEqualTo(shopA.toString());

        MvcResult readBack = mockMvc.perform(get("/api/v1/customers/" + createdId).with(vendor(tenant, managerA))).andReturn();
        assertThat(readBack.getResponse().getStatus()).as(body(readBack)).isEqualTo(200);

        // 2. Explicit originShopId = shopB (not granted to managerA) -> 403 shop-access-denied, writes nothing
        String emailRefused = "refused-origin-" + UUID.randomUUID() + "@example.com";
        MvcResult refused = mockMvc.perform(post("/api/v1/customers").with(vendor(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Refused Origin\",\"email\":\"" + emailRefused + "\",\"originShopId\":\"" + shopB + "\"}"))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(403);
        assertThat(jsonMapper.readTree(refused.getResponse().getContentAsString()).path("type").asString())
                .isEqualTo(SHOP_ACCESS_DENIED);
        Long count = jdbc.queryForObject("SELECT count(*) FROM customers WHERE tenant_id = ? AND email = ?",
                Long.class, tenant, emailRefused);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("Option C: per-shop SHOP_MANAGER update on out-of-scope customer returns 404 and leaves row unchanged; in-scope update succeeds")
    void perShopManager_updateCustomer_outOfScopeIs404_inScopeIs200() throws Exception {
        UUID tenant = seedTenant();
        UUID shopA = seedShop(tenant, "upd-a");
        UUID shopB = seedShop(tenant, "upd-b");

        UUID custAtA = seedCustomerWithOrigin(tenant, "Customer A", "custa-" + UUID.randomUUID() + "@example.com", shopA);
        UUID custAtB = seedCustomerWithOrigin(tenant, "Customer B", "custb-" + UUID.randomUUID() + "@example.com", shopB);

        UUID managerA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, managerA, shopA, "SHOP_MANAGER", "mgr-upd-" + managerA + "@example.com");

        MvcResult outOfScope = mockMvc.perform(put("/api/v1/customers/" + custAtB).with(vendor(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked B\",\"email\":\"hijacked-" + UUID.randomUUID() + "@example.com\"}"))
                .andReturn();
        assertNotFoundWithoutLeak(outOfScope, "Customer B");
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, custAtB))
                .isEqualTo("Customer B");

        String updatedEmail = "updated-a-" + UUID.randomUUID() + "@example.com";
        MvcResult inScope = mockMvc.perform(put("/api/v1/customers/" + custAtA).with(vendor(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated A\",\"email\":\"" + updatedEmail + "\"}"))
                .andReturn();
        assertThat(inScope.getResponse().getStatus()).as(body(inScope)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, custAtA))
                .isEqualTo("Updated A");
    }

    @Test
    @DisplayName("Option C: OrderService.createOrder and updateOrder refuse out-of-scope customerId with 404, and createOrder backfills NULL origin_shop_id")
    void orderService_createAndUpdateOrder_outOfScopeCustomerIs404_andFirstOrderBackfillsNullOriginShop() throws Exception {
        UUID tenant = seedTenant();
        UUID shopA = seedShop(tenant, "ord-a");
        UUID shopB = seedShop(tenant, "ord-b");
        UUID productA = seedProduct(tenant, shopA);

        UUID custOnlyB = seedCustomerWithOrigin(tenant, "Secret B Customer", "secretb-" + UUID.randomUUID() + "@example.com", shopB);
        UUID custOriginA = seedCustomerWithOrigin(tenant, "Known A Customer", "knowna-" + UUID.randomUUID() + "@example.com", shopA);
        UUID custNullOrigin = seedCustomerWithOrigin(tenant, "Unassigned Customer", "unassigned-" + UUID.randomUUID() + "@example.com", null);

        UUID managerA = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, managerA, shopA, "SHOP_MANAGER", "mgr-ord-" + managerA + "@example.com");

        UUID groupAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenant, groupAdmin, null, "GROUP_ADMIN", "ga-ord-" + groupAdmin + "@example.com");

        // 1. Per-shop managerA creating an order at shopA with custOnlyB -> 404 (no PII leak)
        String createOrderWithB = "{\"shopId\":\"" + shopA + "\",\"customerId\":\"" + custOnlyB + "\","
                + "\"items\":[{\"productId\":\"" + productA + "\",\"quantity\":1}]}";
        MvcResult createRefused = mockMvc.perform(post("/api/v1/orders").with(vendorWithOrderScopes(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderWithB))
                .andReturn();
        assertNotFoundWithoutLeak(createRefused, "Secret B Customer");

        // 2. Per-shop managerA creates an order at shopA with custOriginA -> 201, then tries updateOrder to link custOnlyB -> 404
        String createOrderWithA = "{\"shopId\":\"" + shopA + "\",\"customerId\":\"" + custOriginA + "\","
                + "\"items\":[{\"productId\":\"" + productA + "\",\"quantity\":1}]}";
        MvcResult createdOrder = mockMvc.perform(post("/api/v1/orders").with(vendorWithOrderScopes(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderWithA))
                .andReturn();
        assertThat(createdOrder.getResponse().getStatus()).as(body(createdOrder)).isEqualTo(201);
        UUID orderId = UUID.fromString(jsonMapper.readTree(createdOrder.getResponse().getContentAsString()).path("id").asString());

        MvcResult updateRefused = mockMvc.perform(put("/api/v1/orders/" + orderId).with(vendorWithOrderScopes(tenant, managerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + custOnlyB + "\",\"notes\":\"attempt link B\"}"))
                .andReturn();
        assertNotFoundWithoutLeak(updateRefused, "Secret B Customer");

        // 3. Group admin creates first order at shopA for custNullOrigin -> 201 and backfills origin_shop_id = shopA
        String createOrderForNullOrigin = "{\"shopId\":\"" + shopA + "\",\"customerId\":\"" + custNullOrigin + "\","
                + "\"items\":[{\"productId\":\"" + productA + "\",\"quantity\":1}]}";
        MvcResult firstOrder = mockMvc.perform(post("/api/v1/orders").with(vendorWithOrderScopes(tenant, groupAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createOrderForNullOrigin))
                .andReturn();
        assertThat(firstOrder.getResponse().getStatus()).as(body(firstOrder)).isEqualTo(201);
        UUID backfilledOrigin = jdbc.queryForObject(
                "SELECT origin_shop_id FROM customers WHERE id = ?", UUID.class, custNullOrigin);
        assertThat(backfilledOrigin)
                .as("first order for a customer with NULL origin_shop_id stamps the order's shop")
                .isEqualTo(shopA);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID seedTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "optc-tenant-" + tenant);
        return tenant;
    }

    private UUID seedShop(UUID tenant, String prefix) {
        UUID shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shopId, tenant, "Shop " + prefix, prefix + "-" + shopId, "1 Test Street, London, E1 6AN");
        return shopId;
    }

    private UUID seedProduct(UUID tenant, UUID shopId) {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, shop_id, sku, title, ingredients_text, allergen_mask, "
                        + "price_pennies, vat_rate, available, featured, display_order, created_at) "
                        + "VALUES (?, ?, ?, ?, 'Test Dish', 'Rice, water', 0, 1000, 'STANDARD', true, false, 0, NOW())",
                productId, tenant, shopId, "SKU-" + productId.toString().substring(0, 8));
        return productId;
    }

    private UUID seedCustomerWithOrigin(UUID tenant, String name, String email, UUID originShopId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, origin_shop_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, '07700900000', 0, ?, NOW(), NOW())",
                id, tenant, name, email, originShopId);
        return id;
    }

    private UUID seedOrderForCustomer(UUID tenant, UUID shopId, UUID customerId) {
        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'Seeded', 'seeded@example.com', "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, NOW(), NOW())",
                orderId, tenant, shopId, customerId, "ORD-" + orderId.toString().substring(0, 8));
        return orderId;
    }

    private void assertNotFoundWithoutLeak(MvcResult result, String forbiddenSubstring) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("expected 404, got: %s", body(result)).isEqualTo(404);
        assertThat(jsonMapper.readTree(content).path("type").asString()).isEqualTo(NOT_FOUND_TYPE);
        assertThat(content).doesNotContain(forbiddenSubstring);
    }

    private static RequestPostProcessor vendor(UUID tenant, UUID sub) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_customers:write"));
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("azp", "core-api")
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "user-" + sub + "@example.com"))
                .authorities(authorities);
    }

    private static RequestPostProcessor vendorWithOrderScopes(UUID tenant, UUID sub) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("SCOPE_customers:write"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_orders:write"));
        authorities.add(new SimpleGrantedAuthority("SCOPE_orders:read"));
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("azp", "core-api")
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "user-" + sub + "@example.com"))
                .authorities(authorities);
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }

    private static MigrationTenantSeed seedMigrationTenant(Connection su, String n) throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (PreparedStatement ps = su.prepareStatement("INSERT INTO tenants (id, name) VALUES (?, ?)")) {
            ps.setObject(1, tenant);
            ps.setString(2, "V85 Tenant " + n);
            ps.executeUpdate();
        }
        UUID shopX = seedMigShop(su, tenant, "v85-x-" + n + "-" + tenant.toString().substring(0, 8));
        UUID shopY = seedMigShop(su, tenant, "v85-y-" + n + "-" + tenant.toString().substring(0, 8));
        UUID shopZ = seedMigShop(su, tenant, "v85-z-" + n + "-" + tenant.toString().substring(0, 8));

        UUID custEarliestX = seedMigCustomer(su, tenant, "earx-" + n + "@example.test");
        UUID custOnlyY = seedMigCustomer(su, tenant, "onlyy-" + n + "@example.test");
        UUID custOrderless = seedMigCustomer(su, tenant, "noord-" + n + "@example.test");
        UUID custAtZ = seedMigCustomer(su, tenant, "atz-" + n + "@example.test");

        Instant t0 = Instant.parse("2026-01-10T10:00:00Z");
        Instant t1 = Instant.parse("2026-01-12T10:00:00Z");
        seedMigOrder(su, tenant, shopX, custEarliestX, Timestamp.from(t0));
        seedMigOrder(su, tenant, shopY, custEarliestX, Timestamp.from(t1));
        seedMigOrder(su, tenant, shopY, custOnlyY, Timestamp.from(t1));
        return new MigrationTenantSeed(tenant, shopX, shopY, shopZ, custEarliestX, custOnlyY, custOrderless, custAtZ);
    }

    private static UUID seedMigShop(Connection c, UUID tenant, String slug) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, "Shop " + slug);
            ps.setString(4, slug);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedMigCustomer(Connection c, UUID tenant, String email) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, created_at, updated_at) "
                        + "VALUES (?, ?, 'Mig Customer', ?, '07700900000', 0, now(), now())")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setString(3, email);
            ps.executeUpdate();
        }
        return id;
    }

    private static void seedMigOrder(Connection c, UUID tenant, UUID shop, UUID customerId, Timestamp createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, customer_name, customer_email, "
                        + "subtotal_pennies, vat_rate, vat_amount_pennies, total_amount_pennies, delivery_fee_pennies, "
                        + "item_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', 'V85 Customer', 'v85@example.test', "
                        + "1000, 'STANDARD', 200, 1200, 0, 1, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, tenant);
            ps.setObject(3, shop);
            ps.setObject(4, customerId);
            ps.setString(5, "ORD-V85-" + id.toString().substring(0, 8));
            ps.setTimestamp(6, createdAt);
            ps.setTimestamp(7, createdAt);
            ps.executeUpdate();
        }
    }

    private static UUID originShopOf(Connection c, UUID customerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT origin_shop_id FROM customers WHERE id = ?")) {
            ps.setObject(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    private static int columnCount(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static FluentConfiguration flywayConfig(String url, String user, String password) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration");
    }

    private static List<String> appliedVersions(Flyway flyway) {
        return Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }
}
