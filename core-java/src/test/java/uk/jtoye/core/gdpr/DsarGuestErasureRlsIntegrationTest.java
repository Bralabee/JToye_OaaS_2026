package uk.jtoye.core.gdpr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.SoftAssertions;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #777 (P0, decision D-02): a verified Article 17 request must erase a storefront customer.
 *
 * <p>Storefront guest checkout never creates a {@code customers} row. Before #777 the DSAR fan-out
 * matched the subject digest against {@code customers} only, so for every storefront subject it
 * matched nothing, anonymised nothing, and still marked the request COMPLETED with
 * {@code tenantsErased=0}: a statutory right reported as satisfied while every order and review kept
 * the subject's name, address and phone number.
 *
 * <p>Proven against the real posture: Postgres 15 with every migration applied and the connection
 * role downgraded to NOSUPERUSER, so FORCE row-level security is genuinely enforced (the Testcontainers
 * bootstrap role is a superuser and would bypass it — the #764 history). Every seed and every read
 * runs in its own transaction with the tenant GUC pinned on the Hibernate transaction's own connection
 * ({@code Session.doWork}), the {@code DsarFanoutIntegrationTest} recipe.
 *
 * <p>Every arm carries a precondition that proves it CAN fail: the role is NOSUPERUSER and the rows
 * under test are visible to the pinned session BEFORE the sweep, so a blind query cannot pass.
 * Fresh tenants and addresses per test, because the worker sweeps every tenant in the container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@ExtendWith(OutputCaptureExtension.class)
// The fan-out is @Scheduled; this class drives it by hand and must own the timeline (#418).
@Import(NoScheduledTriggersTestConfig.class)
class DsarGuestErasureRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String INTAKE_PATH = "/api/v1/public/gdpr/dsar";
    private static final String VERIFY_PATH = "/api/v1/public/gdpr/dsar/verify";
    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final String REDACTED = "[REDACTED]";
    private static final String REDACTED_EMAIL = "redacted@erased.invalid";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private DsarFanoutWorker worker;
    @Autowired private GdprService gdprService;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoSpyBean private DsarVerificationMailer mailer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // The worker claims every VERIFIED request in the table; start each arm from an empty queue.
        jdbc.update("DELETE FROM dsar_request");
        // Only a superuser may run ALTER ROLE, so this happens exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses "
                        + "row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Tracer: the #777 acceptance test --------------------------------------------------------

    /**
     * Tenant A holds the subject under TWO stored spellings on guest orders plus a review; tenant B
     * holds one guest order. Neither has a {@code customers} row. The request is lodged through the
     * real public intake under a THIRD spelling and verified through the real verify endpoint.
     */
    @Test
    void aVerifiedErasureAnonymisesAGuestSubjectInEveryTenantThatHoldsThem(CapturedOutput output)
            throws Exception {
        String local = "Grace.Persona14." + shortId();
        String storedA1 = local + "@example.test";
        String storedA2 = "  " + local.toLowerCase(java.util.Locale.ROOT) + "@EXAMPLE.test ";
        String lodgedAs = local.toUpperCase(java.util.Locale.ROOT) + "@example.TEST";
        // The review carries a spelling NO order carries, so only the review projection can reach it.
        String storedReview = local + "@Example.Test";
        String digest = DsarSubjectDigest.of(storedA1);
        assertThat(DsarSubjectDigest.of(storedA2)).as("PRECONDITION: the spellings share one digest").isEqualTo(digest);
        assertThat(DsarSubjectDigest.of(lodgedAs)).as("PRECONDITION: the lodged spelling shares it").isEqualTo(digest);
        assertThat(DsarSubjectDigest.of(storedReview)).as("PRECONDITION: the review spelling shares it").isEqualTo(digest);

        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID shopA = seedShop(a);
        UUID shopB = seedShop(b);
        UUID productA = seedProduct(a);
        UUID productB = seedProduct(b);
        UUID orderA1 = seedGuestOrder(a, shopA, storedA1, "Grace Persona");
        UUID orderA2 = seedGuestOrder(a, shopA, storedA2, "G. Persona");
        UUID orderB = seedGuestOrder(b, shopB, storedA1, "Grace P");
        seedItemAndLedger(a, orderA1, productA);
        seedItemAndLedger(a, orderA2, productA);
        seedItemAndLedger(b, orderB, productB);
        UUID reviewA = seedReview(a, shopA, orderA1, storedReview, "Grace Persona", "lovely jollof");
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders WHERE customer_email = ?", storedReview))
                .as("PRECONDITION: no order carries the review's spelling").isZero();

        assertThat(countUnder(a, "SELECT COUNT(*) FROM customers")).as("PRECONDITION: no customers row in A").isZero();
        assertThat(countUnder(b, "SELECT COUNT(*) FROM customers")).as("PRECONDITION: no customers row in B").isZero();
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders WHERE id IN (?, ?) AND customer_email IS NOT NULL",
                orderA1, orderA2))
                .as("PRECONDITION: the downgraded role SEES both of tenant A's orders with their address")
                .isEqualTo(2L);
        assertThat(countUnder(b, "SELECT COUNT(*) FROM orders WHERE id = ? AND customer_email IS NOT NULL", orderB))
                .as("PRECONDITION: the downgraded role SEES tenant B's order with its address").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE id = ? AND customer_email IS NOT NULL", reviewA))
                .as("PRECONDITION: the downgraded role SEES the review").isEqualTo(1L);
        String moneyA = moneyFingerprint(a);
        String moneyB = moneyFingerprint(b);
        assertThat(moneyA).as("PRECONDITION: the money fingerprint read real rows").contains(orderA1.toString());

        UUID requestId = lodgeVerifiedErasure(lodgedAs, "203.0.113.77");
        worker.executeLodgedRequests();

        OrderPii a1 = readOrderPii(a, orderA1);
        OrderPii a2 = readOrderPii(a, orderA2);
        OrderPii b1 = readOrderPii(b, orderB);
        ReviewPii review = readReviewPii(a, reviewA);
        SoftAssertions.assertSoftly(softly -> {
            for (OrderPii o : List.of(a1, a2, b1)) {
                softly.assertThat(o.customerName()).as("order %s customer_name", o.id()).isEqualTo(REDACTED);
                softly.assertThat(o.customerEmail()).as("order %s customer_email", o.id()).isNull();
                softly.assertThat(o.customerPhone()).as("order %s customer_phone", o.id()).isNull();
                softly.assertThat(o.notes()).as("order %s notes", o.id()).isNull();
                softly.assertThat(o.addressLine1()).as("order %s address_line1", o.id()).isNull();
                softly.assertThat(o.addressLine2()).as("order %s address_line2", o.id()).isNull();
                softly.assertThat(o.addressCity()).as("order %s address_city", o.id()).isNull();
                softly.assertThat(o.addressPostcode()).as("order %s address_postcode", o.id()).isNull();
            }
            softly.assertThat(review.customerName()).as("review customer_name").isEqualTo(REDACTED);
            softly.assertThat(review.customerEmail()).as("review customer_email").isEqualTo(REDACTED_EMAIL);
            softly.assertThat(review.comment()).as("review comment").isNull();
            softly.assertThat(completedLine(output, requestId))
                    .as("the worker's completion log for this request")
                    .contains("tenantsErased=2");
        });

        // D-02 / T-31.1-04: tax records have their own retention. Order rows, items, amounts, VAT and the
        // ledger are byte-identical; only PII columns moved.
        assertThat(moneyFingerprint(a)).as("tenant A's orders, items and ledger after erasure").isEqualTo(moneyA);
        assertThat(moneyFingerprint(b)).as("tenant B's orders, items and ledger after erasure").isEqualTo(moneyB);

        for (UUID tenant : List.of(a, b)) {
            List<RecordRow> records = erasureRecords(tenant);
            assertThat(records).as("exactly one Article-17 evidence row in tenant %s", tenant).hasSize(1);
            assertThat(records.get(0).subjectCustomerId())
                    .as("a guest subject had no customers row, so the record names none").isNull();
            assertThat(records.get(0).subjectEmailSha256())
                    .as("the guest record carries the DSAR subject digest").isEqualTo(digest);
        }
        assertThat(erasureRecords(a).get(0).ordersAnonymised()).as("tenant A orders anonymised").isEqualTo(2);
        assertThat(erasureRecords(a).get(0).reviewsAnonymised()).as("tenant A reviews anonymised").isEqualTo(1);
        assertThat(requestStatus(requestId)).isEqualTo("COMPLETED");
    }

    /**
     * V68: {@code erasure_records.subject_customer_id} accepts NULL, and {@code erasure_records} still has
     * no Envers mirror, so V68 owes no {@code _aud} change. The positive control proves the catalogue read
     * can see a mirror where one exists.
     */
    @Test
    void v68LetsAnErasureRecordNameNoCustomerAndAddsNoAuditMirror() {
        assertThat(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'erasure_records' AND column_name = 'subject_customer_id'",
                String.class))
                .as("erasure_records.subject_customer_id must accept NULL for a guest subject")
                .isEqualTo("YES");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE tablename = 'orders_aud'", Long.class))
                .as("CONTROL: the catalogue read sees an _aud mirror where one exists").isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE tablename = 'erasure_records_aud'", Long.class))
                .as("erasure_records has no _aud mirror").isZero();
    }

    // ---- Task 2 arms -----------------------------------------------------------------------------

    /**
     * Every stored spelling that normalises to the subject is erased in ONE sweep, and so is the Envers
     * history behind it: {@code orders_aud} rows written under each spelling lose the address.
     */
    @Test
    void everySpellingAndItsAuditHistoryIsScrubbedInOneSweep(CapturedOutput output) {
        String local = "grace.persona14." + shortId();
        String spellingA = "Grace.Persona14." + local.substring("grace.persona14.".length()) + "@example.test";
        String spellingB = " " + local + "@EXAMPLE.test ";
        String digest = DsarSubjectDigest.of(local + "@example.test");
        UUID a = seedTenant();
        UUID shop = seedShop(a);
        UUID order1 = seedGuestOrder(a, shop, spellingA, "Grace Persona");
        UUID order2 = seedGuestOrder(a, shop, spellingB, "Grace Persona");
        seedOrderAud(a, order1, spellingA);
        seedOrderAud(a, order2, spellingB);
        // A third party's audit history in the same tenant must survive the scrub.
        UUID bystander = seedGuestOrder(a, shop, "bystander-" + shortId() + "@example.test", "Bystander");
        seedOrderAud(a, bystander, "bystander-aud@example.test");

        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders_aud WHERE id IN (?, ?) AND customer_email IS NOT NULL",
                order1, order2))
                .as("PRECONDITION: the downgraded role SEES both spellings' audit rows").isEqualTo(2L);

        UUID requestId = insertVerifiedErasure(digest);
        worker.executeLodgedRequests();

        assertThat(readOrderPii(a, order1).customerEmail()).as("spelling A on the live row").isNull();
        assertThat(readOrderPii(a, order2).customerEmail()).as("spelling B on the live row").isNull();
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders_aud WHERE id IN (?, ?) AND customer_email IS NOT NULL",
                order1, order2))
                .as("no audit row of either spelling still carries an address").isZero();
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders_aud WHERE id IN (?, ?) AND customer_phone IS NOT NULL",
                order1, order2))
                .as("no audit row of either spelling still carries a phone number").isZero();
        assertThat(countUnder(a, "SELECT COUNT(*) FROM orders_aud WHERE id = ? AND customer_email = ?",
                bystander, "bystander-aud@example.test"))
                .as("a third party's audit row is untouched").isEqualTo(1L);
        assertThat(readOrderPii(a, bystander).customerName()).as("a third party's live order").isEqualTo("Bystander");
        List<RecordRow> records = erasureRecords(a);
        assertThat(records).as("one record for one tenant, however many spellings").hasSize(1);
        assertThat(records.get(0).ordersAnonymised()).isEqualTo(2);
        assertThat(completedLine(output, requestId)).contains("tenantsErased=1");
    }

    /** T-31.1-03: a tenant holding only a different address is untouched and gets no record. */
    @Test
    void aTenantHoldingADifferentAddressIsUntouched(CapturedOutput output) {
        String subject = "subject-" + shortId() + "@example.test";
        String other = "someone-else-" + shortId() + "@example.test";
        UUID a = seedTenant();
        UUID c = seedTenant();
        UUID orderA = seedGuestOrder(a, seedShop(a), subject, "Subject");
        UUID orderC = seedGuestOrder(c, seedShop(c), other, "Someone Else");
        OrderPii cBefore = readOrderPii(c, orderC);
        assertThat(cBefore.customerEmail()).as("PRECONDITION: tenant C's order is visible with its address")
                .isEqualTo(other);

        UUID requestId = insertVerifiedErasure(DsarSubjectDigest.of(subject));
        worker.executeLodgedRequests();

        assertThat(readOrderPii(a, orderA).customerEmail()).as("CONTROL: the sweep did erase tenant A").isNull();
        assertThat(readOrderPii(c, orderC)).as("tenant C's order, by content").isEqualTo(cBefore);
        assertThat(erasureRecords(c)).as("tenant C held nothing for the subject").isEmpty();
        assertThat(completedLine(output, requestId)).contains("tenantsErased=1");
    }

    /**
     * The customer-row path is unchanged in kind: the record names the customer. An address reachable both
     * through the customers row and through a guest order under another spelling yields ONE record, and the
     * order linked by customer_id AND by address is updated once (it is counted once).
     */
    @Test
    void aCustomerRowSubjectIsErasedOnceWithTheCustomerOnItsRecord(CapturedOutput output) {
        String address = "Customer.Row." + shortId() + "@example.test";
        String guestSpelling = address.toLowerCase(java.util.Locale.ROOT);
        UUID d = seedTenant();
        UUID shop = seedShop(d);
        UUID customerId = seedCustomer(d, address);
        UUID linked = seedOrder(d, shop, customerId, address, "Customer Row");
        UUID guest = seedGuestOrder(d, shop, guestSpelling, "Customer Row");
        assertThat(countUnder(d, "SELECT COUNT(*) FROM customers WHERE id = ? AND email = ?", customerId, address))
                .as("PRECONDITION: the customers row is visible").isEqualTo(1L);

        UUID requestId = insertVerifiedErasure(DsarSubjectDigest.of(address));
        worker.executeLodgedRequests();

        assertThat(countUnder(d, "SELECT COUNT(*) FROM customers WHERE id = ? AND name = ? AND phone IS NULL",
                customerId, REDACTED))
                .as("the customers row is anonymised as the admin erasure does it").isEqualTo(1L);
        assertThat(readOrderPii(d, linked).customerEmail()).as("the customer-linked order").isNull();
        assertThat(readOrderPii(d, guest).customerEmail()).as("the guest order under another spelling").isNull();
        List<RecordRow> records = erasureRecords(d);
        assertThat(records).as("ONE record for the tenant, not one per path").hasSize(1);
        assertThat(records.get(0).subjectCustomerId()).as("the record names the erased customer").isEqualTo(customerId);
        assertThat(records.get(0).ordersAnonymised()).as("two distinct orders, each counted once").isEqualTo(2);
        assertThat(completedLine(output, requestId)).contains("tenantsErased=1");
    }

    /** A fresh VERIFIED request for an already-erased subject matches nothing and writes nothing. */
    @Test
    void aSecondSweepForTheSameSubjectChangesNothingAndWritesNoRecord(CapturedOutput output) {
        String address = "twice." + shortId() + "@example.test";
        UUID a = seedTenant();
        UUID shop = seedShop(a);
        UUID order = seedGuestOrder(a, shop, address, "Twice");
        UUID review = seedReview(a, shop, order, address, "Twice", "again");
        String digest = DsarSubjectDigest.of(address);

        UUID first = insertVerifiedErasure(digest);
        worker.executeLodgedRequests();
        assertThat(completedLine(output, first)).as("PRECONDITION: the first sweep erased")
                .contains("tenantsErased=1");
        OrderPii orderAfterFirst = readOrderPii(a, order);
        ReviewPii reviewAfterFirst = readReviewPii(a, review);
        assertThat(erasureRecords(a)).as("PRECONDITION: one record after the first sweep").hasSize(1);

        UUID second = insertVerifiedErasure(digest);
        worker.executeLodgedRequests();

        assertThat(completedLine(output, second)).as("the second request's completion").contains("tenantsErased=0");
        assertThat(requestStatus(second)).isEqualTo("COMPLETED");
        assertThat(erasureRecords(a)).as("no second record").hasSize(1);
        assertThat(readOrderPii(a, order)).as("the order after the second sweep").isEqualTo(orderAfterFirst);
        assertThat(readReviewPii(a, review)).as("the review after the second sweep").isEqualTo(reviewAfterFirst);
    }

    /** D-02: a request for an address no tenant holds is SATISFIED with zero erasures and changes nothing. */
    @Test
    void anAddressNoTenantHoldsCompletesWithZeroAndChangesNothing(CapturedOutput output) {
        UUID a = seedTenant();
        UUID orderA = seedGuestOrder(a, seedShop(a), "held-" + shortId() + "@example.test", "Held");
        OrderPii before = readOrderPii(a, orderA);

        UUID requestId = insertVerifiedErasure(DsarSubjectDigest.of("nobody-" + shortId() + "@example.test"));
        worker.executeLodgedRequests();

        String line = completedLine(output, requestId);
        assertThat(line).as("PRECONDITION: the request was processed and logged").isNotEmpty();
        assertThat(line).contains("tenantsErased=0");
        assertThat(requestStatus(requestId)).isEqualTo("COMPLETED");
        assertThat(erasureRecords(a)).isEmpty();
        assertThat(readOrderPii(a, orderA)).isEqualTo(before);
    }

    /**
     * {@code matchSubjectInTenant} finds every stored spelling on customers, orders and reviews in the pinned
     * tenant — and NOT a spelling that only another tenant's PUBLISHED review carries, although the reviews
     * SELECT policy shows that review to this tenant's session (#764, T-31.1-03).
     */
    @Test
    void theMatchFindsEverySpellingInThePinnedTenantOnly() {
        String local = "match." + shortId();
        String onCustomer = local + "@example.test";
        String onOrder = local.toUpperCase(java.util.Locale.ROOT) + "@example.test";
        String onReview = "  " + local + "@Example.Test";
        String onlyElsewhere = "\t" + local + "@EXAMPLE.test";
        String digest = DsarSubjectDigest.of(onCustomer);
        UUID a = seedTenant();
        UUID e = seedTenant();
        UUID shopA = seedShop(a);
        UUID customerId = seedCustomer(a, onCustomer);
        UUID orderA = seedGuestOrder(a, shopA, onOrder, "Match");
        seedReview(a, shopA, orderA, onReview, "Match", "in A");
        UUID publishedShopE = seedPublishedShop(e);
        UUID orderE = seedGuestOrder(e, publishedShopE, "other-" + shortId() + "@example.test", "Other");
        seedReview(e, publishedShopE, orderE, onlyElsewhere, "Match", "in E");
        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE customer_email = ?", onlyElsewhere))
                .as("PRECONDITION: tenant A's session SEES tenant E's published review, so a projection "
                        + "without the tenant predicate WOULD return its spelling — this arm can discriminate")
                .isEqualTo(1L);

        GdprService.SubjectMatch match = inPinnedTransaction(a, () -> gdprService.matchSubjectInTenant(a, digest));

        assertThat(match.customerIds()).containsExactly(customerId);
        assertThat(match.emailSpellings())
                .as("every stored spelling of the subject in tenant A, and only those")
                .containsExactlyInAnyOrder(onCustomer, onOrder, onReview);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private <T> T inPinnedTransaction(UUID tenant, java.util.function.Supplier<T> work) {
        TenantContext.set(tenant);
        try {
            return new TransactionTemplate(txManager).execute(s -> {
                entityManager.unwrap(Session.class).doWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                });
                return work.get();
            });
        } finally {
            TenantContext.clear();
        }
    }

    private UUID seedPublishedShop(UUID tenant) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                id, tenant, "shop-" + id, "shop-777p-" + id, "Test Address");
        return id;
    }

    /** One pre-erasure Envers row for an order, as Hibernate would have written it on create. */
    private void seedOrderAud(UUID tenant, UUID orderId, String email) {
        int rev = 1_000_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000_000);
        update(tenant, "INSERT INTO revinfo (rev, revtstmp, tenant_id) VALUES (?, 0, ?)", rev, tenant);
        update(tenant, "INSERT INTO orders_aud (id, rev, revtype, tenant_id, customer_name, customer_email, "
                        + "  customer_phone, notes, address_line1, address_postcode) "
                        + "VALUES (?, ?, 0, ?, 'Pre-erasure Name', ?, '07700900789', 'aud note', '1 Aud Street', 'B2 2BB')",
                orderId, rev, tenant, email);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID lodgeVerifiedErasure(String email, String clientIp) throws Exception {
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", email, "requestType", "ERASURE"))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(anyString(), token.capture(), any(), anyLong());

        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("token", token.getValue()))))
                .andExpect(status().isOk());
        reset(mailer);
        UUID id = jdbc.queryForObject("SELECT id FROM dsar_request WHERE status = 'VERIFIED'", UUID.class);
        assertThat(id).as("PRECONDITION: the lodged request is VERIFIED").isNotNull();
        return id;
    }

    /** A VERIFIED ERASURE row for a digest, as the verify endpoint leaves it — no intake rate limit applies. */
    private UUID insertVerifiedErasure(String subjectDigest) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dsar_request (id, subject_email_sha256, request_type, status, verified_at) "
                + "VALUES (?, ?, 'ERASURE', 'VERIFIED', NOW())", id, subjectDigest);
        return id;
    }

    /** The worker's completion line for one request, or "" when it never completed. */
    private static String completedLine(CapturedOutput output, UUID requestId) {
        Matcher m = Pattern.compile("event=dsar_fanout_completed request=" + requestId + " [^\\r\\n]*")
                .matcher(output.getAll());
        String last = "";
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    private String requestStatus(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM dsar_request WHERE id = ?", String.class, requestId);
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "777-" + id);
        return id;
    }

    private UUID seedShop(UUID tenant) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, false, 0)",
                id, tenant, "shop-" + id, "shop-777-" + id, "Test Address");
        return id;
    }

    private UUID seedProduct(UUID tenant) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Jollof " + id, "Rice, tomato");
        return id;
    }

    UUID seedCustomer(UUID tenant, String email) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO customers (id, tenant_id, name, email, phone) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "Customer " + id, email, "07700900123");
        return id;
    }

    private UUID seedGuestOrder(UUID tenant, UUID shop, String email, String name) {
        return seedOrder(tenant, shop, null, email, name);
    }

    UUID seedOrder(UUID tenant, UUID shop, UUID customerId, String email, String name) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO orders (id, tenant_id, shop_id, customer_id, order_number, status, "
                        + "  customer_email, customer_name, customer_phone, notes, "
                        + "  address_line1, address_line2, address_city, address_postcode, "
                        + "  total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, '07700900456', 'ring the bell twice', "
                        + "  '14 Persona Road', 'Flat 2', 'Birmingham', 'B1 1AA', 1250, 250, 1000, 'STANDARD', 167)",
                id, tenant, shop, customerId, "ORD-" + id.toString().substring(0, 8), email, name);
        return id;
    }

    private void seedItemAndLedger(UUID tenant, UUID order, UUID product) {
        update(tenant, "INSERT INTO order_items (id, tenant_id, order_id, product_id, quantity, unit_price_pennies, "
                        + "  total_price_pennies) VALUES (?, ?, ?, ?, 2, 500, 1000)",
                UUID.randomUUID(), tenant, order, product);
        update(tenant, "INSERT INTO financial_transactions (id, tenant_id, amount_pennies, vat_rate, reference, order_id) "
                        + "VALUES (?, ?, 1250, 'STANDARD', ?, ?)",
                UUID.randomUUID(), tenant, "order " + order, order);
    }

    private UUID seedReview(UUID tenant, UUID shop, UUID order, String email, String name, String comment) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, customer_name, "
                        + "  food_rating, comment) VALUES (?, ?, ?, ?, ?, ?, 5, ?)",
                id, tenant, shop, order, email, name, comment);
        return id;
    }

    /**
     * Everything an erasure must NOT change, by content: each order's identity, money and VAT columns, every
     * order item and every ledger row of the tenant, as one ordered JSON text.
     */
    private String moneyFingerprint(UUID tenant) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COALESCE((SELECT jsonb_agg(jsonb_build_object('id', id, 'status', status, "
                            + "  'subtotal', subtotal_pennies, 'vat_rate', vat_rate, 'vat', vat_amount_pennies, "
                            + "  'fee', delivery_fee_pennies, 'total', total_amount_pennies, 'shop', shop_id, "
                            + "  'number', order_number) ORDER BY id) FROM orders), '[]'::jsonb)::text "
                            + "|| COALESCE((SELECT jsonb_agg(to_jsonb(i) ORDER BY i.id) FROM order_items i), '[]'::jsonb)::text "
                            + "|| COALESCE((SELECT jsonb_agg(to_jsonb(f) ORDER BY f.id) FROM financial_transactions f), "
                            + "  '[]'::jsonb)::text");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        });
    }

    record OrderPii(UUID id, String customerName, String customerEmail, String customerPhone, String notes,
                    String addressLine1, String addressLine2, String addressCity, String addressPostcode) {
    }

    OrderPii readOrderPii(UUID tenant, UUID orderId) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT customer_name, customer_email, customer_phone, notes, address_line1, address_line2, "
                            + "address_city, address_postcode FROM orders WHERE id = ?")) {
                ps.setObject(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("order %s is visible to tenant %s", orderId, tenant).isTrue();
                    return new OrderPii(orderId, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
                }
            }
        });
    }

    record ReviewPii(String customerName, String customerEmail, String comment) {
    }

    ReviewPii readReviewPii(UUID tenant, UUID reviewId) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT customer_name, customer_email, comment FROM reviews WHERE id = ?")) {
                ps.setObject(1, reviewId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("review %s is visible to tenant %s", reviewId, tenant).isTrue();
                    return new ReviewPii(rs.getString(1), rs.getString(2), rs.getString(3));
                }
            }
        });
    }

    record RecordRow(UUID subjectCustomerId, String subjectEmailSha256, int ordersAnonymised, int reviewsAnonymised) {
    }

    List<RecordRow> erasureRecords(UUID tenant) {
        return inTenant(tenant, connection -> {
            List<RecordRow> rows = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT subject_customer_id, subject_email_sha256, orders_anonymised, reviews_anonymised "
                            + "FROM erasure_records ORDER BY erased_at, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RecordRow((UUID) rs.getObject(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                }
            }
            return rows;
        });
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

    /** One fresh transaction with the tenant GUC pinned on the Hibernate transaction's own connection. */
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
