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
import org.springframework.dao.DataIntegrityViolationException;
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
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.tenant.keycloak.CustomerAccountDeletionService;
import uk.jtoye.core.tenant.keycloak.CustomerRealmUser;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminClient;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminException;
import uk.jtoye.core.tenant.keycloak.KeycloakAdminProperties;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #777 / D-03 through the real worker on a real Postgres: a verified ERASURE ends with the
 * subject's customer-realm sign-in account deleted, and the request is reported complete only once
 * that deletion is confirmed or the account is proven absent.
 *
 * <p>Keycloak itself is replaced by a mock {@link KeycloakAdminClient}, so the REAL
 * {@code CustomerAccountDeletionService} runs against it with the admin seam configured. What the
 * client sends over the wire is proven separately, by request content, in
 * {@code KeycloakAdminClientTest}; the live "can no longer sign in" probe belongs to 31.1-30.
 *
 * <p>Every state assertion reads {@code dsar_request} and {@code orders} with plain JDBC, never
 * what a service reports about itself.
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
class DsarAccountDeletionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    static final int MAX_ATTEMPTS = 3;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // The admin seam ON, as D-22 runs it in compose. The client is a mock, so the base URL is
        // never dialled; it only has to make KeycloakAdminProperties.configured() true.
        registry.add("jtoye.keycloak.admin.enabled", () -> "true");
        registry.add("jtoye.keycloak.admin.base-url", () -> "http://keycloak.invalid:8080");
        registry.add("jtoye.keycloak.admin.password", () -> "test-only-not-a-secret");
        registry.add("jtoye.gdpr.dsar.max-process-attempts", () -> String.valueOf(MAX_ATTEMPTS));
        // The test profile switches mail off; this class asserts what the subject is sent, so it
        // turns it back on against a mocked sender.
        registry.add("notification.email.enabled", () -> "true");
        // The sender is a mock (no SMTP server), and Boot's mail health contributor requires a real
        // JavaMailSenderImpl bean; without this the context refuses to load.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    static final String CUSTOMER_REALM = "jtoye-customers";
    static final String TOKEN = "admin-token";
    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";
    private static final String REDACTED = "[REDACTED]";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper jsonMapper;
    @Autowired DsarFanoutWorker worker;
    @Autowired PlatformTransactionManager txManager;
    @PersistenceContext EntityManager entityManager;

    @MockitoBean KeycloakAdminClient keycloak;
    @MockitoBean JavaMailSender mailSender;
    @MockitoSpyBean DsarVerificationMailer verificationMailer;
    @MockitoSpyBean CustomerAccountDeletionService accountDeletion;
    @Autowired KeycloakAdminProperties adminProperties;

    private static final java.util.concurrent.atomic.AtomicInteger IP_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM dsar_request");
        reset(keycloak, mailSender, verificationMailer, accountDeletion);
        adminProperties.setEnabled(true);
        when(keycloak.obtainAdminToken()).thenReturn(TOKEN);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        adminProperties.setEnabled(true);
    }

    // ---- Task 1: the tracer ----------------------------------------------------------------------

    @Test
    void aVerifiedErasureDeletesTheSubjectsCustomerRealmAccountAfterTheErasureCommits() throws Exception {
        String typed = "  Grace.Persona+" + shortId() + "@Example.TEST  ";
        String verified = typed.trim();
        String lower = verified.toLowerCase(Locale.ROOT);
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        String kcUserId = UUID.randomUUID().toString();
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN))
                .thenReturn(List.of(realmUser(kcUserId, lower)));
        // Read the order AS THE DELETE HAPPENS, in a separate transaction: if the erasure had not
        // committed yet, this read would still see the address.
        AtomicReference<String> emailSeenAtDelete = new AtomicReference<>("<delete never called>");
        doAnswer(inv -> {
            emailSeenAtDelete.set(orderEmail(fx.tenant(), fx.order()));
            return true;
        }).when(keycloak).deleteUser(CUSTOMER_REALM, kcUserId, TOKEN);

        UUID requestId = lodgeVerifiedErasure(typed);
        worker.executeLodgedRequests();

        verify(keycloak, times(1)).deleteUser(CUSTOMER_REALM, kcUserId, TOKEN);
        verify(keycloak).findUsersByEmail(CUSTOMER_REALM, lower, TOKEN);
        verify(keycloak, never()).findUsersByEmail(eq("jtoye-dev"), anyString(), anyString());
        assertThat(emailSeenAtDelete.get())
                .as("the order's address when the account was deleted: the erasure had committed")
                .isNull();
        assertThat(orderName(fx.tenant(), fx.order())).as("order PII anonymised").isEqualTo(REDACTED);
        RequestRow row = request(requestId);
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.accountDeletionStatus()).isEqualTo("DELETED");
        assertThat(row.ciphertextPresent()).as("COMPLETED drops the encrypted address").isFalse();
    }

    @Test
    void aSubjectWithNoRealmAccountCompletesAsNoneFound() throws Exception {
        String verified = "no.account." + shortId() + "@example.test";
        seedSubjectWithAGuestOrder(verified);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());

        UUID requestId = lodgeVerifiedErasure(verified);
        worker.executeLodgedRequests();

        verify(keycloak).findUsersByEmail(CUSTOMER_REALM, verified, TOKEN);
        verify(keycloak, never()).deleteUser(anyString(), anyString(), anyString());
        RequestRow row = request(requestId);
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.accountDeletionStatus()).isEqualTo("NONE_FOUND");
        assertThat(row.ciphertextPresent()).isFalse();
    }

    // ---- Task 2: outage, retry, exhaustion, not configured, interruption, the email -------------

    /**
     * A Keycloak outage costs a retry and nothing else: the erasure has committed and STAYS committed
     * (asserted by SQL while the request is still open), and the next healthy sweep re-runs a harmless
     * erasure (no second record), deletes the account and only then completes and tells the subject.
     */
    @Test
    void anOutageLeavesTheDataErasedAndTheRequestOutstanding_andTheNextSweepCompletesIt() throws Exception {
        String typed = "  Outage.Subject+" + shortId() + "@Example.test ";
        String verified = typed.trim();
        String lower = verified.toLowerCase(Locale.ROOT);
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        String kcUserId = UUID.randomUUID().toString();
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN))
                .thenThrow(new KeycloakAdminException("Keycloak user email search failed for realm=" + CUSTOMER_REALM));

        UUID requestId = lodgeVerifiedErasure(typed);
        worker.executeLodgedRequests();

        RequestRow open = request(requestId);
        assertThat(open.accountDeletionStatus()).as("the failed attempt is recorded").isEqualTo("OUTSTANDING");
        assertThat(open.status()).as("released for retry, not completed").isEqualTo("VERIFIED");
        assertThat(open.accountDeletionAttempts()).isEqualTo(1);
        assertThat(open.ciphertextPresent()).as("the address is kept for the retry").isTrue();
        assertThat(orderEmail(fx.tenant(), fx.order())).as("the erasure was NOT rolled back").isNull();
        assertThat(orderName(fx.tenant(), fx.order())).isEqualTo(REDACTED);
        assertThat(erasureRecordCount(fx.tenant())).isEqualTo(1);
        verify(keycloak, never()).deleteUser(anyString(), anyString(), anyString());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        // Keycloak is back.
        reset(keycloak);
        when(keycloak.obtainAdminToken()).thenReturn(TOKEN);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN)).thenReturn(List.of(realmUser(kcUserId, lower)));
        when(keycloak.deleteUser(CUSTOMER_REALM, kcUserId, TOKEN)).thenReturn(true);

        worker.executeLodgedRequests();

        RequestRow done = request(requestId);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.accountDeletionStatus()).isEqualTo("DELETED");
        assertThat(done.accountDeletionAttempts()).isEqualTo(2);
        assertThat(done.ciphertextPresent()).isFalse();
        assertThat(erasureRecordCount(fx.tenant())).as("the retry's erasure matched nothing: no second record")
                .isEqualTo(1);
        verify(keycloak, times(1)).deleteUser(CUSTOMER_REALM, kcUserId, TOKEN);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        assertThat(mail.getValue().getTo()).as("sent to the verified address, as typed and trimmed")
                .containsExactly(verified);
    }

    @Test
    void aDeletionThatFailsOnEverySweepIsParkedFailedLoudly_andNoCompletionEmailIsSent(CapturedOutput output)
            throws Exception {
        String verified = "always.down." + shortId() + "@example.test";
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN))
                .thenThrow(new KeycloakAdminException("Keycloak user email search failed for realm=" + CUSTOMER_REALM));

        UUID requestId = lodgeVerifiedErasure(verified);
        for (int sweep = 1; sweep <= MAX_ATTEMPTS; sweep++) {
            worker.executeLodgedRequests();
            if (sweep < MAX_ATTEMPTS) {
                assertThat(request(requestId).status()).as("still open after sweep %d", sweep).isEqualTo("VERIFIED");
            }
        }

        RequestRow row = request(requestId);
        assertThat(output.getAll()).as("the exhaustion is an ERROR naming the request")
                .containsPattern("ERROR.*event=dsar_account_deletion_exhausted request=" + requestId);
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.accountDeletionStatus()).isEqualTo("OUTSTANDING");
        assertThat(row.accountDeletionAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.ciphertextPresent()).as("FAILED drops the encrypted address").isFalse();
        assertThat(orderEmail(fx.tenant(), fx.order())).as("the data stays erased").isNull();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(output.getAll()).as("the address is never logged").doesNotContain(verified);
    }

    @Test
    void anUnconfiguredAdminSeamIsOutstanding_neverComplete() throws Exception {
        String verified = "not.configured." + shortId() + "@example.test";
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        adminProperties.setEnabled(false);

        UUID requestId = lodgeVerifiedErasure(verified);
        worker.executeLodgedRequests();

        RequestRow row = request(requestId);
        assertThat(row.accountDeletionStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(row.status()).isEqualTo("VERIFIED");
        assertThat(row.ciphertextPresent()).isTrue();
        assertThat(orderEmail(fx.tenant(), fx.order())).as("the data is erased regardless").isNull();
        verify(keycloak, never()).findUsersByEmail(anyString(), anyString(), anyString());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    /**
     * A sweep that breaks between the erasure commit and the Keycloak call (here: the deletion step
     * itself throws, which its contract forbids) leaves the request released, and the retry re-runs a
     * harmless erasure and then the deletion.
     */
    @Test
    void aSweepInterruptedAfterTheErasureCommitIsReleased_andTheRetryFinishesIt() throws Exception {
        String verified = "interrupted." + shortId() + "@example.test";
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());
        doThrow(new IllegalStateException("simulated interruption"))
                .doCallRealMethod()
                .when(accountDeletion).deleteCustomerAccount(anyString());

        UUID requestId = lodgeVerifiedErasure(verified);
        worker.executeLodgedRequests();

        RequestRow open = request(requestId);
        assertThat(open.accountDeletionStatus()).isEqualTo("OUTSTANDING");
        assertThat(open.status()).isEqualTo("VERIFIED");
        assertThat(orderEmail(fx.tenant(), fx.order())).isNull();

        worker.executeLodgedRequests();

        RequestRow done = request(requestId);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.accountDeletionStatus()).isEqualTo("NONE_FOUND");
        assertThat(erasureRecordCount(fx.tenant())).isEqualTo(1);
    }

    @Test
    void theCompletionEmailTellsTheSubjectWhatWasErasedAndKept_andNamesNoVendor() throws Exception {
        String typed = " Mail.Subject+" + shortId() + "@Example.test ";
        String verified = typed.trim();
        String lower = verified.toLowerCase(Locale.ROOT);
        Fixture fx = seedSubjectWithAGuestOrder(verified);
        String kcUserId = UUID.randomUUID().toString();
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, lower, TOKEN)).thenReturn(List.of(realmUser(kcUserId, lower)));
        when(keycloak.deleteUser(CUSTOMER_REALM, kcUserId, TOKEN)).thenReturn(true);

        lodgeVerifiedErasure(typed);
        worker.executeLodgedRequests();

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        SimpleMailMessage m = mail.getValue();
        assertThat(m.getTo()).containsExactly(verified);
        assertThat(m.getSubject()).isNotBlank();
        String body = m.getText();
        assertThat(body).as("what was kept and why").contains("order and tax records");
        assertThat(body).as("the sign-in account outcome").contains("sign-in account has been deleted");
        assertThat(body).as("the vendor is never named").doesNotContain(fx.shopName());
        assertThat(body).doesNotContain(fx.tenant().toString());
        assertThat(body).doesNotContain(fx.shop().toString());
        // CONTROL: the shop name really is in the fixture, so the absence above is about the email.
        assertThat(fx.shopName()).startsWith("Persona Jollof Kitchen ");
    }

    @Test
    void theCompletionEmailSaysSoWhenThereWasNoAccount() throws Exception {
        String verified = "no.account.mail." + shortId() + "@example.test";
        seedSubjectWithAGuestOrder(verified);
        when(keycloak.findUsersByEmail(CUSTOMER_REALM, verified, TOKEN)).thenReturn(List.of());

        lodgeVerifiedErasure(verified);
        worker.executeLodgedRequests();

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(1)).send(mail.capture());
        assertThat(mail.getValue().getText()).contains("no sign-in account");
        assertThat(mail.getValue().getText()).doesNotContain("sign-in account has been deleted");
    }

    @Test
    void v72RefusesAnUnknownAccountDeletionStatus_andStartsAttemptsAtZero() throws Exception {
        String verified = "v72." + shortId() + "@example.test";
        UUID requestId = lodgeVerifiedErasure(verified);
        assertThat(request(requestId).accountDeletionAttempts()).isZero();
        assertThat(request(requestId).accountDeletionStatus()).as("no attempt yet").isNull();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dsar_request SET account_deletion_status = 'MAYBE' WHERE id = ?", requestId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_dsar_request_account_deletion_status");
        // CONTROL: a listed value is accepted, so the refusal above is the vocabulary CHECK.
        assertThat(jdbc.update("UPDATE dsar_request SET account_deletion_status = 'OUTSTANDING' WHERE id = ?",
                requestId)).isEqualTo(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    record Fixture(UUID tenant, UUID shop, String shopName, UUID order) {
    }

    record RequestRow(String status, String accountDeletionStatus, int accountDeletionAttempts,
                      boolean ciphertextPresent, int processAttempts) {
    }

    RequestRow request(UUID id) {
        Map<String, Object> m = jdbc.queryForMap(
                "SELECT status, account_deletion_status, account_deletion_attempts, "
                        + "subject_email_ciphertext IS NOT NULL AS ct, process_attempts "
                        + "FROM dsar_request WHERE id = ?", id);
        return new RequestRow((String) m.get("status"), (String) m.get("account_deletion_status"),
                ((Number) m.get("account_deletion_attempts")).intValue(), (Boolean) m.get("ct"),
                ((Number) m.get("process_attempts")).intValue());
    }

    static CustomerRealmUser realmUser(String id, String email) {
        return new CustomerRealmUser(id, email, email, "Grace", "Persona", 1791100000000L);
    }

    static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    Fixture seedSubjectWithAGuestOrder(String email) {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "777-kc-" + tenant);
        UUID shop = UUID.randomUUID();
        String shopName = "Persona Jollof Kitchen " + shortId();
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                shop, tenant, shopName, "shop-777kc-" + shop, "Test Address");
        UUID order = UUID.randomUUID();
        update(tenant, "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_email, "
                        + "  customer_name, customer_phone, notes, address_line1, address_city, address_postcode, "
                        + "  total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, 'Grace Persona', '07700900456', 'ring twice', "
                        + "  '14 Persona Road', 'Birmingham', 'B1 1AA', 1250, 250, 1000, 'STANDARD', 167)",
                order, tenant, shop, "ORD-" + order.toString().substring(0, 8), email);
        return new Fixture(tenant, shop, shopName, order);
    }

    UUID lodgeVerifiedErasure(String email) throws Exception {
        String ip = "203.0.113." + (100 + (IP_COUNTER.getAndIncrement() % 100));
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "requestType", "ERASURE"))))
                .andExpect(status().isAccepted());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(verificationMailer).sendVerification(anyString(), token.capture(), any(), anyLong());
        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("token", token.getValue()))))
                .andExpect(status().isOk());
        // The verification mail went to the mocked sender too; start the outcome assertions clean.
        reset(verificationMailer, mailSender);
        return jdbc.queryForObject("SELECT id FROM dsar_request WHERE status = 'VERIFIED'", UUID.class);
    }

    String orderEmail(UUID tenant, UUID order) {
        return queryString(tenant, "SELECT customer_email FROM orders WHERE id = ?", order);
    }

    String orderName(UUID tenant, UUID order) {
        return queryString(tenant, "SELECT customer_name FROM orders WHERE id = ?", order);
    }

    long erasureRecordCount(UUID tenant) {
        // Explicit tenant predicate: this class runs as the Testcontainers SUPERUSER, which bypasses
        // FORCE RLS, so the GUC pin alone would count every test's records (measured: 3 and 8).
        String n = queryString(tenant, "SELECT COUNT(*)::text FROM erasure_records WHERE tenant_id = ?", tenant);
        return Long.parseLong(n);
    }

    private String queryString(UUID tenant, String sql, Object... params) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("a row for: %s", sql).isTrue();
                    return rs.getString(1);
                }
            }
        });
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
}
