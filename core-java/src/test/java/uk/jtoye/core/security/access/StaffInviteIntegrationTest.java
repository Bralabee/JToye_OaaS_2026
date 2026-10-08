package uk.jtoye.core.security.access;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import uk.jtoye.core.testsupport.SentMail;
import uk.jtoye.core.testsupport.ShopGrants;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-07 (Phase 37-07, RWO-004 / RWO-003): a Group admin invites a person by email with the exact grant
 * they will receive.
 *
 * <p>What is proven here, over HTTP, with the stored row read back by SQL:
 * <ul>
 *   <li>the invite is stored ONLY as the SHA-256 of a 32-byte random token (the V62/V75 rule: a
 *       readable token at rest is a bearer credential), with the issuer, the grant it carries and an
 *       expiry from {@code jtoye.staff.invite.ttl-hours};</li>
 *   <li>the token leaves the server once, inside the link of one plain-text email to the normalised
 *       address, and the emailed token hashes to the stored digest;</li>
 *   <li>issuing the same (email, shop, role) again while that invite is open is a replay: 200, the same
 *       invite, and no second email.</li>
 * </ul>
 *
 * <p>The mail sender is a Mockito double that captures the built {@link MimeMessage}; the message is
 * serialised to RFC 5322 bytes and parsed back ({@link SentMail}), so the assertions are about what an
 * SMTP server would receive. The send is {@code @Async} and runs after commit, so the test waits for it
 * with a deadline. The Testcontainers role is a SUPERUSER here, so the SQL reads see every row; the
 * NOSUPERUSER wall is {@code StaffInviteRlsIntegrationTest}'s job.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class StaffInviteIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final String ACCEPT_BASE = "https://app.jtoye.test/invite";
    static final String PATH = "/api/v1/staff/invites";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The test profile turns mail off; this class is about what is sent.
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> "noreply@platform.jtoye.test");
        registry.add("jtoye.staff.invite.accept-base-url", () -> ACCEPT_BASE);
        registry.add("jtoye.staff.invite.ttl-hours", () -> "72");
        // The sender is a mock; Boot's mail health contributor needs a real JavaMailSenderImpl.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @MockitoBean private JavaMailSender mailSender;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;

    private UUID tenantId;
    private String tenantName;
    private UUID shopA;
    private UUID admin;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        Mockito.reset(mailSender);
        when(mailSender.createMimeMessage())
                .thenAnswer(inv -> new MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        tenantId = UUID.randomUUID();
        tenantName = "Mama Ade's Kitchen Group " + tenantId.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenantId, tenantName);
        shopA = seedShop("A");
        admin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, admin, null, "GROUP_ADMIN", "admin-" + admin + "@example.com");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Task 1 (tracer): the invite is a digest at rest and a link in one email -----------------

    @Test
    @DisplayName("D-07: a Group admin's invite is stored as a SHA-256 digest with its grant and expiry, "
            + "and the token arrives once, in a link, in one email to the normalised address")
    void issue_storesDigestOnly_andEmailsOneLink() throws Exception {
        MvcResult created = issue(adminJwt(), "New@Example.com ", "SHOP_MANAGER", shopA);
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        JsonNode invite = json(created);
        UUID inviteId = UUID.fromString(invite.path("id").asString());
        assertThat(invite.path("status").asString()).as(body(created)).isEqualTo("OPEN");
        assertThat(invite.path("email").asString()).as(body(created)).isEqualTo("new@example.com");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, email_normalised, shop_id, role, token_sha256, expires_at, created_by, "
                        + "accepted_at, revoked_at FROM staff_invite WHERE id = ?", inviteId);
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat(row.get("email_normalised")).as("trimmed and lower-cased").isEqualTo("new@example.com");
        assertThat(row.get("shop_id")).isEqualTo(shopA);
        assertThat(row.get("role")).isEqualTo("SHOP_MANAGER");
        assertThat(row.get("created_by")).as("who issued it").isEqualTo(admin);
        assertThat(row.get("accepted_at")).isNull();
        assertThat(row.get("revoked_at")).isNull();
        String digest = (String) row.get("token_sha256");
        assertThat(digest).as("a 64-hex SHA-256 digest").matches("[0-9a-f]{64}");
        OffsetDateTime expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant().atOffset(ZoneOffset.UTC);
        OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC).plusHours(72);
        assertThat(expiresAt).as("expires_at = now + ttl-hours (72)")
                .isBetween(expected.minusMinutes(2), expected.plusMinutes(2));

        MimeMessage mail = SentMail.reparse((MimeMessage) awaitSends(1).get(0).getArgument(0));
        assertThat(SentMail.recipients(mail, Message.RecipientType.TO)).containsExactly("new@example.com");
        assertThat(SentMail.subject(mail)).isEqualTo("Ada Admin invited you to " + tenantName + " on J'Toye");
        String text = SentMail.text(mail);
        Matcher link = Pattern.compile(Pattern.quote(ACCEPT_BASE + "/" + tenantId + ".") + "([A-Za-z0-9_-]+)")
                .matcher(text);
        assertThat(link.find()).as("the email carries the accept link {base}/{tenantId}.{token}: %s", text).isTrue();
        String token = link.group(1);
        assertThat(token).as("32 random bytes, unpadded base64url").hasSize(43);
        assertThat(sha256Hex(token)).as("the emailed token hashes to the stored digest").isEqualTo(digest);

        String wholeRow = jdbc.queryForObject("SELECT row_to_json(s)::text FROM staff_invite s WHERE id = ?",
                String.class, inviteId);
        assertThat(wholeRow).as("the readable token is stored nowhere in the row").doesNotContain(token);
        assertThat(body(created)).as("the readable token is not returned to the issuer").doesNotContain(token);
        assertThat(body(created)).as("nor is its digest").doesNotContain(digest);
    }

    @Test
    @DisplayName("D-07: issuing the same (email, shop, role) while it is open replays it: 200, same id, no second email")
    void issue_sameTriple_replays_withoutSecondEmail() throws Exception {
        MvcResult first = issue(adminJwt(), "New@Example.com ", "SHOP_MANAGER", shopA);
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(201);
        awaitSends(1);

        MvcResult replay = issue(adminJwt(), "new@example.com", "SHOP_MANAGER", shopA);
        assertThat(replay.getResponse().getStatus()).as(body(replay)).isEqualTo(200);
        assertThat(json(replay).path("id").asString()).isEqualTo(json(first).path("id").asString());

        Thread.sleep(750);
        assertThat(sends()).as("a replay sends no second email").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_invite WHERE tenant_id = ?", Long.class, tenantId))
                .as("one row for the triple").isEqualTo(1L);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    MvcResult issue(RequestPostProcessor caller, String email, String role, UUID shopId) throws Exception {
        String payload = "{\"email\":" + jsonString(email) + ",\"role\":" + jsonString(role)
                + ",\"shopId\":" + (shopId == null ? "null" : jsonString(shopId.toString())) + "}";
        return mockMvc.perform(post(PATH).with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                        .content(payload))
                .andReturn();
    }

    RequestPostProcessor adminJwt() {
        return userJwt(admin, "Ada Admin");
    }

    RequestPostProcessor userJwt(UUID sub, String name) {
        return jwt().jwt(j -> j.subject(sub.toString())
                        .claim("tenant_id", tenantId.toString())
                        .claim("email", "user-" + sub + "@example.com")
                        .claim("name", name)
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    UUID seedShop(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                id, tenantId, "Invite Shop " + label, "inv-" + id, "1 Test Street, London, E1 6AN");
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

    static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
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

    static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
