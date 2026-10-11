package uk.jtoye.core.security.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-37-16 (Phase 37-07, D-07): {@code staff_invite} is walled per tenant BY THE DATABASE, under a role
 * that cannot bypass row-level security.
 *
 * <p>The Testcontainers role is a SUPERUSER, which bypasses every policy, so a test run as it would
 * pass with no policy at all. This class downgrades that role to NOSUPERUSER once per container and
 * asserts the precondition before every test (the {@code TraderIdentityRlsIntegrationTest} recipe).
 * Then, under tenant A's GUC:
 * <ul>
 *   <li>A's invitation is visible and B's is not (an unfiltered SELECT returns A's row only);</li>
 *   <li>an UPDATE of B's row matches zero rows, beside the same UPDATE matching A's own row;</li>
 *   <li>an INSERT stamped with B is refused with SQLSTATE 42501, beside an INSERT stamped with A that
 *       is accepted.</li>
 * </ul>
 * With no GUC at all the table reads empty. Not {@code @Transactional}: every statement runs in its own
 * transaction with the GUC pinned on the same connection. Fresh tenants per test.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class StaffInviteRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Only a superuser may run ALTER ROLE, so this must happen exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = ?", Boolean.class,
                DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses "
                        + "row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("NOSUPERUSER under A's GUC: A's invite visible, B's hidden; UPDATE of B matches 0; INSERT as B refused 42501")
    void databaseRefusesCrossTenantReadUpdateAndInsert() {
        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID inviteA = UUID.randomUUID();
        UUID inviteB = UUID.randomUUID();
        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c, insertSql(), inviteA.toString(), a.toString(),
                "a@example.com", digest('a'))))
                .as("POSITIVE CONTROL: an INSERT stamped with the session's own tenant is accepted")
                .isEqualTo(1);
        assertThat(this.<Integer>inTenant(b, c -> executeUpdate(c, insertSql(), inviteB.toString(), b.toString(),
                "b@example.com", digest('b'))))
                .isEqualTo(1);

        // Read
        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM staff_invite WHERE id = ?::uuid", inviteA.toString())))
                .as("PRECONDITION: A's session sees A's own row, so the zeros below are the wall, not an empty table")
                .isEqualTo(1L);
        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM staff_invite WHERE id = ?::uuid", inviteB.toString())))
                .as("A's session cannot see B's invitation")
                .isZero();
        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM staff_invite WHERE tenant_id IN (?::uuid, ?::uuid)", a.toString(), b.toString())))
                .as("an unfiltered read under A returns A's row only")
                .isEqualTo(1L);
        assertThat(this.<Long>inTenant(a, c -> queryLong(c,
                "SELECT count(*) FROM staff_invite WHERE token_sha256 = ?", digest('b'))))
                .as("a lookup by B's digest under A's GUC finds nothing (the accept path reads by digest under the link's tenant)")
                .isZero();

        // Update
        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c,
                "UPDATE staff_invite SET revoked_at = now() WHERE id = ?::uuid", inviteA.toString())))
                .as("POSITIVE CONTROL: the same UPDATE matches A's own row")
                .isEqualTo(1);
        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c,
                "UPDATE staff_invite SET revoked_at = now() WHERE id = ?::uuid", inviteB.toString())))
                .as("an UPDATE of B's invitation under A's GUC matches zero rows")
                .isZero();
        assertThat(this.<Long>inTenant(b, c -> queryLong(c,
                "SELECT count(*) FROM staff_invite WHERE id = ?::uuid AND revoked_at IS NULL", inviteB.toString())))
                .as("B's invitation is untouched, read under B's own GUC")
                .isEqualTo(1L);

        // Insert stamped with another tenant
        SQLException refusal = this.<SQLException>inTenant(a, c -> refusalOf(c, insertSql(),
                UUID.randomUUID().toString(), b.toString(), "forged@example.com", digest('f')));
        assertThat((Throwable) refusal)
                .as("an INSERT stamped with another tenant under A's GUC must be REFUSED by the database")
                .isNotNull();
        assertThat(refusal.getSQLState()).as("SQLSTATE (message: %s)", refusal.getMessage()).isEqualTo("42501");
        assertThat(refusal.getMessage()).contains("row-level security");
    }

    @Test
    @DisplayName("NOSUPERUSER with no tenant GUC: staff_invite reads empty and refuses an INSERT")
    void noGuc_readsNothing_andWritesNothing() {
        UUID a = seedTenant();
        UUID invite = UUID.randomUUID();
        assertThat(this.<Integer>inTenant(a, c -> executeUpdate(c, insertSql(), invite.toString(), a.toString(),
                "a@example.com", digest('n'))))
                .isEqualTo(1);

        Long visible = new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(c -> queryLong(c,
                        "SELECT count(*) FROM staff_invite WHERE id = ?::uuid", invite.toString())));
        assertThat(visible).as("no tenant pinned: no row").isZero();

        SQLException refusal = new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(c -> refusalOf(c, insertSql(),
                        UUID.randomUUID().toString(), a.toString(), "x@example.com", digest('x'))));
        assertThat((Throwable) refusal).as("no tenant pinned: an INSERT is refused").isNotNull();
        assertThat(refusal.getSQLState()).as(refusal.getMessage()).isEqualTo("42501");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String insertSql() {
        return "INSERT INTO staff_invite (id, tenant_id, email_normalised, shop_id, role, token_sha256, "
                + "expires_at, created_by) "
                + "VALUES (?::uuid, ?::uuid, ?, NULL, 'STAFF', ?, now() + interval '72 hours', gen_random_uuid())";
    }

    /** A distinct 64-hex value per label: the column's shape, not a real digest. */
    private static String digest(char label) {
        return String.valueOf(label).repeat(1) + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 31);
    }

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        // tenants carries no RLS.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "37-07-" + id);
        return id;
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

    private static long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static int executeUpdate(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Runs a write that is expected to be refused and returns the refusal (null if it succeeded). The
     * statement runs inside a savepoint so the refusal does not poison the transaction.
     */
    private static SQLException refusalOf(Connection c, String sql, Object... params) throws SQLException {
        var savepoint = c.setSavepoint();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
            c.rollback(savepoint);
            return null;
        } catch (SQLException e) {
            c.rollback(savepoint);
            return e;
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }
}
