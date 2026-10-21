package uk.jtoye.core.finance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The ledger create ({@code POST /financial-transactions}) authorises the {@code orderId} and {@code shopId}
 * the caller supplies: an order must belong to the caller's tenant, the row's shop is the order's own shop, and
 * a shop named with no order must be one the caller may manage (which also binds it to the caller's tenant).
 *
 * <p>Real Postgres with the Testcontainers role downgraded to NOSUPERUSER inside the test transaction (so FORCE
 * RLS applies to every statement the requests issue), strict scoping ON, JWTs through MockMvc and the real filter
 * chain. "Rows written" is read through a ground-truth role that does not depend on the tenant GUC: a
 * NOLOGIN BYPASSRLS role with SELECT only, entered with SET LOCAL ROLE. Before any zero is trusted the test
 * asserts that role sees both tenants' shops while the plain connection role, with no tenant pinned, sees none.
 * Each refusal arm is paired with an allowed control, and afterwards the other tenant deletes its own shop
 * through the service (flushed): a ledger row pointing at it would block that on the foreign key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
@Import(NoScheduledTriggersTestConfig.class)
class LedgerCreateCallerLinksIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String LEDGER = "/api/v1/financial-transactions";
    private static final String TRUTH_ROLE = "ledger_truth_reader";

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
    private UUID shopBPublished;
    private UUID shopBUnpublished;
    private UUID orderA1;
    private UUID orderB;
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
        tenant(tenantA, "LG-A-" + suffix);
        tenant(tenantB, "LG-B-" + suffix);
        shopA1 = shop(tenantA, "a1", true);
        shopA2 = shop(tenantA, "a2", true);
        shopBPublished = shop(tenantB, "bp", true);
        shopBUnpublished = shop(tenantB, "bu", false);
        orderA1 = order(tenantA, shopA1);
        // The other tenant's order sits on its own shop, so the delete probes below are not blocked by the order itself.
        orderB = order(tenantB, shop(tenantB, "bo", true));
        groupAdminA = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at, grant_source) "
                + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', now(), 'OPERATOR')", UUID.randomUUID(), tenantA, groupAdminA);

        // The ground-truth reader: BYPASSRLS, SELECT only. Created and granted while still a superuser; the
        // grant lets the (soon NOSUPERUSER) connection role SET ROLE to it. All rolled back with the test.
        jdbc.execute("CREATE ROLE " + TRUTH_ROLE + " NOLOGIN BYPASSRLS");
        jdbc.execute("GRANT SELECT ON financial_transactions, shops TO " + TRUTH_ROLE);
        jdbc.execute("GRANT " + TRUTH_ROLE + " TO \"" + postgres.getUsername() + "\"");

        jdbc.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("the connection role is NOSUPERUSER, so FORCE RLS applies").isFalse();
        assertThat(jdbc.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("and does not bypass RLS").isFalse();

        // Non-vacuity of every "0 rows" below: the ground-truth role sees BOTH tenants' shops, the plain
        // connection role with no tenant pinned sees none of them.
        assertThat(truth(() -> jdbc.queryForObject("SELECT count(DISTINCT tenant_id) FROM shops WHERE id IN (?, ?, ?)",
                Integer.class, shopA1, shopBPublished, shopBUnpublished)))
                .as("the ground-truth role sees both tenants").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shops WHERE id = ?", Integer.class, shopBUnpublished))
                .as("the plain role with no tenant pinned cannot see an unpublished shop (published shops are "
                        + "publicly readable, so only this one is a fair probe)")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id IN (?, ?)", Integer.class, orderA1, orderB))
                .as("nor either order: a plain-role zero on tenant data would be meaningless")
                .isZero();
    }

    @AfterEach
    void tearDown() {
        StrictScopingGuard.restore(shopAccessService, bootedStrictScoping);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a Group admin naming another tenant's published shop: refused (404), nothing written, the owner can delete it")
    void foreignPublishedShopRefused() throws Exception {
        MvcResult result = post(groupAdminA, body(null, shopBPublished));

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(404);
        assertThat(ledgerRows()).as("rows written: %s", text(result)).isZero();
        assertOwnerCanDelete(shopBPublished, tenantB);
    }

    @Test
    @DisplayName("a Group admin naming another tenant's unpublished shop: refused (404), nothing written")
    void foreignUnpublishedShopRefused() throws Exception {
        MvcResult result = post(groupAdminA, body(null, shopBUnpublished));

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(404);
        assertThat(ledgerRows()).as(text(result)).isZero();
        assertOwnerCanDelete(shopBUnpublished, tenantB);
    }

    @Test
    @DisplayName("another tenant's order: 404, nothing written (with and without that tenant's shop in the body)")
    void foreignOrderRefused() throws Exception {
        MvcResult bare = post(groupAdminA, body(orderB, null));
        assertThat(bare.getResponse().getStatus()).as(text(bare)).isEqualTo(404);

        MvcResult withShop = post(groupAdminA, body(orderB, shopBPublished));
        assertThat(withShop.getResponse().getStatus()).as(text(withShop)).isEqualTo(404);

        assertThat(ledgerRows()).as("rows written: %s / %s", text(bare), text(withShop)).isZero();
        assertOwnerCanDelete(shopBPublished, tenantB);
    }

    @Test
    @DisplayName("an order id that does not exist: 404, nothing written")
    void randomOrderRefused() throws Exception {
        MvcResult result = post(groupAdminA, body(UUID.randomUUID(), null));

        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(404);
        assertThat(ledgerRows()).as(text(result)).isZero();
    }

    @Test
    @DisplayName("an own-tenant order with a shopId that is not the order's shop: 400 naming shopId, nothing written")
    void shopDisagreeingWithOrderRefused() throws Exception {
        MvcResult sibling = post(groupAdminA, body(orderA1, shopA2));
        assertThat(sibling.getResponse().getStatus()).as(text(sibling)).isEqualTo(400);
        assertThat(sibling.getResponse().getContentAsString()).contains("shopId");

        MvcResult foreign = post(groupAdminA, body(orderA1, shopBPublished));
        assertThat(foreign.getResponse().getStatus()).as(text(foreign)).isEqualTo(400);

        assertThat(ledgerRows()).as("rows written: %s / %s", text(sibling), text(foreign)).isZero();
        assertOwnerCanDelete(shopBPublished, tenantB);
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    @Test
    @DisplayName("control: an own-tenant order is accepted and the row carries the order's shop")
    void ownOrderAccepted() throws Exception {
        MvcResult noShop = post(groupAdminA, body(orderA1, null));
        assertThat(noShop.getResponse().getStatus()).as(text(noShop)).isEqualTo(201);
        assertThat(ledgerShops()).containsExactly(shopA1);
    }

    @Test
    @DisplayName("control: an own-tenant order with its own shop in the body is accepted")
    void ownOrderWithItsShopAccepted() throws Exception {
        MvcResult result = post(groupAdminA, body(orderA1, shopA1));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        assertThat(ledgerShops()).containsExactly(shopA1);
    }

    @Test
    @DisplayName("control: an own-tenant shop with no order is accepted (a manual row stores no shop)")
    void ownShopWithoutOrderAccepted() throws Exception {
        MvcResult result = post(groupAdminA, body(null, shopA2));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(ledgerShops()).as("a manual row has no shop").containsExactly((UUID) null);
    }

    @Test
    @DisplayName("control: a plain manual row (no order, no shop) is accepted")
    void manualRowAccepted() throws Exception {
        MvcResult result = post(groupAdminA, body(null, null));
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(201);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void assertOwnerCanDelete(UUID shop, UUID owner) {
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
        assertThat(truth(() -> jdbc.queryForObject("SELECT count(*) FROM shops WHERE id = ?", Integer.class, shop)))
                .as("the owning tenant's shop delete went through").isZero();
    }

    /** Ledger rows of either test tenant, read through the BYPASSRLS role (no dependence on the tenant GUC). */
    private int ledgerRows() {
        return truth(() -> jdbc.queryForObject("SELECT count(*) FROM financial_transactions WHERE tenant_id IN (?, ?)",
                Integer.class, tenantA, tenantB));
    }

    private List<UUID> ledgerShops() {
        return truth(() -> jdbc.queryForList("SELECT shop_id FROM financial_transactions WHERE tenant_id IN (?, ?)",
                UUID.class, tenantA, tenantB));
    }

    private <T> T truth(Supplier<T> read) {
        em.flush();
        jdbc.execute("SET LOCAL ROLE " + TRUTH_ROLE);
        try {
            return read.get();
        } finally {
            jdbc.execute("RESET ROLE");
        }
    }

    private MvcResult post(UUID user, Map<String, Object> body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(LEDGER)
                        .with(userJwt(user, tenantA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private static Map<String, Object> body(UUID orderId, UUID shopId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amountPennies", 1200);
        body.put("vatRate", "STANDARD");
        body.put("description", "ledger caller-links probe");
        if (orderId != null) {
            body.put("orderId", orderId.toString());
        }
        if (shopId != null) {
            body.put("shopId", shopId.toString());
        }
        return body;
    }

    private void tenant(UUID id, String name) {
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", id, name);
    }

    private UUID shop(UUID tenant, String tag, boolean published) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, 0, 0, 0)",
                id, tenant, "Shop " + tag + " " + suffix, "lg-" + tag + "-" + suffix, published);
        return id;
    }

    private UUID order(UUID tenant, UUID shop) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, subtotal_pennies, vat_rate, "
                        + "vat_amount_pennies, delivery_fee_pennies, total_amount_pennies, item_count, version, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 500, 'ZERO', 0, 0, 500, 1, 0, now(), now())",
                id, tenant, shop, "ORD-" + id);
        return id;
    }

    private static RequestPostProcessor userJwt(UUID subject, UUID tenant) {
        return jwt().jwt(j -> j.subject(subject.toString()).claim("tenant_id", tenant.toString()));
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }
}
