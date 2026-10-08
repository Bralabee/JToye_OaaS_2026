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
        // MVC logs every request body it reads at DEBUG (through the DTO's toString). Turned on so the
        // "the password never reaches a log line" arm can actually fail.
        registry.add("logging.level.org.springframework.web", () -> "DEBUG");
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
