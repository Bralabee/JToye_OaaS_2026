package uk.jtoye.core.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #486 — {@code DELETE}/{@code PUT} on a customer, and {@code DELETE} on a staff grant,
 * must answer a typed 404 when the row vanishes mid-transaction. Never a 5xx, and never the 409
 * "re-read it and retry" that tells a caller to keep asking for a row that will never come back.
 *
 * <p>This is the SAME defect class #390 fixed for promotions/announcements (PR #477,
 * {@code MarketingMissingRowStatusIntegrationTest}); #390 deliberately scoped itself to marketing
 * rather than quietly widening, and #486 is the remainder. The fix follows that commit's pattern
 * exactly — flush the write inside the service, catch {@link org.springframework.dao.OptimisticLockingFailureException},
 * translate to {@code ResourceNotFoundException} — rather than inventing a second treatment.
 *
 * <p><strong>The shape.</strong> {@code findById().orElseThrow(ResourceNotFoundException)} guards
 * the SIMPLE absent-id case, so a random UUID was already a typed 404 (the three {@code unknownId}
 * arms below are honest regression guards — they passed BEFORE the fix too). The defect is the
 * other shape: the row is visible when the service READS it and gone when the service WRITES it,
 * so Hibernate's row-count check fails at flush — {@code "Batch update returned unexpected row
 * count from update [0] ... delete from customers where id=?"} — surfacing as
 * {@code ObjectOptimisticLockingFailureException}. On the DELETE paths there was no flush inside
 * the method at all, so that failure was raised at the TRANSACTION BOUNDARY, after the method
 * returned, where no catch inside it could reach it. Since #434 it lands in
 * {@code GlobalExceptionHandler}'s {@code OptimisticLockingFailureException} handler: 409.
 *
 * <p><strong>Why 404 is unconditionally right for these three and not in general.</strong> Neither
 * {@code Customer} nor {@code ShopStaff} carries a JPA {@code @Version}, so the statement's
 * predicate is {@code id = ?} alone and zero affected rows can mean exactly one thing — no such
 * row is visible to this transaction. {@code Product}, {@code Shop}, {@code Order} and
 * {@code MediaAsset} DO carry {@code @Version}; for those a 409 is defensible and the #434 handler
 * is deliberately untouched.
 *
 * <p><strong>How the race is made real and deterministic.</strong> No mocking, and in particular
 * no spy on a Spring Data repository — those are interface proxies, {@code callRealMethod()} cannot
 * run on them, and the resulting {@code MockitoException} hits the catch-all and produces a 500
 * that looks exactly like a perfect reproduction of the original bug (the instrument trap #390
 * recorded). Instead the race is built out of PostgreSQL's own concurrency control on a SECOND,
 * independent connection:
 *
 * <ol>
 *   <li>the second connection issues {@code DELETE ... WHERE id = ?} and does NOT commit, so it
 *       holds the row lock while the row stays fully visible to everyone else (READ COMMITTED);</li>
 *   <li>the request runs on its own thread: its {@code SELECT} therefore SEES the row (this is the
 *       "visible at read time" half, and it is guaranteed, not hoped for), and its {@code DELETE}/
 *       {@code UPDATE} then BLOCKS on that lock;</li>
 *   <li>{@link #awaitRequestBlockedOnRowLock} proves the request really is parked in the write —
 *       it polls {@code pg_stat_activity} for a backend with {@code wait_event_type = 'Lock'} on
 *       that table and FAILS the test if the block never appears or if the request finishes early.
 *       This is what stops the harness silently degrading into the vacuous shape (row already gone
 *       at read time), which would answer 404 for the wrong reason;</li>
 *   <li>only then does the second connection COMMIT — the row genuinely vanishes — and the blocked
 *       statement wakes to find 0 affected rows.</li>
 * </ol>
 *
 * The services are untouched by the harness and run their own real transaction through the real
 * controller and the real {@code GlobalExceptionHandler}, so the database sees production's exact
 * statement sequence: our SELECT, someone else's committed DELETE, our write.
 *
 * <p><strong>Falsifiability.</strong> Run against the unfixed tree the three {@code vanishes} arms
 * answer <b>409</b> ({@code Status expected:<404> but was:<409>}), with the #434 handler logging
 * the row-count message above. They are the arms that make this class evidence rather than
 * decoration. The CONTROL arms (a live customer still deletes/updates, a live grant still revokes)
 * are the other half: without them, "answer 404 to everything" would pass this suite.
 *
 * <p><strong>The customer/staff asymmetry recorded here is now GONE (#500).</strong> When #486
 * wrote this class, the staff 404 carried a full RFC 7807 body ({@code type = .../errors/not-found})
 * because {@code StaffController} let {@code ResourceNotFoundException} reach
 * {@code GlobalExceptionHandler}, while the customer 404 was a bare, empty-bodied 404 because
 * {@code CustomerController} caught the exception itself. That divergence was deliberately left
 * alone and asserted as it actually behaved, not as it ought to. #500 removed the local catch, so
 * both surfaces now answer with the same typed body, and {@code CustomerController} no longer
 * catches the exception at all. The customer arms below assert only the status, which stays
 * correct either way — the typed body they now also receive is pinned by
 * {@code TypedNotFoundBodyIntegrationTest}, which is where that contract lives.
 *
 * <p>Harness mirrors {@code MarketingMissingRowStatusIntegrationTest}: real Postgres 15 + the
 * Flyway-managed RLS schema, NOT {@code @Transactional} (seeded rows must commit, and each service
 * call must own its transaction exactly as in production), fresh random tenants per test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class VanishedRowStatusIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private static final String NOT_FOUND_TYPE = "https://jtoye.uk/errors/not-found";

    /** How long to wait for the request thread to park on the row lock before giving up. */
    private static final long BLOCK_DEADLINE_MILLIS = 30_000L;

    // ------------------------------------------------------------------
    // Regression guards: a genuinely absent id (already correct before the fix)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /customers/{unknown id} is a 404")
    void deleteCustomer_unknownId_is404() throws Exception {
        UUID tenant = seedTenant();
        mockMvc.perform(delete("/api/v1/customers/" + UUID.randomUUID()).with(vendor(tenant)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /customers/{unknown id} is a 404")
    void updateCustomer_unknownId_is404() throws Exception {
        UUID tenant = seedTenant();
        mockMvc.perform(put("/api/v1/customers/" + UUID.randomUUID())
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /staff/{unknown id} is a typed 404")
    void revokeGrant_unknownId_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        MockHttpServletResponse response = mockMvc
                .perform(delete("/api/v1/staff/" + UUID.randomUUID()).with(groupAdmin(tenant)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains(NOT_FOUND_TYPE);
    }

    // ------------------------------------------------------------------
    // The defect: the row vanishes between the service's read and its write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /customers/{id} when the row vanishes mid-transaction is 404, not 409/5xx")
    void deleteCustomer_rowVanishesMidTransaction_is404() throws Exception {
        UUID tenant = seedTenant();
        UUID customer = seedCustomer(tenant);

        MockHttpServletResponse response = performWhileRowVanishes(
                "customers", customer,
                delete("/api/v1/customers/" + customer).with(vendor(tenant)));

        assertThat(response.getStatus())
                .as("a customer that vanished mid-transaction is GONE, not a conflict to retry")
                .isEqualTo(404);
        assertThat(rowExists("customers", customer))
                .as("the concurrent deleter really did remove the row (the arm is not vacuous)")
                .isFalse();
    }

    @Test
    @DisplayName("PUT /customers/{id} when the row vanishes mid-transaction is 404, not 409/5xx")
    void updateCustomer_rowVanishesMidTransaction_is404() throws Exception {
        UUID tenant = seedTenant();
        UUID customer = seedCustomer(tenant);

        MockHttpServletResponse response = performWhileRowVanishes(
                "customers", customer,
                put("/api/v1/customers/" + customer)
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson()));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(rowExists("customers", customer)).isFalse();
    }

    @Test
    @DisplayName("DELETE /staff/{id} when the grant vanishes mid-transaction is a typed 404, not 409/5xx")
    void revokeGrant_rowVanishesMidTransaction_isTyped404() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID grant = seedGrant(tenant, shop);

        MockHttpServletResponse response = performWhileRowVanishes(
                "shop_staff", grant,
                delete("/api/v1/staff/" + grant).with(groupAdmin(tenant)));

        assertThat(response.getStatus())
                .as("a staff grant that vanished mid-transaction is GONE, not a conflict to retry")
                .isEqualTo(404);
        assertThat(response.getContentAsString())
                .as("and it is the same typed RFC 7807 body as the absent-at-read-time 404")
                .contains(NOT_FOUND_TYPE);
        assertThat(rowExists("shop_staff", grant)).isFalse();
    }

    // ------------------------------------------------------------------
    // CONTROL ARM — without these, "answer 404 to everything" passes the suite
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CONTROL: a live customer still deletes (204) and is really gone")
    void deleteCustomer_liveRow_stillSucceeds() throws Exception {
        UUID tenant = seedTenant();
        UUID customer = seedCustomer(tenant);

        mockMvc.perform(delete("/api/v1/customers/" + customer).with(vendor(tenant)))
                .andExpect(status().isNoContent());

        assertThat(rowExists("customers", customer)).isFalse();
    }

    @Test
    @DisplayName("CONTROL: a live customer still updates (200) and the new name is persisted")
    void updateCustomer_liveRow_stillSucceeds() throws Exception {
        UUID tenant = seedTenant();
        UUID customer = seedCustomer(tenant);

        mockMvc.perform(put("/api/v1/customers/" + customer)
                        .with(vendor(tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT name FROM customers WHERE id = ?", String.class, customer))
                .isEqualTo("Updated Customer");
    }

    @Test
    @DisplayName("CONTROL: a live staff grant still revokes (204) and is really gone")
    void revokeGrant_liveRow_stillSucceeds() throws Exception {
        UUID tenant = seedTenant();
        UUID shop = seedShop(tenant);
        UUID grant = seedGrant(tenant, shop);

        mockMvc.perform(delete("/api/v1/staff/" + grant).with(groupAdmin(tenant)))
                .andExpect(status().isNoContent());

        assertThat(rowExists("shop_staff", grant)).isFalse();
    }

    // ------------------------------------------------------------------
    // The race
    // ------------------------------------------------------------------

    /**
     * Perform {@code request} while {@code table.id = rowId} is removed by an INDEPENDENT,
     * genuinely committed transaction, strictly between the request's read and its write.
     *
     * <p>See the class javadoc for why this is built from a real row lock rather than a mock. The
     * three failure modes that would make this vacuous all abort the test loudly: the deleter not
     * matching a row ({@code executeUpdate() == 1} is asserted), the request never blocking, and
     * the request completing before the deleter commits.
     */
    private MockHttpServletResponse performWhileRowVanishes(String table, UUID rowId, RequestBuilder request)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement =
                         blocker.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
                statement.setObject(1, rowId);
                assertThat(statement.executeUpdate())
                        .as("the concurrent deleter must actually target the seeded row")
                        .isEqualTo(1);
            }
            // NOT committed. The row is still visible to every other transaction under READ
            // COMMITTED, so the request's SELECT will see it — but the row lock is held, so the
            // request's DELETE/UPDATE will block until the line marked COMMIT below.

            Future<MockHttpServletResponse> pending =
                    pool.submit(() -> mockMvc.perform(request).andReturn().getResponse());

            awaitRequestBlockedOnRowLock(table, pending);

            blocker.commit(); // COMMIT: the row vanishes for real, and the blocked write wakes to 0 rows.
            return pending.get(60, SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Block until the request thread is provably parked inside its write, waiting on the row lock
     * the deleter holds. This is the instrument that keeps the race honest: if the request never
     * enters the window (it would then be answering about an already-absent row, a completely
     * different code path that answers 404 for the wrong reason) this fails instead of passing.
     */
    private void awaitRequestBlockedOnRowLock(String table, Future<MockHttpServletResponse> pending)
            throws Exception {
        long deadline = System.currentTimeMillis() + BLOCK_DEADLINE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (pending.isDone()) {
                // get() rethrows whatever actually went wrong on the request thread.
                MockHttpServletResponse early = pending.get();
                fail("the request finished (status " + early.getStatus() + ") without ever blocking on "
                        + table + " — it never entered the read/write race window, so this arm would "
                        + "have been vacuous");
            }
            Long blocked = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity "
                            + " WHERE datname = current_database() "
                            + "   AND state = 'active' "
                            + "   AND wait_event_type = 'Lock' "
                            + "   AND query ILIKE ?",
                    Long.class, "%" + table + "%");
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(25L);
        }
        List<Map<String, Object>> activity = jdbc.queryForList(
                "SELECT pid, state, wait_event_type, wait_event, query FROM pg_stat_activity "
                        + " WHERE datname = current_database()");
        fail("the request never blocked on a row lock for " + table + " within "
                + BLOCK_DEADLINE_MILLIS + "ms; pg_stat_activity was " + activity);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A day-one vendor holding the customers write scope the Phase-25 CR-01 gate requires. */
    private static RequestPostProcessor vendor(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_customers:write"));
    }

    /** A realm admin — the D-03 implicit-GROUP_ADMIN bridge the staff endpoints gate on. */
    private static RequestPostProcessor groupAdmin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    private UUID seedTenant() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "vanished-row-tenant-" + tenant);
        return tenant;
    }

    private UUID seedShop(UUID tenant) {
        UUID shop = UUID.randomUUID();
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, true, 0)",
                shop, tenant, "shop-" + shop, "slug-" + shop, "1 Test Street, London");
        return shop;
    }

    private UUID seedCustomer(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, tenant_id, name, email, phone, allergen_restrictions, "
                        + "  created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, NOW(), NOW())",
                id, tenant, "Vanishing Customer", "customer-" + id + "@example.com", "+441234567890");
        return id;
    }

    /**
     * A shop-scoped SHOP_MANAGER grant. Deliberately NOT a GROUP_ADMIN row: revoking a GROUP_ADMIN
     * takes the D-11 last-admin row lock BETWEEN the read and the write, which would change the
     * statement sequence under test.
     */
    private UUID seedGrant(UUID tenant, UUID shop) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, "
                        + "  created_at, created_by) "
                        + "VALUES (?, ?, ?, ?, 'SHOP_MANAGER', 'OPERATOR', NOW(), ?)",
                id, tenant, UUID.randomUUID(), shop, UUID.randomUUID());
        return id;
    }

    private boolean rowExists(String table, UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
        return n != null && n > 0;
    }

    private static String customerJson() {
        return "{\"name\":\"Updated Customer\",\"email\":\"updated@example.com\","
                + "\"phone\":\"+449876543210\",\"allergenRestrictions\":3}";
    }
}
