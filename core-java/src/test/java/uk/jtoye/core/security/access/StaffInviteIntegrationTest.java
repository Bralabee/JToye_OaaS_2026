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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    static final int TTL_HOURS = 48;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The test profile turns mail off; this class is about what is sent.
        registry.add("notification.email.enabled", () -> "true");
        registry.add("notification.email.from", () -> "noreply@platform.jtoye.test");
        registry.add("jtoye.staff.invite.accept-base-url", () -> ACCEPT_BASE);
        // Not the default (72): a key that failed to bind would fall back to 72 and red the expiry assertion.
        registry.add("jtoye.staff.invite.ttl-hours", () -> String.valueOf(TTL_HOURS));
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
        OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC).plusHours(TTL_HOURS);
        assertThat(expiresAt).as("expires_at = now + jtoye.staff.invite.ttl-hours (48, not the default 72)")
                .isBetween(expected.minusMinutes(2), expected.plusMinutes(2));

        MimeMessage mail = SentMail.reparse((MimeMessage) awaitSends(1).get(0).getArgument(0));
        assertThat(SentMail.recipients(mail, Message.RecipientType.TO)).containsExactly("new@example.com");
        assertThat(SentMail.subject(mail)).isEqualTo("Ada Admin invited you to " + tenantName + " on J'Toye");
        String text = SentMail.text(mail);
        Matcher link = Pattern.compile(Pattern.quote(ACCEPT_BASE + "#token=" + tenantId + ".") + "([A-Za-z0-9_-]+)")
                .matcher(text);
        assertThat(link.find()).as("the email carries the accept link {base}#token={tenantId}.{token} (37-08: the token rides in the fragment, never the path): %s", text).isTrue();
        String token = link.group(1);
        assertThat(text).as("the token never rides in the URL path, where request logs would keep it (V75 rule)")
                .doesNotContain(ACCEPT_BASE + "/" + tenantId);
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

    // ---- Task 2: validation and the tenant wall on issue ------------------------------------------

    @Test
    @DisplayName("D-07: a GROUP_ADMIN invitation scoped to a shop is a 400 (the grant() rule), and nothing is stored or sent")
    void issue_groupAdminWithShop_is400() throws Exception {
        MvcResult result = issue(adminJwt(), "ga@example.com", "GROUP_ADMIN", shopA);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(400);
        assertThat(json(result).path("type").asString()).isEqualTo("https://jtoye.uk/errors/invalid-argument");
        assertThat(inviteCount()).isZero();
        Thread.sleep(300);
        assertThat(sends()).isEmpty();
    }

    @Test
    @DisplayName("D-07: a GROUP_ADMIN invitation for all shops is accepted (201)")
    void issue_groupAdminAllShops_is201() throws Exception {
        MvcResult result = issue(adminJwt(), "ga@example.com", "GROUP_ADMIN", null);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat(json(result).path("shopId").isNull()).as(body(result)).isTrue();
        awaitSends(1);
    }

    @Test
    @DisplayName("T-23-12-03: another tenant's shop gets the SAME 404 body as a shop that does not exist")
    void issue_foreignShop_is404_identicalToMissingShop() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", otherTenant, "Other " + otherTenant);
        UUID foreignShop = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version) "
                        + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                foreignShop, otherTenant, "Foreign Shop", "inv-f-" + foreignShop, "2 Test Street, London, E1 6AN");

        MvcResult foreign = issue(adminJwt(), "x@example.com", "STAFF", foreignShop);
        MvcResult missing = issue(adminJwt(), "x@example.com", "STAFF", UUID.randomUUID());

        assertThat(foreign.getResponse().getStatus()).as(body(foreign)).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).as(body(missing)).isEqualTo(404);
        assertThat(body(foreign)).as("not an existence oracle for another tenant's shops").isEqualTo(body(missing));
        assertThat(body(foreign)).doesNotContain(foreignShop.toString());
        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("D-07: an address that is not one valid email is a 400 that does not quote it")
    void issue_invalidEmail_is400() throws Exception {
        MvcResult result = issue(adminJwt(), "not-an-address", "STAFF", shopA);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(400);
        assertThat(body(result)).doesNotContain("not-an-address");
        assertThat(inviteCount()).isZero();
    }

    @Test
    @DisplayName("T-37-15: a SHOP_MANAGER may not issue, list, resend or cancel invitations (403 shop-access-denied)")
    void shopManager_isRefused_everywhere() throws Exception {
        UUID manager = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, tenantId, manager, shopA, "SHOP_MANAGER", "m-" + manager + "@example.com");
        RequestPostProcessor managerJwt = userJwt(manager, "Mo Manager");
        UUID inviteId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);

        MvcResult post = issue(managerJwt, "other@example.com", "STAFF", shopA);
        MvcResult list = mockMvc.perform(get(PATH).with(managerJwt)).andReturn();
        MvcResult resend = mockMvc.perform(post(PATH + "/" + inviteId + "/resend").with(managerJwt)).andReturn();
        MvcResult cancel = mockMvc.perform(delete(PATH + "/" + inviteId).with(managerJwt)).andReturn();

        for (MvcResult r : List.of(post, list, resend, cancel)) {
            assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(403);
            assertThat(json(r).path("type").asString()).as(body(r))
                    .isEqualTo("https://jtoye.uk/errors/shop-access-denied");
        }
        assertThat(inviteCount()).as("the refused issue stored nothing").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM staff_invite WHERE id = ?", Object.class, inviteId))
                .as("the refused cancel and resend changed nothing").isNull();
        Thread.sleep(300);
        assertThat(sends()).as("no email for any refused call").hasSize(1);
    }

    // ---- Task 2: the lifecycle --------------------------------------------------------------------

    @Test
    @DisplayName("D-07: the list derives status: OPEN, then EXPIRED once the expiry passes, never stored")
    void list_derivesOpenThenExpired() throws Exception {
        UUID inviteId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);

        JsonNode open = listed(inviteId);
        assertThat(open.path("status").asString()).as(open.toString()).isEqualTo("OPEN");
        assertThat(open.path("email").asString()).isEqualTo("staff@example.com");
        assertThat(open.path("role").asString()).isEqualTo("STAFF");
        assertThat(open.path("shopId").asString()).isEqualTo(shopA.toString());
        assertThat(open.toString()).as("the list never carries the digest").doesNotContain(digestOf(inviteId));

        // Move the clock past the expiry: the row's expiry is put one second in the past.
        jdbc.update("UPDATE staff_invite SET expires_at = now() - interval '1 second' WHERE id = ?", inviteId);
        assertThat(listed(inviteId).path("status").asString()).isEqualTo("EXPIRED");
        // And just before it: still open (the boundary is "now is before expires_at").
        jdbc.update("UPDATE staff_invite SET expires_at = now() + interval '1 minute' WHERE id = ?", inviteId);
        assertThat(listed(inviteId).path("status").asString()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("D-07: DELETE cancels (204) and the list reads CANCELLED; DELETE again is 204 and keeps the first who/when")
    void cancel_is204_andRepeatable() throws Exception {
        UUID inviteId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);

        MvcResult first = mockMvc.perform(delete(PATH + "/" + inviteId).with(adminJwt())).andReturn();
        assertThat(first.getResponse().getStatus()).as(body(first)).isEqualTo(204);
        Map<String, Object> afterFirst = jdbc.queryForMap(
                "SELECT revoked_at, revoked_by FROM staff_invite WHERE id = ?", inviteId);
        assertThat(afterFirst.get("revoked_at")).isNotNull();
        assertThat(afterFirst.get("revoked_by")).isEqualTo(admin);
        assertThat(listed(inviteId).path("status").asString()).isEqualTo("CANCELLED");

        MvcResult again = mockMvc.perform(delete(PATH + "/" + inviteId).with(adminJwt())).andReturn();
        assertThat(again.getResponse().getStatus()).as(body(again)).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT revoked_at, revoked_by FROM staff_invite WHERE id = ?", inviteId))
                .as("a repeated cancel changes nothing").isEqualTo(afterFirst);

        MvcResult unknown = mockMvc.perform(delete(PATH + "/" + UUID.randomUUID()).with(adminJwt())).andReturn();
        assertThat(unknown.getResponse().getStatus()).as(body(unknown)).isEqualTo(404);
    }

    @Test
    @DisplayName("D-07: after a cancel, issuing the same triple creates a new invitation (201) with its own email")
    void issue_afterCancel_isNew() throws Exception {
        String firstId = json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString();
        awaitSends(1);
        mockMvc.perform(delete(PATH + "/" + firstId).with(adminJwt())).andReturn();

        MvcResult again = issue(adminJwt(), "staff@example.com", "STAFF", shopA);
        assertThat(again.getResponse().getStatus()).as(body(again)).isEqualTo(201);
        assertThat(json(again).path("id").asString()).isNotEqualTo(firstId);
        awaitSends(2);
    }

    @Test
    @DisplayName("D-07: issuing a triple whose only live invitation has expired creates a new one and revokes the expired row")
    void issue_afterExpiry_isNew_andRevokesExpired() throws Exception {
        UUID firstId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);
        jdbc.update("UPDATE staff_invite SET expires_at = now() - interval '1 second' WHERE id = ?", firstId);

        MvcResult again = issue(adminJwt(), "staff@example.com", "STAFF", shopA);
        assertThat(again.getResponse().getStatus()).as(body(again)).isEqualTo(201);
        assertThat(json(again).path("id").asString()).isNotEqualTo(firstId.toString());
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM staff_invite WHERE id = ?", Object.class, firstId))
                .as("a triple never has two live rows").isNotNull();
        awaitSends(2);
    }

    @Test
    @DisplayName("D-07: resend revokes the old row (its link dies) and issues a new invitation (201) with a new link")
    void resend_revokesOld_andIssuesNew() throws Exception {
        UUID oldId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "SHOP_MANAGER", shopA)).path("id").asString());
        String oldToken = linkToken(awaitSends(1).get(0));
        String oldDigest = digestOf(oldId);

        MvcResult resent = mockMvc.perform(post(PATH + "/" + oldId + "/resend").with(adminJwt())).andReturn();
        assertThat(resent.getResponse().getStatus()).as(body(resent)).isEqualTo(201);
        JsonNode fresh = json(resent);
        UUID newId = UUID.fromString(fresh.path("id").asString());
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(fresh.path("status").asString()).isEqualTo("OPEN");
        assertThat(fresh.path("email").asString()).isEqualTo("staff@example.com");
        assertThat(fresh.path("role").asString()).isEqualTo("SHOP_MANAGER");
        assertThat(fresh.path("shopId").asString()).isEqualTo(shopA.toString());

        Map<String, Object> old = jdbc.queryForMap("SELECT revoked_at, revoked_by FROM staff_invite WHERE id = ?", oldId);
        assertThat(old.get("revoked_at")).as("the old link is dead").isNotNull();
        assertThat(old.get("revoked_by")).isEqualTo(admin);
        assertThat(listed(oldId).path("status").asString()).isEqualTo("CANCELLED");

        String newToken = linkToken(awaitSends(2).get(1));
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(sha256Hex(newToken)).isEqualTo(digestOf(newId));
        assertThat(sha256Hex(oldToken)).isEqualTo(oldDigest);
    }

    @Test
    @DisplayName("D-07: an accepted invitation cannot be sent again (400) and a cancel leaves it ACCEPTED")
    void accepted_isFinal() throws Exception {
        UUID inviteId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);
        jdbc.update("UPDATE staff_invite SET accepted_at = now(), accepted_user_id = ? WHERE id = ?",
                UUID.randomUUID(), inviteId);

        MvcResult resend = mockMvc.perform(post(PATH + "/" + inviteId + "/resend").with(adminJwt())).andReturn();
        assertThat(resend.getResponse().getStatus()).as(body(resend)).isEqualTo(400);
        MvcResult cancel = mockMvc.perform(delete(PATH + "/" + inviteId).with(adminJwt())).andReturn();
        assertThat(cancel.getResponse().getStatus()).as(body(cancel)).isEqualTo(204);
        assertThat(listed(inviteId).path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(inviteCount()).isEqualTo(1L);
        Thread.sleep(300);
        assertThat(sends()).hasSize(1);
    }

    @Test
    @DisplayName("D-07: a Group admin of another tenant cannot see, cancel or resend this tenant's invitation")
    void otherTenantsAdmin_cannotReachTheInvite() throws Exception {
        UUID inviteId = UUID.fromString(json(issue(adminJwt(), "staff@example.com", "STAFF", shopA)).path("id").asString());
        awaitSends(1);
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", otherTenant, "Other " + otherTenant);
        UUID otherAdmin = UUID.randomUUID();
        ShopGrants.grantOperator(jdbc, otherTenant, otherAdmin, null, "GROUP_ADMIN", "oa-" + otherAdmin + "@example.com");
        RequestPostProcessor otherJwt = jwt().jwt(j -> j.subject(otherAdmin.toString())
                        .claim("tenant_id", otherTenant.toString())
                        .claim("realm_access", Map.of("roles", List.of("user"))))
                .authorities(new KeycloakRealmRoleConverter());

        MvcResult list = mockMvc.perform(get(PATH).with(otherJwt)).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        assertThat(body(list)).doesNotContain(inviteId.toString());
        assertThat(mockMvc.perform(delete(PATH + "/" + inviteId).with(otherJwt)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mockMvc.perform(post(PATH + "/" + inviteId + "/resend").with(otherJwt)).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM staff_invite WHERE id = ?", Object.class, inviteId))
                .isNull();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    JsonNode listed(UUID inviteId) throws Exception {
        MvcResult list = mockMvc.perform(get(PATH).with(adminJwt()).accept(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(list.getResponse().getStatus()).as(body(list)).isEqualTo(200);
        JsonNode root = json(list);
        assertThat(root.isArray()).as(body(list)).isTrue();
        for (JsonNode node : root) {
            if (inviteId.toString().equals(node.path("id").asString())) {
                return node;
            }
        }
        throw new AssertionError("invite " + inviteId + " not listed: " + body(list));
    }

    long inviteCount() {
        return jdbc.queryForObject("SELECT count(*) FROM staff_invite WHERE tenant_id = ?", Long.class, tenantId);
    }

    String digestOf(UUID inviteId) {
        return jdbc.queryForObject("SELECT token_sha256 FROM staff_invite WHERE id = ?", String.class, inviteId);
    }

    String linkToken(Invocation send) {
        String text = SentMail.text(SentMail.reparse((MimeMessage) send.getArgument(0)));
        Matcher link = Pattern.compile(Pattern.quote(ACCEPT_BASE + "#token=" + tenantId + ".") + "([A-Za-z0-9_-]+)")
                .matcher(text);
        assertThat(link.find()).as(text).isTrue();
        return link.group(1);
    }

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
