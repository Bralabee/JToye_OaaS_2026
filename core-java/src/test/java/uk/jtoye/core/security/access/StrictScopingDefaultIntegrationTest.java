package uk.jtoye.core.security.access;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.ShopGrants;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * UXT-004 (#780) acceptance under the SHIPPED default (Phase 37-04, D-06, threat T-37-06).
 *
 * <p>D-06: ungranted means no access. Every case below runs with whatever value the context booted
 * with — this class never touches {@code strictScoping} (no {@code StrictScopingGuard}, no
 * reflection, no {@code @TestPropertySource}, no env override). It is therefore a statement about
 * the default itself: with {@code ACCESS_STRICT_SCOPING} unset,
 * <ul>
 *   <li>a vendor token with no {@code shop_staff} row is refused a shop write with the typed
 *       {@code shop-access-denied} 403, and no JIT row is written for it;</li>
 *   <li>the same token's {@code GET /api/v1/staff/me} says {@code groupAdmin=false} with an empty
 *       {@code grantedShopIds};</li>
 *   <li>an OPERATOR tenant-wide GROUP_ADMIN and a realm admin still write (201);</li>
 *   <li>in a tenant whose only tenant-wide GROUP_ADMINs are JIT rows, the OLDEST is kept as the
 *       WARN-logged bootstrap admin (the V57 off-ramp: no tenant locks itself out) and a younger
 *       JIT admin is refused (T-37-08).</li>
 * </ul>
 *
 * <p>Run with {@code ACCESS_STRICT_SCOPING=false} exported, every refusal arm here goes red — which
 * is the point: the class pins the default, not the mechanism ({@code StrictScopingTighteningIntegrationTest}
 * pins the mechanism with the switch set explicitly).
 *
 * <p>Each test uses its own random tenant and shop, so an OPERATOR admin seeded for one case cannot
 * change the bootstrap rule in another. The Testcontainers role is a SUPERUSER, so the
 * {@code shop_staff} counts here see every row; the operator arm's count of 1 is the positive
 * control proving the counting query can see a row at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class StrictScopingDefaultIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    private UUID tenantId;
    private UUID shopId;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "37-04 default tenant " + tenantId);
        shopId = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                shopId, tenantId, "Default Kitchen " + shopId, "d06-" + shopId, "1 Test Street, London, E1 6AN");
        serviceLogger = (Logger) LoggerFactory.getLogger(ShopAccessService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-004: an ungranted vendor is refused a shop write (403 shop-access-denied) and gets no JIT row")
    void ungrantedVendor_isRefusedAShopWrite_andNoJitRowIsWritten() throws Exception {
        UUID ungranted = UUID.randomUUID();
        // Positive control for the counting query: a granted user in the same tenant (a per-shop
        // grant, not tenant-wide, so it cannot change the ungranted user's decision).
        UUID control = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, control, shopId, "SHOP_MANAGER", "control-" + control + "@example.com");

        MvcResult write = mockMvc.perform(post("/api/v1/products")
                        .with(vendorJwt(ungranted))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("D06-UNGRANTED"))))
                .andReturn();

        assertThat(write.getResponse().getStatus())
                .as("ungranted vendor shop write under the shipped default: %s", body(write))
                .isEqualTo(403);
        assertThat(json(write).path("type").asString())
                .as("typed refusal: %s", body(write))
                .isEqualTo(SHOP_ACCESS_DENIED);
        assertThat(shopStaffRows(ungranted))
                .as("no JIT shop_staff row may be written for an ungranted user (D-06)")
                .isZero();
        assertThat(shopStaffRows(control))
                .as("positive control: the counting query sees a real grant row")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UXT-004: GET /api/v1/staff/me for an ungranted vendor says groupAdmin=false, grantedShopIds=[]")
    void ungrantedVendor_staffMe_isNotAGroupAdmin_andHasNoShops() throws Exception {
        UUID ungranted = UUID.randomUUID();

        MvcResult me = mockMvc.perform(get("/api/v1/staff/me").with(vendorJwt(ungranted))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(me.getResponse().getStatus()).as(body(me)).isEqualTo(200);
        JsonNode access = json(me);
        assertThat(access.path("userId").asString()).as(body(me)).isEqualTo(ungranted.toString());
        assertThat(access.path("groupAdmin").asBoolean())
                .as("an ungranted user is not an implicit tenant-wide admin: %s", body(me))
                .isFalse();
        assertThat(access.path("grantedShopIds").isArray())
                .as("grantedShopIds is an explicit (empty) list, not the GROUP_ADMIN null: %s", body(me))
                .isTrue();
        assertThat(access.path("grantedShopIds").size()).as(body(me)).isZero();
        assertThat(shopStaffRows(ungranted)).as("asking what I may do provisions nothing").isZero();
    }

    @Test
    @DisplayName("D-06: an OPERATOR tenant-wide GROUP_ADMIN still writes (201)")
    void operatorGroupAdmin_isHonoured() throws Exception {
        UUID operator = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, operator, null, "GROUP_ADMIN", "operator-" + operator + "@example.com");

        MvcResult write = mockMvc.perform(post("/api/v1/products")
                        .with(vendorJwt(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("D06-OPERATOR"))))
                .andReturn();

        assertThat(write.getResponse().getStatus()).as(body(write)).isEqualTo(201);
        assertThat(shopStaffRows(operator)).as("the operator keeps exactly its one grant").isEqualTo(1);
    }

    @Test
    @DisplayName("D-06: a realm admin still writes (201) with no shop_staff row")
    void realmAdmin_isHonoured() throws Exception {
        UUID admin = UUID.randomUUID();

        MvcResult write = mockMvc.perform(post("/api/v1/products")
                        .with(realmAdminJwt(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("D06-REALM-ADMIN"))))
                .andReturn();

        assertThat(write.getResponse().getStatus()).as(body(write)).isEqualTo(201);
        assertThat(shopStaffRows(admin)).as("the realm-admin bridge needs no row").isZero();
    }

    @Test
    @DisplayName("T-37-08: with only JIT tenant-wide admins, the oldest is the WARN-logged bootstrap (201); a younger one is refused (403)")
    void onlyJitAdmins_oldestIsBootstrap_youngerIsRefused() throws Exception {
        UUID oldest = UUID.randomUUID();
        UUID younger = UUID.randomUUID();
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        seedJitGroupAdmin(oldest, base);
        seedJitGroupAdmin(younger, base.plusDays(1));

        MvcResult bootstrapWrite = mockMvc.perform(post("/api/v1/products")
                        .with(vendorJwt(oldest))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("D06-BOOTSTRAP"))))
                .andReturn();
        assertThat(bootstrapWrite.getResponse().getStatus())
                .as("the oldest JIT admin keeps access as the bootstrap admin: %s", body(bootstrapWrite))
                .isEqualTo(201);
        assertThat(appender.list)
                .as("the bootstrap retention is WARN-logged, naming the tenant and the user")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains("bootstrap admin retained")
                            .contains(tenantId.toString())
                            .contains(oldest.toString());
                });

        MvcResult youngerWrite = mockMvc.perform(post("/api/v1/products")
                        .with(vendorJwt(younger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(productBody("D06-YOUNGER-JIT"))))
                .andReturn();
        assertThat(youngerWrite.getResponse().getStatus())
                .as("a younger JIT admin is de-honoured under the shipped default: %s", body(youngerWrite))
                .isEqualTo(403);
        assertThat(json(youngerWrite).path("type").asString()).as(body(youngerWrite)).isEqualTo(SHOP_ACCESS_DENIED);
    }

    // ---- helpers ------------------------------------------------------------

    /** An ordinary vendor user: realm role {@code user} only, plus the catalogue write scope the endpoint needs. */
    private RequestPostProcessor vendorJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("realm_access", Map.of("roles", List.of("user")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    /** A realm admin (the D-03 bridge), plus the catalogue write scope. */
    private RequestPostProcessor realmAdminJwt(UUID sub) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "admin-" + sub + "@example.com")
                        .claim("realm_access", Map.of("roles", List.of("admin")))
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private Map<String, Object> productBody(String skuPrefix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", skuPrefix + "-" + UUID.randomUUID());
        body.put("title", "Jollof Rice");
        body.put("ingredientsText", "Rice, tomato, pepper");
        body.put("allergenMask", 0);
        body.put("pricePennies", 799);
        body.put("category", "Mains");
        body.put("available", true);
        body.put("shopId", shopId);
        return body;
    }

    /** A committed JIT-sourced tenant-wide GROUP_ADMIN row, as day-one provisioning wrote them. */
    private void seedJitGroupAdmin(UUID user, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', 'JIT', ?)",
                UUID.randomUUID(), tenantId, user, createdAt);
    }

    private long shopStaffRows(UUID user) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, user);
        return n == null ? 0 : n;
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
