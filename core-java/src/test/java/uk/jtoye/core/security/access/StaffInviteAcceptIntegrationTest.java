package uk.jtoye.core.security.access;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminException;
import uk.jtoye.core.tenant.keycloak.KeycloakUserRejectedException;
import uk.jtoye.core.tenant.keycloak.VendorRealmUser;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.SentMail;
import uk.jtoye.core.testsupport.ShopGrants;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-07 / D-26 (Phase 37-08, RWO-004 / RWO-003): an invitation link turns into an account that lands
 * with EXACTLY the invited grant, and every way a link can be unusable looks the same from outside.
 *
 * <p><b>The link and the API (37-08 deviation, the V62/V75 rule).</b> The emailed link is
 * {@code {accept-base-url}#token={tenantId}.{token}}: the token rides in the URL FRAGMENT, which no
 * browser sends to a server, exactly like the DSAR export link. The accept page reads it client-side
 * and POSTs it in a JSON body to {@code /api/v1/public/staff-invites/preview} and
 * {@code /api/v1/public/staff-invites/accept}, so the token never reaches a request line, an access
 * log, an APM span or a {@code Referer}.
 *
 * <p>Keycloak is a Mockito double of {@link KeycloakAdminClient} (its HTTP shape is proven by
 * {@code KeycloakAdminClientTest}); everything else is real: Postgres with the Flyway schema, the
 * MVC stack, the exception handler and the public per-IP rate limiter on a real Redis. Stored rows
 * are read back by SQL inside a transaction with the tenant GUC pinned.
 *
 * <p>Non-default config on purpose: the vendor realm is {@code jtoye-vendor-it}, so a
 * {@code jtoye.keycloak.admin.vendor-realm} key that failed to bind falls back to {@code jtoye-dev},
 * misses every stub and reds the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class StaffInviteAcceptIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final String ACCEPT_BASE = "https://app.jtoye.test/invite";
    static final String VENDOR_REALM = "jtoye-vendor-it";
    static final String PREVIEW = "/api/v1/public/staff-invites/preview";
    static final String ACCEPT = "/api/v1/public/staff-invites/accept";
    static final String PASSWORD = "Tr0ub4dor&3-Invite-Only";
    static final int PUBLIC_RPM = 5;
    static final int PUBLIC_BURST = 2;
    static final String UNAVAILABLE_TYPE = "https://jtoye.uk/errors/staff-invite-unavailable";

    private static final SecureRandom RANDOM = new SecureRandom();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> "noreply@platform.jtoye.test");
        registry.add("management.health.mail.enabled", () -> "false");
        registry.add("jtoye.staff.invite.accept-base-url", () -> ACCEPT_BASE);
        // The Keycloak admin seam is ON (the client itself is a mock), with a non-default vendor realm.
        registry.add("jtoye.keycloak.admin.enabled", () -> "true");
        registry.add("jtoye.keycloak.admin.base-url", () -> "http://keycloak.invalid:8080");
        registry.add("jtoye.keycloak.admin.password", () -> "not-a-real-admin-password");
        registry.add("jtoye.keycloak.admin.vendor-realm", () -> VENDOR_REALM);
        // The public per-IP limiter, live on a real Redis, with a tiny bucket for the 429 arm. Every
        // other request comes from its own random address, so no arm can starve another.
        registry.add("rate-limiting.enabled", () -> "true");
        registry.add("rate-limiting.public.requests-per-minute", () -> String.valueOf(PUBLIC_RPM));
        registry.add("rate-limiting.public.burst", () -> String.valueOf(PUBLIC_BURST));
        registry.add("rate-limiting.public.window-seconds", () -> "60");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @MockitoBean private KeycloakAdminClient keycloak;
    @MockitoBean private JavaMailSender mailSender;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    private UUID tenantId;
    private String tenantName;
    private UUID shopA;
    private String shopAName;
    private UUID admin;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        when(keycloak.obtainAdminToken()).thenReturn("tok");
        tenantId = UUID.randomUUID();
        tenantName = "Mama Ade's Kitchen Group " + tenantId.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenantId, tenantName);
        shopAName = "Accept Shop A " + tenantId.toString().substring(0, 4);
        shopA = seedShop(tenantId, shopAName);
        admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
        jdbc.update("UPDATE user_directory SET display_name = 'Ada Admin' WHERE tenant_id = ? AND user_id = ?",
                tenantId, admin);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1 (tracer): a new invitee accepts and lands with exactly the invited grant -------

    @Test
    @DisplayName("D-07/D-26: the emailed link (token in the fragment) previews as NEW, and accepting creates the "
            + "Keycloak user with a tenant and writes exactly the invited grant and the directory row")
    void newInvitee_fromTheEmailedLink_previewThenAccept_landsWithExactlyTheInvitedGrant() throws Exception {
        String email = "grace.hopper@example.com";
        MvcResult issued = mockMvc.perform(post("/api/v1/staff/invites").with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"role\":\"STAFF\",\"shopId\":\"" + shopA + "\"}"))
                .andReturn();
        assertThat(issued.getResponse().getStatus()).as(body(issued)).isEqualTo(201);
        UUID inviteId = UUID.fromString(json(issued).path("id").asString());

        String text = SentMail.text(SentMail.reparse((MimeMessage) awaitSends(1).get(0).getArgument(0)));
        Matcher link = Pattern.compile(Pattern.quote(ACCEPT_BASE + "#token=") + "([0-9a-f-]{36}\\.[A-Za-z0-9_-]{43})")
                .matcher(text);
        assertThat(link.find()).as("the link carries {tenantId}.{token} in the fragment: %s", text).isTrue();
        String ref = link.group(1);
        assertThat(ref).startsWith(tenantId + ".");

        UUID newUserId = UUID.randomUUID();
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok")).thenReturn(List.of());
        List<String> passwordsSeenByKeycloak = new CopyOnWriteArrayList<>();
        when(keycloak.createUser(eq(VENDOR_REALM), eq(email), eq("Grace"), eq("Hopper"), any(char[].class),
                eq(tenantId), eq("tok")))
                .thenAnswer(inv -> {
                    passwordsSeenByKeycloak.add(new String((char[]) inv.getArgument(4)));
                    return newUserId.toString();
                });

        MvcResult preview = preview(ref, randomIp());
        assertThat(preview.getResponse().getStatus()).as(body(preview)).isEqualTo(200);
        assertThat(preview.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode p = json(preview);
        assertThat(p.path("accountState").asString()).isEqualTo("NEW");
        assertThat(p.path("businessName").asString()).isEqualTo(tenantName);
        assertThat(p.path("role").asString()).isEqualTo("STAFF");
        assertThat(p.path("shopName").asString()).isEqualTo(shopAName);
        assertThat(p.path("inviterName").asString()).isEqualTo("Ada Admin");
        assertThat(p.path("email").asString()).isEqualTo(email);

        MvcResult accepted = accept(ref, "Grace", "Hopper", PASSWORD, randomIp());
        assertThat(accepted.getResponse().getStatus()).as(body(accepted)).isEqualTo(201);
        assertThat(accepted.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode a = json(accepted);
        assertThat(a.path("businessName").asString()).isEqualTo(tenantName);
        assertThat(a.path("role").asString()).isEqualTo("STAFF");
        assertThat(a.path("shopName").asString()).isEqualTo(shopAName);
        assertThat(body(accepted)).as("the password is never echoed").doesNotContain(PASSWORD);

        verify(keycloak, times(1)).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        assertThat(passwordsSeenByKeycloak).as("Keycloak received the chosen password, once").containsExactly(PASSWORD);

        List<Map<String, Object>> grants = pinned(tenantId, j -> j.queryForList(
                "SELECT shop_id, role, grant_source, created_by FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                tenantId, newUserId));
        assertThat(grants).as("exactly the invited grant, as an OPERATOR grant by the inviter").hasSize(1);
        assertThat(grants.get(0).get("shop_id")).isEqualTo(shopA);
        assertThat(grants.get(0).get("role")).isEqualTo("STAFF");
        assertThat(grants.get(0).get("grant_source")).isEqualTo("OPERATOR");
        assertThat(grants.get(0).get("created_by")).isEqualTo(admin);

        Map<String, Object> dir = pinned(tenantId, j -> j.queryForMap(
                "SELECT email, display_name FROM user_directory WHERE tenant_id = ? AND user_id = ?", tenantId, newUserId));
        assertThat(dir.get("email")).isEqualTo(email);
        assertThat(dir.get("display_name")).isEqualTo("Grace Hopper");

        Map<String, Object> invite = pinned(tenantId, j -> j.queryForMap(
                "SELECT accepted_at, accepted_user_id FROM staff_invite WHERE id = ?", inviteId));
        assertThat(invite.get("accepted_at")).as("accepted").isNotNull();
        assertThat(invite.get("accepted_user_id")).isEqualTo(newUserId);
    }

    // ---- Task 2: every other outcome ---------------------------------------------------------------

    @Test
    @DisplayName("D-07: an address that already has an account of THIS business accepts with no password; the "
            + "grant is exactly the invitation's even when the body asks for more, and a de-honoured JIT row "
            + "for the same shop becomes the invited OPERATOR grant")
    void existingAccountHere_acceptsWithoutPassword_andGetsExactlyTheInvitedGrant() throws Exception {
        String email = "already.here@example.com";
        UUID existingUser = UUID.randomUUID();
        // A day-one JIT row on the same shop, which strict scoping de-honours: the invite must replace it.
        pinned(tenantId, j -> j.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, "
                + "created_at) VALUES (?, ?, ?, ?, 'SHOP_MANAGER', 'JIT', now())", UUID.randomUUID(), tenantId,
                existingUser, shopA));
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        UUID inviteId = lastSeededId;
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok"))
                .thenReturn(List.of(new VendorRealmUser(existingUser.toString(), email, tenantId.toString())));

        MvcResult preview = preview(ref, randomIp());
        assertThat(preview.getResponse().getStatus()).as(body(preview)).isEqualTo(200);
        assertThat(json(preview).path("accountState").asString()).isEqualTo("EXISTS_HERE");

        // The body tries to escalate: role, shop and tenant fields are not part of the contract and change nothing.
        String escalation = "{\"ref\":" + jsonString(ref) + ",\"role\":\"GROUP_ADMIN\",\"shopId\":null,"
                + "\"tenantId\":\"" + UUID.randomUUID() + "\",\"createdBy\":\"" + existingUser + "\"}";
        MvcResult accepted = mockMvc.perform(post(ACCEPT).with(from(randomIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(escalation))
                .andReturn();
        assertThat(accepted.getResponse().getStatus()).as(body(accepted)).isEqualTo(201);
        assertThat(json(accepted).path("role").asString()).isEqualTo("STAFF");

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        List<Map<String, Object>> grants = pinned(tenantId, j -> j.queryForList(
                "SELECT shop_id, role, grant_source, created_by FROM shop_staff WHERE tenant_id = ? AND user_id = ?",
                tenantId, existingUser));
        assertThat(grants).as("one row: the invited grant, nothing wider").hasSize(1);
        assertThat(grants.get(0).get("shop_id")).isEqualTo(shopA);
        assertThat(grants.get(0).get("role")).isEqualTo("STAFF");
        assertThat(grants.get(0).get("grant_source")).isEqualTo("OPERATOR");
        assertThat(grants.get(0).get("created_by")).isEqualTo(admin);
        assertThat(acceptedUserId(inviteId)).isEqualTo(existingUser);
        assertThat(count("SELECT count(*) FROM user_directory WHERE tenant_id = ? AND user_id = ? AND email = ?",
                tenantId, existingUser, email)).isEqualTo(1L);
    }

    @Test
    @DisplayName("T-37-20: an address whose account belongs to another business previews OTHER_BUSINESS and "
            + "accepting is refused 409 staff-invite-email-in-other-business with nothing written")
    void addressOfAnotherBusiness_isRefused409_andNothingIsWritten() throws Exception {
        String email = "elsewhere@example.com";
        UUID otherUser = UUID.randomUUID();
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        UUID inviteId = lastSeededId;
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok"))
                .thenReturn(List.of(new VendorRealmUser(otherUser.toString(), email, UUID.randomUUID().toString())));

        MvcResult preview = preview(ref, randomIp());
        assertThat(preview.getResponse().getStatus()).as(body(preview)).isEqualTo(200);
        assertThat(json(preview).path("accountState").asString()).isEqualTo("OTHER_BUSINESS");

        MvcResult refused = accept(ref, "Other", "Person", PASSWORD, randomIp());
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(409);
        JsonNode problem = json(refused);
        assertThat(problem.path("type").asString())
                .isEqualTo("https://jtoye.uk/errors/staff-invite-email-in-other-business");
        assertThat(problem.path("code").asString()).isEqualTo("STAFF_INVITE_EMAIL_IN_OTHER_BUSINESS");

        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        assertThat(count("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", tenantId, otherUser))
                .isZero();
        assertThat(count("SELECT count(*) FROM user_directory WHERE tenant_id = ? AND email = ?", tenantId, email))
                .isZero();
        assertThat(acceptedAt(inviteId)).as("still open").isNull();
    }

    @Test
    @DisplayName("UI-SPEC B2 state 4: expired, used, cancelled, wrong-business, unknown, malformed, missing and "
            + "offboarded-business links all PREVIEW as one byte-identical 404 with Cache-Control no-store")
    void everyUnusablePreview_isOneIdentical404() throws Exception {
        List<String> refs = unusableRefs();
        List<byte[]> bodies = new ArrayList<>();
        for (String ref : refs) {
            MvcResult r = preview(ref, randomIp());
            assertThat(r.getResponse().getStatus()).as("cause #%d: %s", bodies.size(), body(r)).isEqualTo(404);
            assertThat(r.getResponse().getHeader("Cache-Control")).as("cause #%d", bodies.size()).contains("no-store");
            bodies.add(r.getResponse().getContentAsByteArray());
        }
        assertUnavailableBodies(bodies);
        verify(keycloak, never()).findVendorUsersByEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("UI-SPEC B2 state 4: the same eight causes ACCEPT as one byte-identical 404, and nothing is written")
    void everyUnusableAccept_isOneIdentical404_andWritesNothing() throws Exception {
        List<String> refs = unusableRefs();
        long grantsBefore = count("SELECT count(*) FROM shop_staff WHERE tenant_id = ?", tenantId);
        List<byte[]> bodies = new ArrayList<>();
        for (String ref : refs) {
            MvcResult r = accept(ref, "Link", "Holder", PASSWORD, randomIp());
            assertThat(r.getResponse().getStatus()).as("cause #%d: %s", bodies.size(), body(r)).isEqualTo(404);
            assertThat(r.getResponse().getHeader("Cache-Control")).as("cause #%d", bodies.size()).contains("no-store");
            bodies.add(r.getResponse().getContentAsByteArray());
        }
        assertUnavailableBodies(bodies);
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        assertThat(count("SELECT count(*) FROM shop_staff WHERE tenant_id = ?", tenantId)).isEqualTo(grantsBefore);
    }

    @Test
    @DisplayName("T-37-21: two simultaneous accepts of one link produce exactly one 201, one 404 and one grant")
    void twoConcurrentAccepts_produceOneGrant() throws Exception {
        String email = "race@example.com";
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        UUID newUser = UUID.randomUUID();
        CyclicBarrier bothLookedUp = new CyclicBarrier(2);
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok")).thenAnswer(inv -> {
            bothLookedUp.await(10, TimeUnit.SECONDS);   // both requests are past the read before either claims
            return List.of();
        });
        when(keycloak.createUser(eq(VENDOR_REALM), eq(email), anyString(), anyString(), any(char[].class),
                eq(tenantId), eq("tok"))).thenAnswer(inv -> {
                    Thread.sleep(500);                   // the winner holds the claim while Keycloak works
                    return newUser.toString();
                });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return accept(ref, "Race", "Runner", PASSWORD, randomIp()).getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("one winner, one refusal").containsExactlyInAnyOrder(201, 404);
        } finally {
            pool.shutdownNow();
        }
        verify(keycloak, times(1)).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        assertThat(count("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", tenantId, newUser))
                .as("exactly one grant row").isEqualTo(1L);
    }

    @Test
    @DisplayName("T-37-22: a Keycloak password-policy 400 is 422 staff-invite-password-rejected carrying Keycloak's "
            + "message (never the password), the invite stays open, and a retry with a good password succeeds")
    void passwordPolicyRefusal_is422_andLeavesTheInviteOpen() throws Exception {
        String email = "weak.password@example.com";
        String weak = "weakpass-" + UUID.randomUUID().toString().substring(0, 6);
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        UUID inviteId = lastSeededId;
        UUID newUser = UUID.randomUUID();
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok")).thenReturn(List.of());
        when(keycloak.createUser(eq(VENDOR_REALM), eq(email), anyString(), anyString(), any(char[].class),
                eq(tenantId), eq("tok")))
                .thenThrow(new KeycloakUserRejectedException(VENDOR_REALM, "Password policy not met"))
                .thenReturn(newUser.toString());

        MvcResult refused = accept(ref, "Pat", "Weak", weak, randomIp());
        assertThat(refused.getResponse().getStatus()).as(body(refused)).isEqualTo(422);
        JsonNode problem = json(refused);
        assertThat(problem.path("type").asString()).isEqualTo("https://jtoye.uk/errors/staff-invite-password-rejected");
        assertThat(problem.path("code").asString()).isEqualTo("STAFF_INVITE_PASSWORD_REJECTED");
        assertThat(problem.path("detail").asString()).isEqualTo("Password policy not met");
        assertThat(body(refused)).as("the password is never echoed").doesNotContain(weak);

        Map<String, Object> invite = pinned(tenantId, j -> j.queryForMap(
                "SELECT accepted_at, accepted_user_id FROM staff_invite WHERE id = ?", inviteId));
        assertThat(invite.get("accepted_at")).as("the claim rolled back: still open").isNull();
        assertThat(count("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", tenantId, newUser)).isZero();
        assertThat(count("SELECT count(*) FROM user_directory WHERE tenant_id = ? AND email = ?", tenantId, email)).isZero();

        MvcResult retry = accept(ref, "Pat", "Strong", PASSWORD, randomIp());
        assertThat(retry.getResponse().getStatus()).as(body(retry)).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM shop_staff WHERE tenant_id = ? AND user_id = ?", tenantId, newUser))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("A NEW account without a name or a password is 400 before anything is claimed or created")
    void newAccountWithoutNamesOrPassword_is400_andTheInviteStaysOpen() throws Exception {
        String email = "incomplete@example.com";
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        UUID inviteId = lastSeededId;
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok")).thenReturn(List.of());

        for (String[] form : List.of(new String[]{null, "Last", PASSWORD}, new String[]{"First", " ", PASSWORD},
                new String[]{"First", "Last", null}, new String[]{"<script>", "Last", PASSWORD})) {
            MvcResult r = accept(ref, form[0], form[1], form[2], randomIp());
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
            assertThat(body(r)).doesNotContain(PASSWORD).doesNotContain("<script>");
        }
        verify(keycloak, never()).createUser(anyString(), anyString(), anyString(), anyString(), any(char[].class),
                any(UUID.class), anyString());
        assertThat(acceptedAt(inviteId)).isNull();
    }

    @Test
    @DisplayName("T-37-18: both public endpoints share the per-IP public rate limit: a flood from one address gets 429")
    void publicRateLimit_floodFromOneAddress_gets429_onBothEndpoints() throws Exception {
        String ip = "198.51.100." + (1 + ThreadLocalRandom.current().nextInt(254));
        int capacity = PUBLIC_RPM + PUBLIC_BURST;
        for (int i = 0; i < capacity; i++) {
            MvcResult r = preview(tenantId + "." + randomToken(), ip);
            assertThat(r.getResponse().getStatus()).as("request %d of %d is inside the bucket", i + 1, capacity)
                    .isEqualTo(404);
        }
        MvcResult limitedPreview = preview(tenantId + "." + randomToken(), ip);
        assertThat(limitedPreview.getResponse().getStatus()).as(body(limitedPreview)).isEqualTo(429);
        assertThat(limitedPreview.getResponse().getHeader("Retry-After")).isNotNull();
        MvcResult limitedAccept = accept(tenantId + "." + randomToken(), "A", "B", PASSWORD, ip);
        assertThat(limitedAccept.getResponse().getStatus()).as("same address, other endpoint").isEqualTo(429);
        MvcResult otherAddress = preview(tenantId + "." + randomToken(), randomIp());
        assertThat(otherAddress.getResponse().getStatus()).as("another address is unaffected").isEqualTo(404);
    }

    @Test
    @DisplayName("T-37-22: neither the password nor the link token reaches any log line, although MVC logs every "
            + "request body it reads, in full, at TRACE")
    void passwordAndToken_neverReachALogLine() throws Exception {
        String email = "quiet@example.com";
        // The secret STARTS with a short unique marker, and the check is on the marker: a logger that
        // truncates a long value still prints its first characters.
        String marker = "Pw" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String secret = marker + "-Unique-Secret!9";
        String ref = seedInvite(tenantId, email, shopA, "STAFF", "1 hour");
        String token = ref.substring(ref.indexOf('.') + 1);
        when(keycloak.findVendorUsersByEmail(VENDOR_REALM, email, "tok")).thenReturn(List.of());
        when(keycloak.createUser(eq(VENDOR_REALM), eq(email), anyString(), anyString(), any(char[].class),
                eq(tenantId), eq("tok"))).thenReturn(UUID.randomUUID().toString());

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        // MVC logs every request body it reads through the DTO's toString: at DEBUG cut to 100 characters,
        // at TRACE in full. Raised to TRACE here, for this test only, so the arm can fail. Two measured
        // reasons: a level set through @DynamicPropertySource never reached MVC's logger (the positive
        // control below went red with zero org.springframework.web lines), and at DEBUG a toString that
        // printed the whole password still passed, because the 100-character cut fell inside the password.
        Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
        ch.qos.logback.classic.Level previousWebLevel = web.getLevel();
        web.setLevel(ch.qos.logback.classic.Level.TRACE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            assertThat(preview(ref, randomIp()).getResponse().getStatus()).isEqualTo(200);
            assertThat(accept(ref, "Quiet", "Person", null, randomIp()).getResponse().getStatus()).isEqualTo(400);
            assertThat(accept(ref, "Quiet", "Person", secret, randomIp()).getResponse().getStatus()).isEqualTo(201);
        } finally {
            root.detachAppender(appender);
            web.setLevel(previousWebLevel);
        }
        List<String> lines = appender.list.stream()
                .map(e -> e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : " " + e.getThrowableProxy().getMessage()))
                .toList();
        // Positive controls: the capture saw MVC's body-read DEBUG line for this DTO and the service's event.
        assertThat(lines).as("MVC logged the request body it read (so this arm can fail)")
                .anyMatch(l -> l.contains("AcceptStaffInviteRequest"));
        assertThat(lines).anyMatch(l -> l.contains("event=staff_invite_accepted"));
        assertThat(lines).as("no log line carries the password, or even its first characters")
                .noneMatch(l -> l.contains(marker));
        assertThat(lines).as("no log line carries the link token").noneMatch(l -> l.contains(token));
    }

    @Test
    @DisplayName("Keycloak unreachable: preview and accept answer 503 staff-invite-account-service-unavailable and "
            + "the invite stays open")
    void keycloakOutage_is503_andTheInviteStaysOpen() throws Exception {
        String ref = seedInvite(tenantId, "outage@example.com", shopA, "STAFF", "1 hour");
        UUID inviteId = lastSeededId;
        when(keycloak.obtainAdminToken()).thenThrow(new KeycloakAdminException("Keycloak admin token request failed"));

        for (MvcResult r : List.of(preview(ref, randomIp()), accept(ref, "Out", "Age", PASSWORD, randomIp()))) {
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(503);
            assertThat(json(r).path("type").asString())
                    .isEqualTo("https://jtoye.uk/errors/staff-invite-account-service-unavailable");
            assertThat(json(r).path("code").asString()).isEqualTo("STAFF_INVITE_ACCOUNT_SERVICE_UNAVAILABLE");
        }
        assertThat(acceptedAt(inviteId)).isNull();
    }

    /** Expired, accepted, cancelled, tenant swapped, unknown token, malformed, missing, offboarded business. */
    List<String> unusableRefs() {
        List<String> refs = new ArrayList<>();
        refs.add(seedInvite(tenantId, "expired@example.com", shopA, "STAFF", "-1 hour"));
        String accepted = seedInvite(tenantId, "used@example.com", shopA, "STAFF", "1 hour");
        UUID acceptedId = lastSeededId;
        pinned(tenantId, j -> j.update("UPDATE staff_invite SET accepted_at = now(), accepted_user_id = ? WHERE id = ?",
                UUID.randomUUID(), acceptedId));
        refs.add(accepted);
        String cancelled = seedInvite(tenantId, "cancelled@example.com", shopA, "STAFF", "1 hour");
        UUID cancelledId = lastSeededId;
        pinned(tenantId, j -> j.update("UPDATE staff_invite SET revoked_at = now(), revoked_by = ? WHERE id = ?",
                admin, cancelledId));
        refs.add(cancelled);
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", otherTenant, "Other " + otherTenant);
        String live = seedInvite(tenantId, "swapped@example.com", shopA, "STAFF", "1 hour");
        refs.add(otherTenant + live.substring(live.indexOf('.')));
        refs.add(tenantId + "." + randomToken());
        refs.add("not-a-reference");
        refs.add(null);
        UUID gone = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", gone, "Gone " + gone);
        UUID goneShop = seedShop(gone, "Gone shop");
        String offboarded = seedInvite(gone, "offboarded@example.com", goneShop, "STAFF", "1 hour");
        jdbc.update("UPDATE tenants SET status = 'OFFBOARDED', offboarded_at = now() WHERE id = ?", gone);
        refs.add(offboarded);
        return refs;
    }

    void assertUnavailableBodies(List<byte[]> bodies) throws Exception {
        JsonNode first = jsonMapper.readTree(bodies.get(0));
        assertThat(first.path("type").asString()).isEqualTo(UNAVAILABLE_TYPE);
        assertThat(first.path("code").asString()).isEqualTo("STAFF_INVITE_UNAVAILABLE");
        assertThat(first.path("status").asInt()).isEqualTo(404);
        assertThat(first.path("detail").asString()).isEqualTo("This invitation can't be used.");
        for (int i = 1; i < bodies.size(); i++) {
            assertThat(new String(bodies.get(i), StandardCharsets.UTF_8))
                    .as("cause #%d must be byte-identical to cause #0", i)
                    .isEqualTo(new String(bodies.get(0), StandardCharsets.UTF_8));
        }
    }

    static String randomToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    MvcResult preview(String ref, String ip) throws Exception {
        String payload = "{\"ref\":" + (ref == null ? "null" : jsonString(ref)) + "}";
        return mockMvc.perform(post(PREVIEW).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                        .content(payload))
                .andReturn();
    }

    MvcResult accept(String ref, String first, String last, String password, String ip) throws Exception {
        StringBuilder payload = new StringBuilder("{\"ref\":").append(ref == null ? "null" : jsonString(ref));
        if (first != null) {
            payload.append(",\"firstName\":").append(jsonString(first));
        }
        if (last != null) {
            payload.append(",\"lastName\":").append(jsonString(last));
        }
        if (password != null) {
            payload.append(",\"password\":").append(jsonString(password));
        }
        payload.append('}');
        return mockMvc.perform(post(ACCEPT).with(from(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                        .content(payload.toString()))
                .andReturn();
    }

    /** Seed an OPEN invitation directly (digest only, as V77 stores it) and return its link ref. */
    String seedInvite(UUID tenant, String email, UUID shopId, String role, String expiresInterval) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = UUID.randomUUID();
        pinned(tenant, j -> j.update("INSERT INTO staff_invite (id, tenant_id, email_normalised, shop_id, role, "
                        + "token_sha256, expires_at, created_by) VALUES (?, ?, ?, ?, ?, ?, now() + CAST(? AS interval), ?)",
                id, tenant, email, shopId, role, sha256Hex(token), expiresInterval, admin));
        lastSeededId = id;
        return tenant + "." + token;
    }

    UUID lastSeededId;

    <T> T pinned(UUID tenant, Function<JdbcTemplate, T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.current_tenant_id', ?, true)", String.class, tenant.toString());
            return work.apply(jdbc);
        });
    }

    Object acceptedAt(UUID inviteId) {
        return pinned(tenantId, j -> j.queryForObject(
                "SELECT accepted_at FROM staff_invite WHERE id = ?", Object.class, inviteId));
    }

    UUID acceptedUserId(UUID inviteId) {
        return pinned(tenantId, j -> j.queryForObject(
                "SELECT accepted_user_id FROM staff_invite WHERE id = ?", UUID.class, inviteId));
    }

    long count(String sql, Object... args) {
        return pinned(tenantId, j -> j.queryForObject(sql, Long.class, args));
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    RequestPostProcessor adminJwt() {
        return jwt().jwt(j -> j.subject(admin.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "admin-" + admin + "@example.com")
                        .claim("name", "Ada Admin")
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    UUID seedShop(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                id, tenant, name, "acc-" + id, "1 Test Street, London, E1 6AN");
        return id;
    }

    List<Invocation> awaitSends(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<Invocation> sends = sends();
        while (sends.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            sends = sends();
        }
        assertThat(sends).as("waited 10 s for the @Async after-commit send").hasSize(expected);
        return sends;
    }

    List<Invocation> sends() {
        return Mockito.mockingDetails(mailSender).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .toList();
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body>";
        }
    }

    String jsonString(String s) {
        return jsonMapper.writeValueAsString(s);
    }
}
