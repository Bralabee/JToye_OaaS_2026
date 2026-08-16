package uk.jtoye.core.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.KeycloakRealmRoleConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-17 proven against a real Postgres under FORCE row-level security with the application role
 * downgraded: <b>no human path crosses tenants, and the background job does</b>.
 *
 * <p>That contrast is the whole of the design. A single cross-tenant data-subject-request desk
 * looks like it requires the cross-tenant operator identity this project has refused twice; it does
 * not, because the reach belongs to a scheduled worker that takes one pinned tenant at a time and
 * to nothing else. {@link #aTenantAdminCannotReachAnotherTenantsCustomerWhileTheWorkerActsOnBoth()}
 * asserts both sides of that sentence in one test rather than describing it in prose.
 *
 * <h2>Two instrument hazards this class is built around</h2>
 *
 * <ol>
 *   <li><b>A zero row count under RLS is not evidence of isolation.</b> Under a NOSUPERUSER role an
 *       UNPINNED query returns zero rows over a fully populated table, so a test can "prove"
 *       isolation while proving only that its own instrument was blind. Every zero-row assertion
 *       here is therefore preceded, in the same test, by a POSITIVE CONTROL that pins the other
 *       tenant and asserts a NON-ZERO count. The under-seeded fixture arm (seed one tenant instead
 *       of two) was run and fires that control.</li>
 *   <li><b>The Testcontainers bootstrap role is a SUPERUSER and bypasses even FORCE RLS.</b> So the
 *       role is downgraded with {@code ALTER ROLE ... NOSUPERUSER} before any cross-tenant
 *       assertion, and every seed pins the tenant GUC inside its own transaction so it works in
 *       both worlds.</li>
 * </ol>
 *
 * <h2>Deliberately NOT {@code @AsSystemHarness}</h2>
 *
 * A class-wide system declaration would sit underneath the worker's own
 * {@code SystemPrincipal.asSystem} wrap and make the "remove the wrap" break arm incapable of
 * failing — the test would keep passing with the declaration deleted, and the arm would certify a
 * control that had stopped existing. Seeding therefore avoids every gated service and goes straight
 * to SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
// The fan-out is @Scheduled and a fixedDelay task fires once at context refresh regardless of its
// interval (#418) — this class drives it by hand and must own the timeline.
@Import(NoScheduledTriggersTestConfig.class)
class DsarFanoutIntegrationTest {

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
    private static final String ADMIN_EXPORT_PATH = "/api/v1/gdpr/customers/{id}/export";

    /**
     * The downgraded APPLICATION role, named rather than inherited from whatever the harness
     * happens to default to. It is the container's own bootstrap role after
     * {@code ALTER ROLE ... NOSUPERUSER}: NOSUPERUSER, so FORCE RLS is genuinely enforced against
     * it. (It still OWNS the tables — see {@code IntegrationTestSupport} on why that is a weaker
     * property than the production non-owner {@code jtoye_runtime} role.)
     */
    private static final String DOWNGRADED_APP_ROLE = "test";

    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DsarFanoutWorker worker;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @SpyBean private DsarVerificationMailer mailer;
    @SpyBean private GdprService gdprService;

    @BeforeEach
    void downgradeRole() {
        jdbc.update("DELETE FROM dsar_request");
        // Idempotent: only a superuser may run ALTER ROLE, so this must happen exactly once.
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
    void leaveTheThreadClean() {
        TenantContext.clear();
        reset(gdprService);
    }

    // ---- The fan-out itself --------------------------------------------------------------------

    @Test
    void aSubjectHeldByTwoTenantsYieldsExactlyOneErasureRecordPerTenant() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID c = seedTenant();
        // The address is lodged in a DIFFERENT surface form from the one the customer rows hold.
        // That is not decoration: it is the byte-identity proof. The intake normalises (trim +
        // lower-case, UTF-8) before hashing, and the worker must reproduce that EXACTLY over
        // customer rows or it matches nothing while every status assertion stays green.
        String stored = "mixed.case+dsar-" + UUID.randomUUID() + "@example.com";
        String lodgedAs = "  " + stored.toUpperCase(java.util.Locale.ROOT) + "  ";
        seedCustomer(a, stored);
        seedCustomer(b, stored);
        seedCustomer(c, "someone-else-" + UUID.randomUUID() + "@example.com");

        lodgeVerifiedErasure(lodgedAs, "203.0.113.31");
        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(a)).as("tenant A held the subject").isEqualTo(1);
        assertThat(erasureRecordCount(b)).as("tenant B held the subject").isEqualTo(1);
        assertThat(erasureRecordCount(c)).as("tenant C never held the subject").isZero();

        assertThat(customerCountWithEmail(a, stored)).isZero();
        assertThat(customerCountWithEmail(b, stored)).isZero();

        assertThat(requestStatus()).isEqualTo("COMPLETED");
        assertThat(completedAtIsSet()).isTrue();
    }

    @Test
    void aSubjectHeldByNoTenantStillCompletes() throws Exception {
        UUID a = seedTenant();
        seedCustomer(a, "unrelated-" + UUID.randomUUID() + "@example.com");

        lodgeVerifiedErasure("nobody-holds-" + UUID.randomUUID() + "@example.com", "203.0.113.32");
        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(a)).isZero();
        assertThat(requestStatus())
                .as("a request from somebody with no data is SATISFIED, not stuck — leaving it "
                        + "outstanding would make the queue grow without bound and would also be a "
                        + "worse answer than the truth")
                .isEqualTo("COMPLETED");
    }

    @Test
    void aSecondSweepDoesNotReprocessACompletedRequest() throws Exception {
        UUID a = seedTenant();
        String email = "once-only-" + UUID.randomUUID() + "@example.com";
        seedCustomer(a, email);

        lodgeVerifiedErasure(email, "203.0.113.33");
        worker.executeLodgedRequests();
        assertThat(erasureRecordCount(a)).isEqualTo(1);

        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(a))
                .as("a completed request must not be claimed again")
                .isEqualTo(1);
        assertThat(processAttempts()).isEqualTo(1);
    }

    @Test
    void aRequestIsClaimedExactlyOnceEvenWhenTwoSweepsRunConcurrently() throws Exception {
        UUID a = seedTenant();
        String email = "concurrent-" + UUID.randomUUID() + "@example.com";
        seedCustomer(a, email);
        lodgeVerifiedErasure(email, "203.0.113.34");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { await(go); worker.executeLodgedRequests(); return null; });
            var second = pool.submit(() -> { await(go); worker.executeLodgedRequests(); return null; });
            go.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // process_attempts is the falsifiable assertion here. The erasure itself is idempotent
        // (the anonymised address no longer hashes to the subject digest), so a record count of 1
        // would ALSO hold if both sweeps claimed the row — it cannot distinguish the two worlds.
        // The attempt counter can.
        assertThat(processAttempts())
                .as("both sweeps claimed the same request — the claim is not exclusive")
                .isEqualTo(1);
        assertThat(erasureRecordCount(a)).isEqualTo(1);
    }

    // ---- Per-tenant isolation of failure --------------------------------------------------------

    @Test
    void oneTenantsFailureLeavesTheOtherTenantsCommittedErasureInPlace() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        String email = "partial-failure-" + UUID.randomUUID() + "@example.com";
        seedCustomer(a, email);
        seedCustomer(b, email);

        doThrow(new IllegalStateException("deliberate per-tenant failure"))
                .when(gdprService).eraseSubjectByDigest(eq(b), anyString());

        lodgeVerifiedErasure(email, "203.0.113.35");
        assertThatCode(() -> worker.executeLodgedRequests())
                .as("one tenant's failure must not abort the sweep")
                .doesNotThrowAnyException();

        // COMMITTED, read in its own fresh transaction — not merely "no exception escaped".
        assertThat(erasureRecordCount(a))
                .as("tenant A's erasure must be committed and survive tenant B's failure")
                .isEqualTo(1);
        assertThat(customerCountWithEmail(a, email)).isZero();
        assertThat(customerCountWithEmail(b, email))
                .as("tenant B genuinely failed, so its subject is untouched")
                .isEqualTo(1);

        assertThat(requestStatus())
                .as("a partially-failed request must be released for retry, never marked complete "
                        + "— completing it would silently drop tenant B's erasure for good")
                .isEqualTo("VERIFIED");
        assertThat(completedAtIsSet()).isFalse();
        assertThat(lastError()).isNotBlank();
    }

    @Test
    void theSweepLeavesTheThreadCleanIncludingOnTheFailurePath() throws Exception {
        UUID a = seedTenant();
        String email = "thread-clean-" + UUID.randomUUID() + "@example.com";
        seedCustomer(a, email);

        doThrow(new IllegalStateException("deliberate failure"))
                .when(gdprService).eraseSubjectByDigest(eq(a), anyString());

        lodgeVerifiedErasure(email, "203.0.113.36");
        worker.executeLodgedRequests();

        // TenantContext is genuinely falsifiable: drop the worker's `finally { clear() }` and a
        // throwing tenant leaves the pin behind on this pooled thread.
        assertThat(TenantContext.get())
                .as("a stale TenantContext on a returned thread is a cross-tenant read waiting to "
                        + "happen on an unrelated request")
                .isEmpty();
        // SystemPrincipal is asserted for completeness, but honestly: asSystem RESTORES the prior
        // value in a finally, so this assertion is incapable of failing and is NOT evidence. The
        // executable form of "the wrap exists" is the source grep in the plan's <verify> block.
        assertThat(SystemPrincipal.isSystem()).isFalse();
    }

    // ---- D-17: the human path does not cross tenants; the worker does ---------------------------

    @Test
    void aTenantAdminCannotReachAnotherTenantsCustomerWhileTheWorkerActsOnBoth() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        String email = "cross-tenant-" + UUID.randomUUID() + "@example.com";
        UUID customerInA = seedCustomer(a, email);
        UUID customerInB = seedCustomer(b, email);

        // POSITIVE CONTROL FIRST. Under a downgraded role an unpinned or misdirected query returns
        // zero rows over a full table, so "the admin saw nothing" proves nothing until the same
        // instrument is shown to see something.
        mockMvc.perform(get(ADMIN_EXPORT_PATH, customerInA).with(adminOf(a)))
                .andExpect(status().isOk());

        // ...and only now the isolation assertion.
        mockMvc.perform(get(ADMIN_EXPORT_PATH, customerInB).with(adminOf(a)))
                .andExpect(status().isNotFound());

        // The other half of the contrast: the background worker reaches BOTH.
        lodgeVerifiedErasure(email, "203.0.113.37");
        worker.executeLodgedRequests();

        assertThat(erasureRecordCount(a)).isEqualTo(1);
        assertThat(erasureRecordCount(b))
                .as("the worker acts on the tenant no human on this path can see")
                .isEqualTo(1);
    }

    @Test
    void theDowngradedRoleSeesOnlyThePinnedTenantsRows() {
        UUID a = seedTenant();
        UUID b = seedTenant();
        seedCustomer(a, "iso-a-" + UUID.randomUUID() + "@example.com");
        seedCustomer(b, "iso-b-" + UUID.randomUUID() + "@example.com");

        // Positive controls in BOTH directions, before either zero is asserted. Two tenants are
        // genuinely seeded, so the isolation result below is about RLS rather than about an
        // under-seeded fixture. (The one-tenant arm was run: it fires the control for B.)
        assertThat(customerCount(a)).as("instrument can see tenant A").isPositive();
        assertThat(customerCount(b)).as("instrument can see tenant B").isPositive();

        assertThat(customerCountOfOtherTenant(a, b))
                .as("with A pinned, none of B's rows are visible")
                .isZero();
        assertThat(customerCountOfOtherTenant(b, a))
                .as("with B pinned, none of A's rows are visible")
                .isZero();
    }

    /**
     * The measurement behind {@code DsarSubjectDigest}'s "why the match is computed in Java"
     * decision — recorded rather than assumed, and asserting DISAGREEMENT rather than agreement.
     *
     * <p>The tempting alternative is a server-side
     * {@code encode(sha256(convert_to(lower(btrim(email)), 'UTF8')), 'hex')}, which needs no
     * extension and would push the match into the database. It is rejected because it is not the
     * same function: {@link String#trim()} strips every character {@code <= U+0020} while
     * {@code btrim} strips spaces only. This test proves that on the real engine — if a future
     * Postgres made them equivalent, this test reds and the trade can be revisited on evidence.
     *
     * <p>The ASCII control is the non-vacuity half: for an ordinary address the two forms MUST
     * agree, or the disagreement below would be a fact about a broken query rather than about the
     * normalisation.
     */
    @Test
    void theSqlSideDigestIsNotEquivalentToThisOne() {
        String plain = "Plain.Address@Example.COM";
        assertThat(sqlDigestOf(plain))
                .as("NON-VACUITY: for an ordinary address the two forms must agree, or the "
                        + "divergence asserted below would be about a malformed query")
                .isEqualTo(DsarSubjectDigest.of(plain));

        // A tab is whitespace to Java and not to btrim. An address pasted out of a spreadsheet or
        // a mail client routinely carries one.
        String tabbed = "\tPlain.Address@Example.COM\t";
        assertThat(sqlDigestOf(tabbed))
                .as("MEASURED DIVERGENCE: String.trim() strips U+0009, btrim does not — so a "
                        + "server-side match would silently miss this subject entirely")
                .isNotEqualTo(DsarSubjectDigest.of(tabbed));
        assertThat(DsarSubjectDigest.of(tabbed))
                .as("...while the Java form folds it to the same subject as the plain address, "
                        + "which is the behaviour a data subject is entitled to")
                .isEqualTo(DsarSubjectDigest.of(plain));
    }

    /**
     * The structural contract, as an executable assertion rather than a grep in a summary.
     *
     * <h2>The plan's own criterion could not pass, and was replaced rather than reported satisfied</h2>
     *
     * 31-09 specified {@code grep -cF '@Transactional' DsarFanoutWorker.java} equal to ZERO. Run
     * literally against the correct tree it returns <b>2</b> — both hits are the class javadoc
     * explaining <em>why</em> the annotation is absent. That is a named vacuous shape in this
     * project's standards ("a doc rule that must name the token it forbids", and "an expected-0 that
     * is 1 on the CORRECT tree"): the criterion fires on a clean implementation and the only ways to
     * satisfy it are to delete the warning or to lie about the count.
     *
     * <p>So it is replaced with a strictly stronger form — no NON-COMMENT line may carry the
     * annotation — which still reds on a real {@code @Transactional} and no longer reds on the
     * comment that forbids it. Both directions are recorded in the plan summary.
     *
     * <h2>Non-vacuity</h2>
     *
     * A comment-stripping scan that strips everything reports "clean" over any file at all. So the
     * same scan is run over {@code GdprService}, which genuinely carries the annotation, and must
     * find it. An empty result there means the instrument is broken, not that the code is right.
     */
    @Test
    void theWorkerCarriesNoTransactionalAnnotationAndKeepsItsFourLoadBearingConstructs()
            throws java.io.IOException {
        List<String> workerCode = codeLinesOf("uk/jtoye/core/gdpr/DsarFanoutWorker.java");
        assertThat(workerCode)
                .as("NON-VACUITY: the scan read no code lines, so any verdict below is about the "
                        + "scan rather than about the worker")
                .isNotEmpty();

        assertThat(workerCode.stream().filter(l -> l.contains("@Transactional")).toList())
                .as("a @Transactional private method is bypassed by Spring self-invocation, so no "
                        + "transaction starts, the tenant GUC is never pinned, and under FORCE RLS "
                        + "the work silently matches zero rows and reports success")
                .isEmpty();

        // POSITIVE CONTROL for the emptiness above.
        assertThat(codeLinesOf("uk/jtoye/core/gdpr/GdprService.java").stream()
                .filter(l -> l.contains("@Transactional")).count())
                .as("the same scan must find the annotation where it genuinely exists")
                .isPositive();

        // The four constructs the design rests on, asserted on CODE lines for the same reason.
        for (String required : List.of("TransactionTemplate", "set_config", "TenantContext.clear",
                "SystemPrincipal.asSystem")) {
            assertThat(workerCode.stream().anyMatch(l -> l.contains(required)))
                    .as("DsarFanoutWorker no longer contains %s in executable code", required)
                    .isTrue();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Source lines with whole-line comments removed — javadoc, block and line comments alike. */
    private List<String> codeLinesOf(String relativePath) throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java");
        if (!java.nio.file.Files.isDirectory(root)) {
            root = java.nio.file.Path.of("core-java", "src", "main", "java");
        }
        java.nio.file.Path file = root.resolve(relativePath);
        assertThat(java.nio.file.Files.isRegularFile(file))
                .as("%s is missing — a scan that reads nothing proves nothing", relativePath)
                .isTrue();
        return java.nio.file.Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                .toList();
    }

    private String sqlDigestOf(String email) {
        return jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(lower(btrim(?)), 'UTF8')), 'hex')",
                String.class, email);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor adminOf(UUID tenantId) {
        return jwt()
                .jwt(j -> j.claim("tenant_id", tenantId.toString())
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private void lodgeVerifiedErasure(String email, String clientIp) throws Exception {
        mockMvc.perform(post(INTAKE_PATH)
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "requestType", "ERASURE"))))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mailer).sendVerification(anyString(), token.capture(), any(), anyLong());

        mockMvc.perform(post(VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", token.getValue()))))
                .andExpect(status().isOk());
        reset(mailer);
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "T-" + id);
        return id;
    }

    private UUID seedCustomer(UUID tenantId, String email) {
        UUID id = UUID.randomUUID();
        pinnedUpdate(tenantId, "INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                id, tenantId, "Seeded Subject", email);
        return id;
    }

    private long erasureRecordCount(UUID tenantId) {
        return pinnedCount(tenantId, "SELECT COUNT(*) FROM erasure_records");
    }

    private long customerCount(UUID tenantId) {
        return pinnedCount(tenantId, "SELECT COUNT(*) FROM customers");
    }

    private long customerCountWithEmail(UUID tenantId, String email) {
        return pinnedCount(tenantId, "SELECT COUNT(*) FROM customers WHERE email = ?", email);
    }

    private long customerCountOfOtherTenant(UUID pinned, UUID other) {
        return pinnedCount(pinned, "SELECT COUNT(*) FROM customers WHERE tenant_id = ?", other);
    }

    /**
     * Run one statement with the tenant GUC pinned, ON THE SAME CONNECTION.
     *
     * <p><b>Measured, not stylistic.</b> The obvious shape —
     * {@code TransactionTemplate.execute(s -> { jdbcTemplate.execute("SELECT set_config(...)"); jdbcTemplate.update(...); })}
     * — was written first and FAILED with
     * {@code new row violates row-level security policy for table "customers"}. Under a
     * {@code JpaTransactionManager} the {@code JdbcTemplate} may take a connection that is still in
     * autocommit, and {@code set_config(..., true)} is TRANSACTION-local: in autocommit each
     * statement is its own transaction, so the pin is reverted before the next statement runs.
     * Going through {@code Session.doWork} takes the Hibernate transaction's own connection, which
     * is the same connection {@code TenantSetLocalAspect} and {@code DsarFanoutWorker.pinTenantGuc}
     * use in production.
     */
    private void pinnedUpdate(UUID tenantId, String sql, Object... params) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                entityManager.unwrap(Session.class).doWork(connection -> {
                    pinOn(connection, tenantId);
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        bind(ps, params);
                        ps.executeUpdate();
                    }
                }));
    }

    private long pinnedCount(UUID tenantId, String sql, Object... params) {
        Long n = new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    pinOn(connection, tenantId);
                    try (PreparedStatement ps = connection.prepareStatement(sql)) {
                        bind(ps, params);
                        try (ResultSet rs = ps.executeQuery()) {
                            return rs.next() ? rs.getLong(1) : 0L;
                        }
                    }
                }));
        return n == null ? 0 : n;
    }

    private static void pinOn(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT set_config('app.current_tenant_id', ?, true)")) {
            ps.setString(1, tenantId.toString());
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private String requestStatus() {
        return jdbc.queryForObject("SELECT status FROM dsar_request", String.class);
    }

    private boolean completedAtIsSet() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT completed_at IS NOT NULL FROM dsar_request", Boolean.class));
    }

    private int processAttempts() {
        Integer n = jdbc.queryForObject("SELECT process_attempts FROM dsar_request", Integer.class);
        return n == null ? 0 : n;
    }

    private String lastError() {
        return jdbc.queryForObject("SELECT last_error FROM dsar_request", String.class);
    }
}
