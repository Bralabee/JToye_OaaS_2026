package uk.jtoye.core.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.jtoye.core.testsupport.TenantJwts.adminJwt;
import static uk.jtoye.core.testsupport.TenantJwts.vendorJwt;

/**
 * QA-council 20260902 SEC-1 — the webhook control plane is GROUP_ADMIN-only.
 *
 * <p><strong>The defect.</strong> {@code WebhookSubscriptionController} (7 handlers) and
 * {@code WebhookDeliveryController} (2 handlers) carried no role gate and neither
 * service consulted {@code ShopAccessService}, so the endpoints fell through to
 * {@code SecurityConfig}'s {@code anyRequest().authenticated()}. A tenant user holding
 * only a STAFF-rank grant on ONE shop could therefore mint and rotate the tenant's HMAC
 * signing secrets (breaking every downstream receiver), revoke integrations, and read the
 * tenant-wide delivery log — whose payloads are full {@code OrderDto}s with customer
 * name / email / phone for EVERY shop, because V55/V56 carry no {@code shop_id}.
 *
 * <p><strong>The oracle</strong> is the platform's own authorization model: the analogous
 * tenant-wide admin surface, {@code StaffManagementService} (list / grant / revoke), gates
 * at the service boundary with {@code shopAccessService.requireGroupAdmin()}. The fix
 * mirrors that exactly — a service-boundary call, no controller annotation, no new role,
 * no new config key.
 *
 * <p><strong>Shape of the proof.</strong> One caller per rank, real filter chain, real
 * Postgres (Testcontainers, Flyway schema, RLS policies):
 * <ul>
 *   <li>{@code STAFF_SUB} holds exactly one {@code shop_staff} row: STAFF on one shop.
 *       Under the production default ({@code strict-scoping} OFF) a user holding ANY
 *       explicit grant is scoped ({@code ShopAccessService.isGroupAdminForUser}), so this is
 *       precisely the population the gate exists for. Every one of the nine handlers must
 *       answer 403 {@code errors/shop-access-denied}, and — the invariant that makes the
 *       403s falsifiable — nothing may have been mutated and the grant may not have been
 *       escalated by the denied requests themselves.</li>
 *   <li>A realm-admin (the D-03 bridge; the same shape every dashboard vendor user carries
 *       on the E2E stack) must still receive 2xx on all nine, so the gate is proven able to
 *       ALLOW as well as deny — a gate observed only denying is not evidence.</li>
 * </ul>
 *
 * <p>Watched RED at {@code 9d9efaeb} (pre-fix): all nine STAFF arms returned 2xx and the
 * no-mutation invariant failed on a rotated secret. The realm-admin controls were green
 * on both trees, which is what they are for.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class WebhookAuthzIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // Hermetic: no DNS resolution for the subscription target URL; HTTPS is still enforced.
        registry.add("webhook.target.block-private-ranges", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopService shopService;

    private static final String SHOP_ACCESS_DENIED = "https://jtoye.uk/errors/shop-access-denied";
    private static final String CREATE_BODY =
            "{\"targetUrl\":\"https://example.com/hook\",\"eventTypes\":[\"ORDER_STATE_CHANGED\"]}";

    /** One tenant, one shop, one STAFF-rank user on that shop, one subscription with one delivery. */
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID STAFF_SUB = UUID.randomUUID();
    private static final UUID SUB = UUID.randomUUID();
    private static final UUID DELIVERY = UUID.randomUUID();
    /** A second delivery the realm-admin control replays, so the STAFF no-mutation invariant on DELIVERY is order-independent. */
    private static final UUID DELIVERY_FOR_ADMIN = UUID.randomUUID();
    private static final String SEEDED_SECRET = "secret-" + SUB;

    private static boolean seeded = false;

    @BeforeEach
    void seedOnce() {
        if (seeded) {
            return;
        }
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT, "wh-authz-tenant-" + TENANT);

        // A real shop must exist to satisfy shop_staff_shop_id_fkey. Seed it through the real
        // service as a realm-admin (implicit GROUP_ADMIN), the StaffManagementIntegrationTest pattern.
        UUID shopId = seedShopAsRealmAdmin();

        // The population under test: exactly ONE grant, STAFF, on ONE shop. Not tenant-wide,
        // not GROUP_ADMIN. The Testcontainers bootstrap role is a superuser, so this direct
        // INSERT bypasses FORCE RLS and needs no tenant GUC (ShopAccessEnforcementIntegrationTest).
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, 'STAFF', now())",
                UUID.randomUUID(), TENANT, STAFF_SUB, shopId);

        jdbc.update("INSERT INTO webhook_subscription "
                        + "(id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, 'https://example.com/hook', ARRAY['ORDER_STATE_CHANGED'], ?, 'ACTIVE')",
                SUB, TENANT, SEEDED_SECRET);
        jdbc.update("INSERT INTO webhook_delivery "
                        + "(id, tenant_id, subscription_id, event_id, event_type, payload, status, "
                        + " attempt_count, last_http_status, last_error) "
                        + "VALUES (?, ?, ?, ?, 'order.state.changed', ?, 'FAILED', 8, 500, '500 Internal Server Error')",
                DELIVERY, TENANT, SUB, UUID.randomUUID(), "{\"id\":\"" + DELIVERY + "\"}");
        jdbc.update("INSERT INTO webhook_delivery "
                        + "(id, tenant_id, subscription_id, event_id, event_type, payload, status, "
                        + " attempt_count, last_http_status, last_error) "
                        + "VALUES (?, ?, ?, ?, 'order.state.changed', ?, 'FAILED', 8, 500, '500 Internal Server Error')",
                DELIVERY_FOR_ADMIN, TENANT, SUB, UUID.randomUUID(), "{\"id\":\"" + DELIVERY_FOR_ADMIN + "\"}");
        seeded = true;
    }

    private UUID seedShopAsRealmAdmin() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "seed-admin@example.com")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
        TenantContext.set(TENANT);
        try {
            CreateShopRequest req = new CreateShopRequest();
            req.setName("Webhook Authz Shop " + UUID.randomUUID());
            req.setAddress("1 Test Street, London");
            return shopService.createShop(req).getId();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    // ---------------------------------------------------------------------
    // STAFF-rank caller: 403 on every one of the nine handlers
    // ---------------------------------------------------------------------

    private ResultActions asStaff(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(vendorJwt(STAFF_SUB, TENANT)));
    }

    private static void expectShopAccessDenied(ResultActions actions) throws Exception {
        actions.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(SHOP_ACCESS_DENIED))
                .andExpect(jsonPath("$.requiredRole").value("GROUP_ADMIN"));
    }

    @Test
    @DisplayName("STAFF: GET /api/v1/webhooks (list) is 403 shop-access-denied")
    void staff_list_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(get("/api/v1/webhooks")));
    }

    @Test
    @DisplayName("STAFF: GET /api/v1/webhooks/{id} is 403 shop-access-denied")
    void staff_get_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(get("/api/v1/webhooks/" + SUB)));
    }

    @Test
    @DisplayName("STAFF: POST /api/v1/webhooks (create — mints a signing secret) is 403")
    void staff_create_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(post("/api/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY)));
    }

    @Test
    @DisplayName("STAFF: POST /{id}/rotate-secret (breaks every receiver) is 403")
    void staff_rotateSecret_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(post("/api/v1/webhooks/" + SUB + "/rotate-secret")));
    }

    @Test
    @DisplayName("STAFF: POST /{id}/pause is 403")
    void staff_pause_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(post("/api/v1/webhooks/" + SUB + "/pause")));
    }

    @Test
    @DisplayName("STAFF: POST /{id}/resume is 403")
    void staff_resume_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(post("/api/v1/webhooks/" + SUB + "/resume")));
    }

    @Test
    @DisplayName("STAFF: POST /{id}/revoke (terminal) is 403")
    void staff_revoke_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(post("/api/v1/webhooks/" + SUB + "/revoke")));
    }

    @Test
    @DisplayName("STAFF: GET /{id}/deliveries (tenant-wide order PII) is 403")
    void staff_deliveryLog_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(get("/api/v1/webhooks/" + SUB + "/deliveries")));
    }

    @Test
    @DisplayName("STAFF: POST /{id}/deliveries/{deliveryId}/replay is 403")
    void staff_replay_isDenied() throws Exception {
        expectShopAccessDenied(asStaff(
                post("/api/v1/webhooks/" + SUB + "/deliveries/" + DELIVERY + "/replay")));
    }

    /**
     * The falsifiability anchor. A 403 status alone could be produced AFTER a write (a
     * gate placed below the mutation), and a denied request could still escalate its own
     * caller through the JIT provision. So: fire the two most dangerous writes as STAFF,
     * then read the truth out of the database — the seeded secret is byte-identical, the
     * subscription is still ACTIVE, no replay row exists, and STAFF_SUB still holds exactly
     * one row, STAFF, on one shop (no tenant-wide GROUP_ADMIN was minted). On the pre-fix
     * tree this arm fails first on the rotated secret.
     */
    @Test
    @DisplayName("STAFF: the denied calls mutate nothing and do not escalate the caller's grant")
    void staff_deniedCalls_mutateNothing_andDoNotEscalateGrant() throws Exception {
        asStaff(post("/api/v1/webhooks/" + SUB + "/rotate-secret")).andExpect(status().isForbidden());
        asStaff(post("/api/v1/webhooks/" + SUB + "/revoke")).andExpect(status().isForbidden());
        asStaff(post("/api/v1/webhooks/" + SUB + "/deliveries/" + DELIVERY + "/replay"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject(
                "SELECT signing_secret FROM webhook_subscription WHERE id = ?", String.class, SUB))
                .as("the signing secret is untouched by the denied rotate")
                .isEqualTo(SEEDED_SECRET);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM webhook_subscription WHERE id = ?", String.class, SUB))
                .as("the subscription is not revoked by the denied revoke")
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM webhook_delivery WHERE is_replay = true AND replay_of = ?",
                Integer.class, DELIVERY))
                .as("no replay row is created by the denied replay")
                .isZero();
        assertThat(jdbc.queryForList(
                "SELECT role FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                String.class, TENANT, STAFF_SUB))
                .as("the caller's grant set is exactly {STAFF}: the denied requests did not JIT-provision a GROUP_ADMIN")
                .containsExactly("STAFF");
    }

    // ---------------------------------------------------------------------
    // Realm-admin control: the same nine handlers still ALLOW a GROUP_ADMIN
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GROUP_ADMIN control: all seven subscription handlers answer 2xx")
    void admin_subscriptionHandlers_areAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks").with(adminJwt(TENANT)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/webhooks/" + SUB).with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SUB.toString()));

        // Mutations run on a subscription this control creates for itself, so the shared
        // fixture the STAFF arms deny against is never rotated/revoked by the control.
        String created = mockMvc.perform(post("/api/v1/webhooks").with(adminJwt(TENANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String own = com.jayway.jsonpath.JsonPath.read(created, "$.subscription.id");

        mockMvc.perform(post("/api/v1/webhooks/" + own + "/rotate-secret").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").isNotEmpty());
        mockMvc.perform(post("/api/v1/webhooks/" + own + "/pause").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
        mockMvc.perform(post("/api/v1/webhooks/" + own + "/resume").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/webhooks/" + own + "/revoke").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    @DisplayName("GROUP_ADMIN control: both delivery handlers answer 2xx")
    void admin_deliveryHandlers_areAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks/" + SUB + "/deliveries").with(adminJwt(TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(post("/api/v1/webhooks/" + SUB + "/deliveries/" + DELIVERY_FOR_ADMIN + "/replay")
                        .with(adminJwt(TENANT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayOf").value(DELIVERY_FOR_ADMIN.toString()));
    }
}
