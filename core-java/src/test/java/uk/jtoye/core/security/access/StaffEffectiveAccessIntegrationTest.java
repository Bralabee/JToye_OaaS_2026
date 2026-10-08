package uk.jtoye.core.security.access;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-09 (Phase 37-05, RWO-003): "who can do what" is a fact the SERVER computes, read from the same
 * decision {@link ShopAccessService} enforces with — never reconstructed from raw grant rows.
 *
 * <p>{@code GET /api/v1/staff} carries {@code people[]}: every {@code user_directory} row of the
 * tenant plus every grant holder that has no directory row, each with an {@code effectiveAccess}.
 * The arms here are chosen so that a list which derived the level from {@code shop_staff} rows
 * would answer differently from enforcement:
 * <ul>
 *   <li>an ungranted user who has only READ (called {@code staff/me}) is listed, as {@code NONE}
 *       — before 37-05 the directory upsert ran on write paths only, so such a user never
 *       appeared at all;</li>
 *   <li>a JIT tenant-wide GROUP_ADMIN row reads GROUP_ADMIN only for the tenant's oldest JIT admin
 *       (the D-06 bootstrap), and NONE for a younger one, although both rows say GROUP_ADMIN;</li>
 *   <li>a directory write that fails never fails the request that triggered it (T-37-11).</li>
 * </ul>
 *
 * <p>Runs on whatever strict-scoping value the context booted with (the D-06 default, ON); it never
 * touches the switch. Each test has its own random tenant. The Testcontainers role is a SUPERUSER,
 * so the direct SQL reads below see every row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StaffEffectiveAccessIntegrationTest {

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
    @Autowired private ShopStaffRepository shopStaffRepository;

    /** A spy over the real repository: every call goes to the database unless a test stubs it. */
    @MockitoSpyBean private UserDirectoryRepository userDirectoryRepository;

    private UUID tenantId;
    private UUID shopA;
    private UUID shopB;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-05 effective access " + tenantId);
        shopA = seedShop("A");
        shopB = seedShop("B");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1: the people list and the read-path directory touch ----------------------------

    @Test
    @DisplayName("D-09: a signed-in user with no grant appears in people[] with effectiveAccess NONE")
    void signedInUngrantedUser_isListed_asNone() throws Exception {
        UUID admin = operatorGroupAdmin();
        UUID ungranted = UUID.randomUUID();

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(ungranted))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);

        MvcResult list = staffList(vendorJwt(admin));
        JsonNode person = person(list, ungranted);
        assertThat(person).as("the ungranted reader is listed: %s", body(list)).isNotNull();
        assertThat(person.path("effectiveAccess").path("level").asString())
                .as("computed level for an ungranted user: %s", body(list))
                .isEqualTo("NONE");
        assertThat(person.path("maskedEmail").asString())
                .as("the email is masked exactly as the directory picker masks it (WR-10)")
                .isEqualTo("u***@example.com");

        // Additive change: the existing fields are still there.
        JsonNode root = json(list);
        assertThat(root.path("directory").isArray()).as(body(list)).isTrue();
        assertThat(root.path("grants").isArray()).as(body(list)).isTrue();
        assertThat(root.path("directory").toString()).as("the directory still lists the reader")
                .contains(ungranted.toString());
    }

    @Test
    @DisplayName("T-37-11: a directory write that fails does not fail the request that triggered it")
    void directoryWriteFailure_doesNotFailTheRequest() throws Exception {
        UUID reader = UUID.randomUUID();
        // A genuine database failure, not a thrown stub. The first statement aborts the PostgreSQL
        // transaction the write runs in; the next repository call on it then fails THROUGH Spring
        // Data's own transaction interceptor, which marks the transaction rollback-only — exactly
        // what a failing upsert does. (A bare thrown stub would skip that marking, and a
        // @Transactional-annotated toucher that catches inside its body would then pass this arm
        // while throwing UnexpectedRollbackException in production.)
        doAnswer(invocation -> {
            try {
                jdbc.execute("SELECT 1 / 0");
            } catch (DataAccessException aborted) {
                // the transaction is now aborted; the repository call below reports it
            }
            return (int) shopStaffRepository.count();
        }).when(userDirectoryRepository).recordSignIn(any(), eq(reader), any(), any(), anyBoolean(), any());

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(reader))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        verify(userDirectoryRepository, atLeastOnce()).recordSignIn(any(), eq(reader), any(), any(), anyBoolean(), any());
        assertThat(me.getResponse().getStatus())
                .as("a failed directory write is best-effort: %s", body(me))
                .isEqualTo(200);
        assertThat(json(me).path("userId").asString()).as(body(me)).isEqualTo(reader.toString());
        assertThat(directoryRows(reader)).as("the failed write left no row").isZero();
    }

    @Test
    @DisplayName("D-09: an OPERATOR tenant-wide GROUP_ADMIN reads level GROUP_ADMIN on all shops, not bootstrap")
    void operatorGroupAdmin_isGroupAdmin() throws Exception {
        UUID admin = operatorGroupAdmin();

        MvcResult list = staffList(vendorJwt(admin));
        JsonNode self = person(list, admin);
        assertThat(self).as("the admin is listed: %s", body(list)).isNotNull();
        JsonNode access = self.path("effectiveAccess");

        assertThat(access.path("level").asString()).as(access.toString()).isEqualTo("GROUP_ADMIN");
        assertThat(access.path("allShops").asBoolean()).as(access.toString()).isTrue();
        assertThat(access.path("bootstrapAdmin").asBoolean()).as(access.toString()).isFalse();
    }

    @Test
    @DisplayName("D-06/D-09: with only JIT admins, the oldest reads GROUP_ADMIN (bootstrap) and a younger one NONE")
    void jitAdmins_bootstrapIsGroupAdmin_youngerIsNone() throws Exception {
        UUID oldest = UUID.randomUUID();
        UUID younger = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        seedJitGroupAdmin(oldest, base);
        seedJitGroupAdmin(younger, base.plusDays(1));
        UUID realmAdmin = UUID.randomUUID();   // reads the list without adding a shop_staff row

        MvcResult list = staffList(realmAdminJwt(realmAdmin));

        JsonNode bootstrap = person(list, oldest);
        assertThat(bootstrap).as("a grant holder with no directory row is listed: %s", body(list)).isNotNull();
        assertThat(bootstrap.path("effectiveAccess").path("level").asString()).as(body(list))
                .isEqualTo("GROUP_ADMIN");
        assertThat(bootstrap.path("effectiveAccess").path("bootstrapAdmin").asBoolean()).as(body(list))
                .isTrue();
        assertThat(bootstrap.path("maskedEmail").isNull())
                .as("no directory row, so no email to show: %s", bootstrap)
                .isTrue();

        JsonNode second = person(list, younger);
        assertThat(second).as(body(list)).isNotNull();
        assertThat(second.path("effectiveAccess").path("level").asString())
                .as("a younger JIT admin is de-honoured, whatever its row says: %s", body(list))
                .isEqualTo("NONE");
    }

    // ---- Task 2: every grant shape means what it says, realm admins included -----------------

    @Test
    @DisplayName("D-07/D-23: a NULL-shop SHOP_MANAGER grant writes on every tenant shop and reads SHOP_ROLES, all shops")
    void tenantWideShopManager_writesOnEveryShop() throws Exception {
        UUID admin = operatorGroupAdmin();
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, manager, null, "SHOP_MANAGER", "manager-" + manager + "@example.com");

        for (UUID shop : List.of(shopA, shopB)) {
            MvcResult write = mockMvc.perform(post("/api/v1/products").with(vendorJwt(manager))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(productBody("TW-MGR", shop))))
                    .andReturn();
            assertThat(write.getResponse().getStatus())
                    .as("a tenant-wide SHOP_MANAGER writes on shop %s: %s", shop, body(write))
                    .isEqualTo(201);
        }

        JsonNode access = listedAccess(vendorJwt(admin), manager);
        assertThat(access.path("level").asString()).as(access.toString()).isEqualTo("SHOP_ROLES");
        assertThat(access.path("tenantWideRole").asString()).as(access.toString()).isEqualTo("SHOP_MANAGER");
        assertThat(access.path("allShops").asBoolean()).as(access.toString()).isTrue();
    }

    @Test
    @DisplayName("T-37-10: a NULL-shop STAFF grant reads every tenant shop's orders and is refused a product write")
    void tenantWideStaff_readsEveryShop_butCannotWrite() throws Exception {
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, staff, null, "STAFF", "staff-" + staff + "@example.com");

        for (UUID shop : List.of(shopA, shopB)) {
            MvcResult read = mockMvc.perform(get("/api/v1/orders/shop/" + shop).with(vendorJwt(staff))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();
            assertThat(read.getResponse().getStatus())
                    .as("a tenant-wide STAFF reads shop %s's orders: %s", shop, body(read))
                    .isEqualTo(200);
        }

        MvcResult write = mockMvc.perform(post("/api/v1/products").with(vendorJwt(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("TW-STAFF", shopA))))
                .andReturn();
        assertThat(write.getResponse().getStatus())
                .as("STAFF < SHOP_MANAGER: the tenant-wide role caps at its own rank: %s", body(write))
                .isEqualTo(403);
        assertThat(json(write).path("type").asString()).as(body(write)).isEqualTo(SHOP_ACCESS_DENIED);
    }

    @Test
    @DisplayName("D-07: staff/me for a NULL-shop STAFF user says groupAdmin=false, tenantWideRole STAFF, every tenant shop")
    void tenantWideStaff_staffMe_listsEveryTenantShop() throws Exception {
        UUID staff = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, staff, null, "STAFF", "staff-" + staff + "@example.com");

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(staff))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);
        JsonNode access = json(me);
        assertThat(access.path("groupAdmin").asBoolean()).as(body(me)).isFalse();
        assertThat(access.path("tenantWideRole").asString()).as(body(me)).isEqualTo("STAFF");
        List<String> shops = new java.util.ArrayList<>();
        access.path("grantedShopIds").forEach(id -> shops.add(id.asString()));
        assertThat(shops).as(body(me)).containsExactlyInAnyOrder(shopA.toString(), shopB.toString());
    }

    @Test
    @DisplayName("T-37-10: a tenant-wide role stops at the tenant wall — no write on another tenant's shop")
    void tenantWideShopManager_cannotWriteAnotherTenantsShop() throws Exception {
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, manager, null, "SHOP_MANAGER", "manager-" + manager + "@example.com");
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                otherTenant, "37-05 other tenant " + otherTenant);
        UUID foreignShop = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                foreignShop, otherTenant, "Foreign " + foreignShop, "fx-" + foreignShop, "2 Test Street, London, E1 6AN");

        MvcResult write = mockMvc.perform(post("/api/v1/products").with(vendorJwt(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("TW-FOREIGN", foreignShop))))
                .andReturn();

        assertThat(write.getResponse().getStatus())
                .as("a published foreign shop is not one of 'all shops': %s", body(write))
                .isIn(403, 404);
        Long written = jdbc.queryForObject("SELECT count(*) FROM products WHERE shop_id = ?", Long.class, foreignShop);
        assertThat(written).as("nothing was written to the foreign shop").isZero();
    }

    @Test
    @DisplayName("Pitfall 13: a realm admin is stamped at sign-in and reads REALM_ADMIN, even on a fresh directory row")
    void realmAdmin_isStamped_andReadsRealmAdmin() throws Exception {
        UUID admin = operatorGroupAdmin();
        UUID platformAdmin = UUID.randomUUID();
        UUID ordinary = UUID.randomUUID();

        // First seen WITHOUT the role (a fresh row, inside the throttle window), then with it.
        mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(platformAdmin))).andReturn();
        mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(ordinary))).andReturn();
        MvcResult asAdmin = mockMvc.perform(get("/api/v1/staff/me").with(realmAdminJwt(platformAdmin))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(asAdmin.getResponse().getStatus()).as(body(asAdmin)).isEqualTo(200);

        JsonNode access = listedAccess(vendorJwt(admin), platformAdmin);
        assertThat(access.path("level").asString()).as("not 'No access': %s", access).isEqualTo("REALM_ADMIN");
        assertThat(access.path("allShops").asBoolean()).as(access.toString()).isTrue();
        assertThat(access.path("realmAdminSeenAt").isNull()).as(access.toString()).isFalse();

        assertThat(realmAdminSeenAt(platformAdmin)).as("stamped in user_directory").isNotNull();
        assertThat(realmAdminSeenAt(ordinary)).as("never seen with the role: NULL").isNull();
        assertThat(listedAccess(vendorJwt(admin), ordinary).path("level").asString()).isEqualTo("NONE");
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

    private JsonNode listedAccess(RequestPostProcessor caller, UUID userId) throws Exception {
        MvcResult list = staffList(caller);
        JsonNode p = person(list, userId);
        assertThat(p).as("%s is listed: %s", userId, body(list)).isNotNull();
        return p.path("effectiveAccess");
    }

    private Object realmAdminSeenAt(UUID user) {
        return jdbc.queryForObject("SELECT realm_admin_seen_at FROM user_directory WHERE tenant_id = ? AND user_id = ?",
                Object.class, tenantId, user);
    }

    private Map<String, Object> productBody(String skuPrefix, UUID shop) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("sku", skuPrefix + "-" + UUID.randomUUID());
        body.put("title", "Jollof Rice");
        body.put("ingredientsText", "Rice, tomato, pepper");
        body.put("allergenMask", 0);
        body.put("pricePennies", 799);
        body.put("category", "Mains");
        body.put("available", true);
        body.put("shopId", shop);
        return body;
    }

    private UUID operatorGroupAdmin() {
        UUID admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
        return admin;
    }

    private UUID seedShop(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                id, tenantId, "Effective Access " + label + " " + id, "ea-" + id, "1 Test Street, London, E1 6AN");
        return id;
    }

    private void seedJitGroupAdmin(UUID user, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', 'JIT', ?)",
                UUID.randomUUID(), tenantId, user, createdAt);
    }

    private MvcResult staffList(RequestPostProcessor caller) throws Exception {
        MvcResult list = mockMvc.perform(get("/api/v1/staff").with(caller).accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(list.getResponse().getStatus()).as("staff list: %s", body(list)).isEqualTo(200);
        return list;
    }

    /** The people[] entry for {@code userId}, or null when the list does not carry one. */
    private JsonNode person(MvcResult list, UUID userId) throws Exception {
        for (JsonNode p : json(list).path("people")) {
            if (userId.toString().equals(p.path("userId").asString())) {
                return p;
            }
        }
        return null;
    }

    private long directoryRows(UUID user) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM user_directory WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, user);
        return n == null ? 0 : n;
    }

    private RequestPostProcessor vendorJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("name", "Vendor " + sub)
                        .claim("realm_access", Map.of("roles", List.of("user")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private RequestPostProcessor realmAdminJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "admin-" + sub + "@example.com")
                        .claim("name", "Realm Admin " + sub)
                        .claim("realm_access", Map.of("roles", List.of("admin")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }
}
