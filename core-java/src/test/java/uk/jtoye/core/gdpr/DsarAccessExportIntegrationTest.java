package uk.jtoye.core.gdpr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.keycloak.CustomerRealmUser;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminProperties;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #778 (P0, decision D-01): a verified Article 15 request is FULFILLED — the worker assembles
 * the subject's data across every tenant, stores it encrypted behind a single-use, expiring token,
 * emails the verified address a link (never the data), and only then reports the request complete.
 *
 * <p>Proven against the real posture: Postgres 15 with every migration applied and the connection role
 * downgraded to NOSUPERUSER, so FORCE row-level security is genuinely enforced (the Testcontainers
 * bootstrap role is a superuser and would bypass it — the #764 history). That is what makes the
 * cross-tenant reach claim falsifiable: the export can only contain a tenant's rows if the worker pinned
 * that tenant, and every precondition below proves the downgraded role SEES the fixture rows first.
 *
 * <p>Keycloak is a mock {@link KeycloakAdminClient} and the mail sender is a mock
 * {@link JavaMailSender}: the email is proven by the captured message's content. The live Mailhog
 * capture and the live realm lookup belong to 31.1-30. Every state assertion reads
 * {@code dsar_request} and {@code dsar_access_export} with plain JDBC, never what a service reports.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// The fan-out is @Scheduled and a fixedDelay task fires once at context refresh (#418): this class
// drives the worker by hand and owns the timeline.
@Import(NoScheduledTriggersTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class DsarAccessExportIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final int MAX_ATTEMPTS = 3;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The admin seam ON, as D-22 runs it in compose. The client is a mock, so the base URL is never
        // dialled; it only has to make KeycloakAdminProperties.configured() true.
        registry.add("jtoye.keycloak.admin.enabled", () -> "true");
        registry.add("jtoye.keycloak.admin.base-url", () -> "http://keycloak.invalid:8080");
        registry.add("jtoye.keycloak.admin.password", () -> "test-only-not-a-secret");
        registry.add("jtoye.gdpr.dsar.max-process-attempts", () -> String.valueOf(MAX_ATTEMPTS));
        // The test profile switches mail off; this class asserts what the subject is sent, so it turns it
        // back on against a mocked sender.
        registry.add("notification.email.enabled", () -> "true");
        // The sender is a mock; Boot's mail health contributor needs a real JavaMailSenderImpl.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    static final String CUSTOMER_REALM = "jtoye-customers";
    static final String TOKEN = "admin-token";
    static final String DOWNLOAD_BASE = "http://localhost:3000/data-request/download";
    static final long TTL_HOURS = 168;
    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";
    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final AtomicInteger IP_COUNTER = new AtomicInteger();
    /** The link the email must carry: the configured base, then the token in the URL FRAGMENT. */
    private static final Pattern LINK = Pattern.compile(
            Pattern.quote(DOWNLOAD_BASE) + "#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_=-])");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper jsonMapper;
    @Autowired DsarFanoutWorker worker;
    @Autowired DsarCipher cipher;
    @Autowired PlatformTransactionManager txManager;
    @Autowired KeycloakAdminProperties adminProperties;
    @PersistenceContext EntityManager entityManager;

    @MockitoBean KeycloakAdminClient keycloak;
    @MockitoBean JavaMailSender mailSender;
    @MockitoSpyBean DsarVerificationMailer verificationMailer;
    @MockitoSpyBean DsarAccessExportService exportService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // The worker claims every VERIFIED request in the table; start each arm from an empty queue.
        jdbc.update("DELETE FROM dsar_request");
        reset(keycloak, mailSender, verificationMailer, exportService);
        adminProperties.setEnabled(true);
        when(keycloak.obtainAdminToken()).thenReturn(TOKEN);
        // Only a superuser may run ALTER ROLE, so this happens exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: the cross-tenant claims below are meaningless if the role still "
                        + "bypasses row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        adminProperties.setEnabled(true);
    }

    // ---- Task 1: the tracer — the #778 acceptance test -------------------------------------------

    /**
     * Tenant A (trader "Zeta Foods Ltd") holds a guest order and a review; tenant B (trader
     * "Adebayo Kitchen Ltd") holds a guest order. The request is lodged and verified through the real
     * public endpoints, and the worker is run once.
     */
    @Test
    void aVerifiedAccessRequestProducesAnEncryptedExportEmailsALinkAndCompletes(CapturedOutput output)
            throws Exception {
        String typed = "  Grace.Access+" + shortId() + "@Example.TEST ";
        String verified = typed.trim();
        String lower = verified.toLowerCase(Locale.ROOT);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN)).thenReturn(List.of());

        UUID a = seedTenant();
        UUID b = seedTenant();
        seedTraderIdentity(a, "Zeta Foods Ltd", "COMPANY");
        seedTraderIdentity(b, "Adebayo Kitchen Ltd", "COMPANY");
        UUID shopA = seedShop(a, "Zeta Jollof House");
        UUID shopB = seedShop(b, "Adebayo Suya Spot");
        String itemA = "Smoky Party Jollof " + shortId();
        String itemB = "Beef Suya Wrap " + shortId();
        Order orderA = seedGuestOrder(a, shopA, verified, "Grace Persona", "07700900456", itemA);
        Order orderB = seedGuestOrder(b, shopB, verified, "Grace Persona", "07700900456", itemB);
        UUID reviewA = seedReview(a, shopA, orderA.id(), verified, "Grace Persona", "the jollof was perfect");

        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders WHERE id = ? AND customer_email IS NOT NULL",
                orderA.id())).as("PRECONDITION: the downgraded role SEES tenant A's order").isEqualTo(1L);
        assertThat(countUnder(b, "SELECT COUNT(*) FROM orders WHERE id = ? AND customer_email IS NOT NULL",
                orderB.id())).as("PRECONDITION: the downgraded role SEES tenant B's order").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders WHERE id = ?", orderB.id()))
                .as("PRECONDITION: RLS is live — tenant A's pin cannot see tenant B's order").isZero();

        UUID requestId = lodgeVerified(typed, "ACCESS");
        worker.executeLodgedRequests();

        RequestRow row = request(requestId);
        assertThat(row.status()).as("the request completes").isEqualTo("COMPLETED");
        assertThat(row.ciphertextPresent()).as("COMPLETED drops the encrypted address").isFalse();

        // The export row: token hash only, an expiry ~ now + 168h, an encrypted payload.
        ExportRow export = export(requestId);
        assertThat(export.tokenSha256()).matches("[0-9a-f]{64}");
        assertThat(export.secondsToExpiry())
                .as("expires_at is ~ now + export-link-ttl-hours (168)")
                .isBetween(TTL_HOURS * 3600 - 300, TTL_HOURS * 3600 + 5);
        assertThat(export.payload()).isNotNull();
        for (String plain : new String[]{verified, lower, "Grace Persona", itemA, orderA.number()}) {
            assertThat(indexOf(export.payload(), plain.getBytes(StandardCharsets.UTF_8)))
                    .as("the stored payload must not contain readable personal data: %s", plain)
                    .isEqualTo(-1);
        }

        // The one email: to the verified address, carrying the link and nothing else of the subject's.
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        SimpleMailMessage message = mail.getValue();
        assertThat(message.getTo()).as("sent to the verified address, as typed and trimmed")
                .containsExactly(verified);
        String body = message.getText();
        Matcher link = LINK.matcher(body);
        assertThat(link.find()).as("the body carries %s#token=<43 base64url chars>: %s", DOWNLOAD_BASE, body)
                .isTrue();
        String token = link.group(1);
        assertThat(sha256Hex(token)).as("the emailed token is the one whose hash was stored")
                .isEqualTo(export.tokenSha256());
        assertThat(body).as("the link works once").containsIgnoringCase("once");
        assertThat(body).as("the link expires in 7 days").contains("7 days");
        // T-31.1-58 / the plan prohibition: no personal data in the email body.
        for (String pii : new String[]{"Grace Persona", "07700900456", orderA.number(), orderB.number(), itemA,
                itemB, "14 Persona Road", "the jollof was perfect"}) {
            assertThat(body).as("the email body must not carry %s", pii).doesNotContain(pii);
        }
        assertThat(message.getText()).doesNotContain("Zeta Foods Ltd").doesNotContain("Adebayo Kitchen Ltd");

        // The decrypted document: two vendor sections, ordered by legal name, A's with its review.
        JsonNode doc = decryptedDocument(requestId);
        assertThat(doc.get("format").asString()).isEqualTo("jtoye-dsar-export/1");
        JsonNode vendors = doc.get("vendors");
        assertThat(vendors.size()).as("one section per tenant that holds the subject").isEqualTo(2);
        assertThat(vendors.get(0).get("recipient").get("legalName").asString()).isEqualTo("Adebayo Kitchen Ltd");
        assertThat(vendors.get(1).get("recipient").get("legalName").asString()).isEqualTo("Zeta Foods Ltd");
        assertThat(vendors.get(0).get("reviews").size()).as("B holds no review").isZero();
        JsonNode zeta = vendors.get(1);
        assertThat(zeta.get("reviews").size()).isEqualTo(1);
        assertThat(zeta.get("reviews").get(0).get("id").asString()).isEqualTo(reviewA.toString());
        assertThat(zeta.get("orders").get(0).get("orderNumber").asString()).isEqualTo(orderA.number());
        assertThat(zeta.get("orders").get(0).get("items").get(0).get("productName").asString()).isEqualTo(itemA);
        assertThat(vendors.get(0).get("orders").get(0).get("orderNumber").asString()).isEqualTo(orderB.number());
        // CONTROL for the email-body absence checks above: the fixture PII really is in the export.
        assertThat(doc.toString()).contains("Grace Persona").contains("07700900456").contains(itemA);

        assertThat(output.getAll()).as("the backlog WARN is gone: ACCESS is now executed")
                .doesNotContain("dsar_access_requests_outstanding");
        assertThat(output.getAll()).as("the address is never logged").doesNotContain(verified);
    }

    // ---- Task 2: complete content, the empty subject, retries, deterministic order ---------------

    /**
     * Every section of a vendor's part of the export, from one tenant that holds the subject as a
     * {@code customers} row, as an order linked only by {@code customer_id} (no address on it), as a
     * guest order with an allergy note and an acknowledged allergen set, as a review with a photo, as a
     * marketing opt-in and an unsubscribe, and as a staff-directory entry — plus the customer-realm
     * sign-in account and the "about" section.
     */
    @Test
    void theExportCarriesEverySectionForAVendorAndThePlatformAccount() throws Exception {
        String verified = "Grace.Full+" + shortId() + "@Example.test";
        String lower = verified.toLowerCase(Locale.ROOT);
        String kcUserId = UUID.randomUUID().toString();
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN))
                .thenReturn(List.of(realmUser(kcUserId, lower)));

        UUID c = seedTenant();
        seedTraderIdentity(c, "Mama Ade's Kitchen Ltd", "COMPANY");
        seedCompanyNumber(c, "16471464");
        UUID shop = seedShop(c, "Mama Ade's Peckham");
        UUID customer = seedCustomer(c, lower, "Grace Customer", "07700900111", 144, "prefers collection");
        Order linked = seedOrder(c, shop, customer, null, "Grace Customer", "07700900111",
                "Egusi Soup " + shortId(), "2026-09-01T12:00:00Z");
        Order guest = seedOrder(c, shop, null, verified, "Grace Guest", "07700900222",
                "Puff Puff " + shortId(), "2026-09-02T12:00:00Z");
        update(c, "UPDATE orders SET allergy_note = 'My child has a severe peanut allergy', "
                + "allergy_note_ack_at = '2026-09-02T12:05:00Z', allergy_note_ack_by = 'kitchen.staff', "
                + "allergen_ack_mask = 65, allergen_ack_at = '2026-09-02T11:59:00Z', placed_via = 'STOREFRONT', "
                + "payment_method = 'CASH' WHERE id = ?", guest.id());
        UUID review = seedReview(c, shop, guest.id(), verified, "Grace G.", "lovely puff puff");
        update(c, "UPDATE reviews SET photo_urls = ARRAY['https://cdn.example.test/r/1.jpg'] WHERE id = ?", review);
        update(c, "INSERT INTO marketing_opt_in (tenant_id, recipient, opted_in_at) VALUES (?, ?, '2026-08-01T09:00:00Z')",
                c, verified);
        update(c, "INSERT INTO notification_suppression (tenant_id, recipient, category, created_at) "
                + "VALUES (?, ?, 'ONBOARDING', '2026-08-02T09:00:00Z')", c, lower);
        update(c, "INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
                + "VALUES (?, ?, ?, 'Grace (staff)', '2026-08-03T09:00:00Z')", c, UUID.randomUUID(), verified);
        assertThat(countUnder(c, "SELECT COUNT(*) FROM orders WHERE id = ? AND customer_email IS NULL", linked.id()))
                .as("PRECONDITION: the linked order carries NO address, so only the customer_id path reaches it")
                .isEqualTo(1L);

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();
        assertThat(request(requestId).status()).isEqualTo("COMPLETED");

        JsonNode doc = decryptedDocument(requestId);
        assertThat(doc.get("requestedFor").asString()).isEqualTo(verified);

        // "about": purposes, recipients, retention pointer, rights, source.
        assertThat(doc.has("about")).as("the export carries an 'about' section").isTrue();
        assertThat(doc.has("platformAccount")).as("the export carries a 'platformAccount' section").isTrue();
        JsonNode about = doc.get("about");
        assertThat(about.get("purposes").size()).isPositive();
        assertThat(about.get("recipients").asString()).isNotBlank();
        assertThat(about.get("retention").asString()).contains("/legal/retention");
        assertThat(about.get("rights").toString()).contains("rectification").contains("erasure").contains("ico.org.uk");
        assertThat(about.get("source").asString()).containsIgnoringCase("you");

        // The platform account, from the customer realm.
        JsonNode account = doc.get("platformAccount");
        assertThat(account.get("status").asString()).isEqualTo("FOUND");
        JsonNode user = account.get("accounts").get(0);
        assertThat(user.get("username").asString()).isEqualTo("grace.persona");
        assertThat(user.get("email").asString()).isEqualTo(lower);
        assertThat(user.get("firstName").asString()).isEqualTo("Grace");
        assertThat(user.get("lastName").asString()).isEqualTo("Persona");
        assertThat(user.get("createdAt").asString()).isEqualTo("2026-10-04T07:46:40Z");

        JsonNode v = singleVendor(doc);
        JsonNode recipient = v.get("recipient");
        assertThat(recipient.get("legalName").asString()).isEqualTo("Mama Ade's Kitchen Ltd");
        assertThat(recipient.get("entityType").asString()).isEqualTo("COMPANY");
        assertThat(recipient.get("companyNumber").asString()).isEqualTo("16471464");
        assertThat(recipient.get("vatNumber").asString()).isEqualTo("GB123456789");
        assertThat(recipient.get("address").get("line1").asString()).isEqualTo("1 Trading Street");
        assertThat(recipient.get("address").get("postcode").asString()).isEqualTo("B2 4QA");
        assertThat(recipient.get("shops").get(0).asString()).isEqualTo("Mama Ade's Peckham");

        JsonNode customerRecord = v.get("customerRecords").get(0);
        assertThat(customerRecord.get("name").asString()).isEqualTo("Grace Customer");
        assertThat(customerRecord.get("email").asString()).isEqualTo(lower);
        assertThat(customerRecord.get("phone").asString()).isEqualTo("07700900111");
        assertThat(texts(customerRecord.get("allergenRestrictions"))).containsExactly("Peanuts", "Nuts");
        assertThat(customerRecord.get("notes").asString()).isEqualTo("prefers collection");

        JsonNode orders = v.get("orders");
        assertThat(orders.size()).as("the customer_id-linked order AND the guest order").isEqualTo(2);
        assertThat(orders.get(0).get("orderNumber").asString()).isEqualTo(linked.number());
        JsonNode g = orders.get(1);
        assertThat(g.get("orderNumber").asString()).isEqualTo(guest.number());
        assertThat(g.get("placedAt").asString()).isEqualTo("2026-09-02T12:00:00Z");
        assertThat(g.get("fulfilmentType").asString()).isEqualTo("DELIVERY");
        assertThat(g.get("customerName").asString()).isEqualTo("Grace Guest");
        assertThat(g.get("customerPhone").asString()).isEqualTo("07700900222");
        assertThat(g.get("deliveryAddress").get("line1").asString()).isEqualTo("14 Persona Road");
        assertThat(g.get("deliveryAddress").get("line2").asString()).isEqualTo("Flat 2");
        assertThat(g.get("deliveryAddress").get("city").asString()).isEqualTo("Birmingham");
        assertThat(g.get("deliveryAddress").get("postcode").asString()).isEqualTo("B1 1AA");
        assertThat(g.get("notes").asString()).isEqualTo("ring the bell twice");
        assertThat(g.get("allergyNote").asString()).isEqualTo("My child has a severe peanut allergy");
        assertThat(g.get("allergyNoteReadByShopAt").asString()).isEqualTo("2026-09-02T12:05:00Z");
        assertThat(g.toString()).as("who in the shop read it is a staff record, not the subject's data")
                .doesNotContain("kitchen.staff");
        assertThat(texts(g.get("acknowledgedAllergens"))).as("catalogue order").containsExactly("Gluten", "Milk");
        assertThat(g.get("acknowledgedAt").asString()).isEqualTo("2026-09-02T11:59:00Z");
        assertThat(g.get("paymentMethod").asString()).isEqualTo("CASH");
        JsonNode totals = g.get("totals");
        assertThat(totals.get("subtotalPennies").asLong()).isEqualTo(1000);
        assertThat(totals.get("vatAmountPennies").asLong()).isEqualTo(167);
        assertThat(totals.get("deliveryFeePennies").asLong()).isEqualTo(250);
        assertThat(totals.get("totalAmountPennies").asLong()).isEqualTo(1250);
        assertThat(g.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(orders.get(0).get("acknowledgedAllergens").isNull())
                .as("an order with no recorded acknowledgement says so (null), never an invented []").isTrue();

        JsonNode r = v.get("reviews").get(0);
        assertThat(r.get("name").asString()).isEqualTo("Grace G.");
        assertThat(r.get("comment").asString()).isEqualTo("lovely puff puff");
        assertThat(r.get("foodRating").asInt()).isEqualTo(5);
        assertThat(r.get("deliveryRating").asInt()).isEqualTo(4);
        assertThat(r.get("orderNumber").asString()).isEqualTo(guest.number());
        assertThat(texts(r.get("photoUrls"))).containsExactly("https://cdn.example.test/r/1.jpg");

        JsonNode prefs = v.get("communicationPreferences");
        assertThat(prefs.get("marketingOptIn").get("optedInAt").asString()).isEqualTo("2026-08-01T09:00:00Z");
        assertThat(prefs.get("unsubscribed").get(0).get("category").asString()).isEqualTo("ONBOARDING");
        assertThat(v.get("staffDirectory").get(0).get("displayName").asString()).isEqualTo("Grace (staff)");
    }

    @Test
    void aTenantWithNoTraderIdentityIsNamedByItsShopsAndSaysSo_andTheAccountIsNoneFound() throws Exception {
        String verified = "no.identity." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        UUID t = seedTenant();
        UUID shop = seedShop(t, "Unnamed Trader Shop " + shortId());
        seedGuestOrder(t, shop, verified, "Grace", "07700900333", "Chin Chin " + shortId());

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();

        JsonNode doc = decryptedDocument(requestId);
        JsonNode recipient = singleVendor(doc).get("recipient");
        assertThat(recipient.get("legalName").isNull()).as("stated as unknown, never invented").isTrue();
        assertThat(recipient.has("traderIdentityOnFile")).as("the recipient says whether an identity is on file")
                .isTrue();
        assertThat(recipient.get("traderIdentityOnFile").asBoolean()).isFalse();
        assertThat(recipient.get("note").asString()).containsIgnoringCase("not");
        assertThat(texts(recipient.get("shops"))).hasSize(1);
        assertThat(doc.get("platformAccount").get("status").asString()).isEqualTo("NONE_FOUND");
        assertThat(doc.get("platformAccount").get("accounts").size()).isZero();
    }

    @Test
    void whenTheAdminSeamIsNotConfiguredThePlatformAccountIsNotChecked_andTheRequestStillCompletes()
            throws Exception {
        String verified = "not.checked." + shortId() + "@example.test";
        adminProperties.setEnabled(false);

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();

        assertThat(request(requestId).status()).isEqualTo("COMPLETED");
        JsonNode notCheckedDoc = decryptedDocument(requestId);
        assertThat(notCheckedDoc.has("platformAccount")).as("the export carries a 'platformAccount' section")
                .isTrue();
        JsonNode account = notCheckedDoc.get("platformAccount");
        assertThat(account.get("status").asString()).isEqualTo("NOT_CHECKED");
        assertThat(account.get("note").asString()).isNotBlank();
        verify(keycloak, org.mockito.Mockito.never()).findUsersByEmail(anyString(), anyString(), anyString());
    }

    @Test
    void aFailedAccountLookupReleasesTheRequest_neverAnAnswerThatSkippedTheAccount() throws Exception {
        String verified = "lookup.down." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN))
                .thenThrow(new uk.jtoye.core.tenant.keycloak.KeycloakAdminException("down"));

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();

        assertThat(request(requestId).status()).isEqualTo("VERIFIED");
        assertThat(exports(requestId)).isEmpty();
        verify(mailSender, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }

    /** Article 15's "we hold nothing" reply — the reason D-19 keeps the address at all. */
    @Test
    void anAddressNoTenantHoldsStillGetsAnExportSayingSo_andCompletes() throws Exception {
        String verified = "nobody." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        seedTenant();

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();

        RequestRow row = request(requestId);
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.ciphertextPresent()).isFalse();
        JsonNode doc = decryptedDocument(requestId);
        assertThat(doc.get("vendors").size()).isZero();
        assertThat(doc.has("summary")).as("the export carries a 'summary' with the no-data statement").isTrue();
        assertThat(doc.get("summary").get("vendorDataHeld").asBoolean()).isFalse();
        assertThat(doc.get("summary").get("statement").asString())
                .containsIgnoringCase("no shop").containsIgnoringCase("hold");
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    /**
     * A failed send releases the request with the export row in place; the next sweep, with mail
     * working, builds a NEW token (the old hash is replaced on the same row) and only then completes.
     */
    @Test
    void aFailedSendReleasesTheRequest_andTheRetryReplacesTheTokenAndCompletes() throws Exception {
        String verified = "mail.down." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        UUID t = seedTenant();
        seedGuestOrder(t, seedShop(t, "Retry Shop"), verified, "Grace", "07700900444", "Akara " + shortId());
        UUID requestId = lodgeVerified(verified, "ACCESS");
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        worker.executeLodgedRequests();

        RequestRow open = request(requestId);
        assertThat(open.status()).as("not complete: the subject does not have the link").isEqualTo("VERIFIED");
        assertThat(open.ciphertextPresent()).as("the address is kept for the retry").isTrue();
        String firstHash = export(requestId).tokenSha256();

        reset(mailSender);
        worker.executeLodgedRequests();

        assertThat(request(requestId).status()).isEqualTo("COMPLETED");
        ExportRow second = export(requestId);
        assertThat(second.tokenSha256()).as("a retry issues a new token; the unsent one is dead").isNotEqualTo(firstHash);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        Matcher link = LINK.matcher(mail.getValue().getText());
        assertThat(link.find()).isTrue();
        assertThat(sha256Hex(link.group(1))).isEqualTo(second.tokenSha256());
    }

    @Test
    void aTenantFailingDuringCollectionReleasesTheRequest_noEmail_noExport() throws Exception {
        String verified = "tenant.fails." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        UUID good = seedTenant();
        UUID bad = seedTenant();
        seedGuestOrder(good, seedShop(good, "Good Shop"), verified, "Grace", "07700900555", "Moi Moi " + shortId());
        org.mockito.Mockito.doThrow(new IllegalStateException("deliberate tenant failure"))
                .when(exportService).collectForTenant(org.mockito.ArgumentMatchers.eq(bad), anyString());

        UUID requestId = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();

        RequestRow row = request(requestId);
        assertThat(row.status()).isEqualTo("VERIFIED");
        assertThat(row.ciphertextPresent()).isTrue();
        assertThat(exports(requestId)).as("a partial answer is not stored").isEmpty();
        verify(mailSender, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }

    /**
     * Vendor sections by legal name then tenant id (null names last), orders by placed time, allergen
     * names in catalogue order — so two exports of the same data are byte-identical apart from
     * generatedAt.
     */
    @Test
    void twoExportsOfTheSameDataAreByteIdenticalApartFromGeneratedAt_andAreDeterministicallyOrdered()
            throws Exception {
        String verified = "ordering." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        UUID named = seedTenant();
        seedTraderIdentity(named, "Beta Bakes Ltd", "COMPANY");
        UUID anon1 = seedTenant();
        UUID anon2 = seedTenant();
        for (UUID t : List.of(anon2, named, anon1)) {
            UUID shop = seedShop(t, "Shop " + shortId());
            // Inserted LATEST first, so insertion order is the reverse of placed order.
            seedOrder(t, shop, null, verified, "Grace", "07700900666", "Late " + shortId(), "2026-09-03T10:00:00Z");
            seedOrder(t, shop, null, verified, "Grace", "07700900666", "Early " + shortId(), "2026-09-01T10:00:00Z");
        }
        update(named, "UPDATE orders SET allergen_ack_mask = 8257, allergen_ack_at = NOW() WHERE tenant_id = ?", named);

        UUID first = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();
        String one = decryptedJson(first);
        UUID second = lodgeVerified(verified, "ACCESS");
        worker.executeLodgedRequests();
        String two = decryptedJson(second);

        JsonNode doc = jsonMapper.readTree(one);
        JsonNode vendors = doc.get("vendors");
        assertThat(vendors.size()).isEqualTo(3);
        assertThat(vendors.get(0).get("recipient").get("legalName").asString()).isEqualTo("Beta Bakes Ltd");
        List<String> anonRefs = new ArrayList<>(List.of(anon1.toString(), anon2.toString()));
        anonRefs.sort(null);
        assertThat(List.of(vendors.get(1).get("reference").asString(), vendors.get(2).get("reference").asString()))
                .as("null legal names last, then by tenant id").isEqualTo(anonRefs);
        JsonNode namedOrders = vendors.get(0).get("orders");
        assertThat(namedOrders.get(0).get("placedAt").asString()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(namedOrders.get(1).get("placedAt").asString()).isEqualTo("2026-09-03T10:00:00Z");
        // 8257 = bits 0, 6, 13: Gluten, Milk, Molluscs — catalogue order, not alphabetical.
        assertThat(texts(namedOrders.get(0).get("acknowledgedAllergens"))).containsExactly("Gluten", "Milk", "Molluscs");

        String generatedAt = "\"generatedAt\":\"[^\"]*\"";
        assertThat(one).as("PRECONDITION: the two documents differ only if generatedAt differs").contains("generatedAt");
        assertThat(one.replaceFirst(generatedAt, "\"generatedAt\":\"X\""))
                .as("byte-identical apart from generatedAt")
                .isEqualTo(two.replaceFirst(generatedAt, "\"generatedAt\":\"X\""));
    }

    @Test
    void anAccessAndAnErasureForTheSameSubjectAreSeparateRequests_neitherMergesIntoTheOther() throws Exception {
        String verified = "both." + shortId() + "@example.test";
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        UUID t = seedTenant();
        Order order = seedGuestOrder(t, seedShop(t, "Both Shop"), verified, "Grace Both", "07700900777",
                "Suya " + shortId());

        UUID access = lodgeVerified(verified, "ACCESS");
        UUID erasure = lodgeVerified(verified, "ERASURE");
        assertThat(access).isNotEqualTo(erasure);
        worker.executeLodgedRequests();

        assertThat(request(access).status()).isEqualTo("COMPLETED");
        assertThat(request(erasure).status()).isEqualTo("COMPLETED");
        assertThat(exports(access)).hasSize(1);
        assertThat(exports(erasure)).as("an erasure produces no export").isEmpty();
        // The access request was lodged first and claimed first (oldest first), so its export holds the
        // data as it was before the erasure ran.
        assertThat(decryptedDocument(access).toString()).contains(order.number()).contains("Grace Both");
        assertThat(countUnder(t, "SELECT COUNT(*) FROM orders WHERE id = ? AND customer_name = '[REDACTED]'",
                order.id())).as("the erasure ran on its own row").isEqualTo(1L);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    JsonNode singleVendor(JsonNode doc) {
        assertThat(doc.get("vendors").size()).as("exactly one vendor section").isEqualTo(1);
        return doc.get("vendors").get(0);
    }

    void seedCompanyNumber(UUID tenant, String companyNumber) {
        update(tenant, "INSERT INTO vendor_onboarding (id, tenant_id, model, company_number) VALUES (?, ?, 'MARKETPLACE', ?)",
                UUID.randomUUID(), tenant, companyNumber);
    }

    UUID seedCustomer(UUID tenant, String email, String name, String phone, int allergenRestrictions, String notes) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, notes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, tenant, name, email, phone, allergenRestrictions, notes);
        return id;
    }

    record Order(UUID id, String number) {
    }

    record RequestRow(String status, boolean ciphertextPresent, int processAttempts) {
    }

    record ExportRow(UUID id, String tokenSha256, long secondsToExpiry, byte[] payload) {
    }

    RequestRow request(UUID id) {
        Map<String, Object> m = jdbc.queryForMap(
                "SELECT status, subject_email_ciphertext IS NOT NULL AS ct, process_attempts "
                        + "FROM dsar_request WHERE id = ?", id);
        return new RequestRow((String) m.get("status"), (Boolean) m.get("ct"),
                ((Number) m.get("process_attempts")).intValue());
    }

    List<ExportRow> exports(UUID requestId) {
        return jdbc.query(
                "SELECT id, token_sha256, EXTRACT(EPOCH FROM (expires_at - NOW()))::bigint AS secs, "
                        + "payload_ciphertext FROM dsar_access_export WHERE dsar_request_id = ?",
                (rs, n) -> new ExportRow((UUID) rs.getObject(1), rs.getString(2), rs.getLong(3), rs.getBytes(4)),
                requestId);
    }

    ExportRow export(UUID requestId) {
        List<ExportRow> rows = exports(requestId);
        assertThat(rows).as("exactly one export row for request %s", requestId).hasSize(1);
        return rows.get(0);
    }

    JsonNode decryptedDocument(UUID requestId) {
        String json = decryptedJson(requestId);
        return jsonMapper.readTree(json);
    }

    String decryptedJson(UUID requestId) {
        // The exact associated-data label the production purpose ACCESS_EXPORT binds to: a payload
        // written for any other purpose or request id fails the tag check here.
        return cipher.decryptWithLabel("ACCESS_EXPORT", requestId, export(requestId).payload());
    }

    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static CustomerRealmUser realmUser(String id, String email) {
        return new CustomerRealmUser(id, "grace.persona", email, "Grace", "Persona", 1791100000000L);
    }

    UUID lodgeVerified(String email, String type) throws Exception {
        String ip = "203.0.113." + (100 + (IP_COUNTER.getAndIncrement() % 100));
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "requestType", type))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(verificationMailer).sendVerification(anyString(), token.capture(), any(), anyLong());
        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("token", token.getValue()))))
                .andExpect(status().isOk());
        // The verification mail went to the mocked sender too; start the outcome assertions clean.
        reset(verificationMailer, mailSender);
        return jdbc.queryForObject("SELECT id FROM dsar_request WHERE status = 'VERIFIED' AND request_type = ? "
                + "ORDER BY received_at DESC LIMIT 1", UUID.class, type);
    }

    UUID seedTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "778-" + id);
        return id;
    }

    void seedTraderIdentity(UUID tenant, String legalName, String entityType) {
        update(tenant, "INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                        + "address_line2, address_city, address_postcode, vat_number) "
                        + "VALUES (?, ?, ?, ?, '1 Trading Street', NULL, 'Birmingham', 'B2 4QA', 'GB123456789')",
                UUID.randomUUID(), tenant, legalName, entityType);
    }

    UUID seedShop(UUID tenant, String name) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                id, tenant, name, "shop-778-" + id, "Test Address");
        return id;
    }

    UUID seedProduct(UUID tenant, String title) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), title, "Rice, tomato");
        return id;
    }

    Order seedGuestOrder(UUID tenant, UUID shop, String email, String name, String phone, String itemName) {
        return seedOrder(tenant, shop, null, email, name, phone, itemName, null);
    }

    /**
     * One order with one item. {@code placedAt} null = the database default (now); otherwise the
     * placed time is set explicitly, so ordering by it is under the test's control.
     */
    Order seedOrder(UUID tenant, UUID shop, UUID customerId, String email, String name, String phone,
                    String itemName, String placedAt) {
        UUID id = UUID.randomUUID();
        String number = "ORD-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        update(tenant, "INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, "
                        + "  customer_email, customer_name, customer_phone, notes, "
                        + "  address_line1, address_line2, address_city, address_postcode, "
                        + "  total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies, "
                        + "  created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, 'ring the bell twice', "
                        + "  '14 Persona Road', 'Flat 2', 'Birmingham', 'B1 1AA', 1250, 250, 1000, 'STANDARD', 167, "
                        + "  COALESCE(?::timestamptz, NOW()), COALESCE(?::timestamptz, NOW()))",
                id, tenant, shop, customerId, number, email, name, phone, placedAt, placedAt);
        UUID product = seedProduct(tenant, itemName);
        update(tenant, "INSERT INTO order_items (id, tenant_id, order_id, product_id, product_name, quantity, "
                        + "  unit_price_pennies, total_price_pennies) VALUES (?, ?, ?, ?, ?, 2, 500, 1000)",
                UUID.randomUUID(), tenant, id, product, itemName);
        return new Order(id, number);
    }

    UUID seedReview(UUID tenant, UUID shop, UUID order, String email, String name, String comment) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, customer_name, "
                        + "  food_rating, delivery_rating, comment) VALUES (?, ?, ?, ?, ?, ?, 5, 4, ?)",
                id, tenant, shop, order, email, name, comment);
        return id;
    }

    long countUnder(UUID tenant, String sql, Object... params) {
        Long n = inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        return n == null ? 0 : n;
    }

    void update(UUID tenant, String sql, Object... params) {
        inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

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

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static List<String> texts(JsonNode array) {
        assertThat(array).as("an array node").isNotNull();
        assertThat(array.isArray()).as("an array, not %s", array).isTrue();
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? null : n.asString()));
        return out;
    }
}
