package uk.jtoye.core.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Seeds an explicit OPERATOR {@code shop_staff} grant for a vendor subject (Phase 37-02, RWO-003/004).
 *
 * <p>Under strict-scoping OFF an ungranted vendor user is an implicit tenant-wide GROUP_ADMIN
 * ({@code ShopAccessService.isGroupAdminForUser}'s last line), so a test could reach a gated
 * endpoint without saying what access it needed. D-06 removes that rule. A test that needs access
 * now states it with this helper instead of relying on the implicit admin.
 *
 * <p>What it writes, in ONE transaction on ONE connection:
 * <ol>
 *   <li>{@code set_config('app.current_tenant_id', tenant, true)} — the transaction-local tenant GUC
 *       every FORCE-RLS policy here reads through {@code current_tenant_id()};</li>
 *   <li>a {@code user_directory} row (ON CONFLICT DO NOTHING — the directory is keyed per
 *       (tenant, user), and {@code StaffManagementService.grant} requires one to exist);</li>
 *   <li>a {@code shop_staff} row with {@code grant_source = 'OPERATOR'} and
 *       {@code created_by = }{@link #TEST_OPERATOR} — the shape an operator grant has in production,
 *       which strict-scoping ON keeps honouring (a JIT-sourced row it would de-honour).</li>
 * </ol>
 *
 * <p><strong>No superuser shortcut (T-37-03).</strong> The rows are written through the policies,
 * not around them: on the NOSUPERUSER application role both INSERTs are refused (42501) unless the
 * GUC is pinned to the row's tenant, and {@code ShopGrantsIntegrationTest} proves exactly that. The
 * helper also reads the GUC back before writing, so a regression that lost the pin (an autocommit
 * connection, a second pooled connection) fails here, loudly, rather than writing with no tenant.
 */
public final class ShopGrants {

    /** The fixed {@code created_by} of every test operator grant — recognisable in a failing assertion. */
    public static final UUID TEST_OPERATOR = UUID.fromString("00000000-0000-0000-0000-0000000037a2");

    private static final String PIN = "SELECT set_config('app.current_tenant_id', ?, true)";
    private static final String READ_PIN = "SELECT current_setting('app.current_tenant_id', true)";
    private static final String DIRECTORY = "INSERT INTO user_directory (tenant_id, user_id, email, display_name, last_seen) "
            + "VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, user_id) DO NOTHING";
    private static final String GRANT = "INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, grant_source, created_by, created_at) "
            + "VALUES (?, ?, ?, ?, ?, 'OPERATOR', ?, now())";

    private ShopGrants() {
    }

    /**
     * Grant {@code userId} the {@code role} on {@code shopIdOrNull} (NULL = tenant-wide, the only
     * legal shape for GROUP_ADMIN) as an OPERATOR grant, and record the user in the directory.
     *
     * @return the new {@code shop_staff.id}
     */
    public static UUID grantOperator(JdbcTemplate jdbc, UUID tenantId, UUID userId, UUID shopIdOrNull,
                                     String role, String email) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if ("GROUP_ADMIN".equals(role) && shopIdOrNull != null) {
            throw new IllegalArgumentException("GROUP_ADMIN is tenant-wide only — pass a null shop id");
        }
        DataSource dataSource = Objects.requireNonNull(jdbc.getDataSource(), "JdbcTemplate has no DataSource");
        UUID grantId = UUID.randomUUID();
        // A JDBC transaction on the same DataSource the JdbcTemplate uses: every statement below runs on
        // the connection this transaction binds, so the transaction-local GUC is still set when the
        // INSERTs run. Joins an outer transaction on the same DataSource when one is active.
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.queryForObject(PIN, String.class, tenantId.toString());
            String pinned = jdbc.queryForObject(READ_PIN, String.class);
            if (!tenantId.toString().equals(pinned)) {
                throw new IllegalStateException("tenant GUC not pinned on the seeding connection (read back '"
                        + pinned + "', expected " + tenantId + ") — the grant would bypass or trip RLS");
            }
            jdbc.update(DIRECTORY, tenantId, userId, email, "Test User " + userId);
            jdbc.update(GRANT, grantId, tenantId, userId, shopIdOrNull, role, TEST_OPERATOR);
        });
        return grantId;
    }
}
