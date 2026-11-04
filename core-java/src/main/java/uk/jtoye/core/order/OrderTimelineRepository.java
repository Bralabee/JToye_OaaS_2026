package uk.jtoye.core.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.lang.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only access to an order's Envers history for the activity timeline (issue #857).
 *
 * <p>Bound to {@link Order} only because Spring Data needs an entity to host a native query; nothing
 * here reads or writes {@code orders}. Both tables are read under their tenant policies (FORCE RLS on
 * {@code orders_aud}; {@code user_directory} is tenant-scoped too), and the tenant predicate is explicit
 * on top. {@code revinfo} carries no tenant policy by design (V4) and is reached only through an
 * {@code orders_aud} row already confined to the caller's tenant and order.
 */
@Repository
public interface OrderTimelineRepository extends JpaRepository<Order, UUID> {

    /** One revision of the order, oldest first, with who made it. */
    interface RevisionRow {
        Integer getRev();

        @Nullable Short getRevtype();

        @Nullable String getStatus();

        @Nullable String getPaymentStatus();

        @Nullable Boolean getVoided();

        @Nullable String getVoidReason();

        @Nullable String getCancelReasonCode();

        @Nullable Long getRevtstmp();

        @Nullable String getUserId();

        @Nullable String getDisplayName();

        @Nullable String getEmail();
    }

    /**
     * {@code user_directory.user_id} is a UUID and {@code revinfo.user_id} is the JWT subject as text, so the
     * join compares as text: a subject that is not a UUID (a service account) simply finds no directory row,
     * where a cast would throw.
     */
    @Query(value = """
            SELECT a.rev AS rev,
                   a.revtype AS revtype,
                   a.status AS status,
                   a.payment_status AS payment_status,
                   (a.voided_at IS NOT NULL) AS voided,
                   a.void_reason AS void_reason,
                   a.cancel_reason_code AS cancel_reason_code,
                   r.revtstmp AS revtstmp,
                   r.user_id AS user_id,
                   u.display_name AS display_name,
                   u.email AS email
            FROM orders_aud a
            JOIN revinfo r ON r.rev = a.rev
            LEFT JOIN user_directory u
                   ON u.tenant_id = :tenantId AND CAST(u.user_id AS TEXT) = r.user_id
            WHERE a.id = :orderId
              AND a.tenant_id = :tenantId
              AND a.revtype IN (0, 1)
            ORDER BY a.rev ASC
            """, nativeQuery = true)
    List<Object[]> findRevisionRows(@Param("tenantId") UUID tenantId, @Param("orderId") UUID orderId);

    /**
     * The rows as {@link RevisionRow}s. Read by POSITION: a native interface projection matches columns by
     * alias, and Postgres folds an unquoted alias to lower case, so a camelCase getter silently read null.
     */
    default List<RevisionRow> findRevisions(UUID tenantId, UUID orderId) {
        List<RevisionRow> rows = new ArrayList<>();
        for (Object[] c : findRevisionRows(tenantId, orderId)) {
            rows.add(new Revision(
                    c[0] == null ? null : ((Number) c[0]).intValue(),
                    c[1] == null ? null : ((Number) c[1]).shortValue(),
                    (String) c[2],
                    (String) c[3],
                    (Boolean) c[4],
                    (String) c[5],
                    (String) c[6],
                    c[7] == null ? null : ((Number) c[7]).longValue(),
                    (String) c[8],
                    (String) c[9],
                    (String) c[10]));
        }
        return rows;
    }

    /** The positional row. */
    record Revision(Integer rev, Short revtype, String status, String paymentStatus, Boolean voided,
                    String voidReason, String cancelReasonCode, Long revtstmp, String userId,
                    String displayName, String email) implements RevisionRow {
        @Override public Integer getRev() { return rev; }
        @Override public Short getRevtype() { return revtype; }
        @Override public String getStatus() { return status; }
        @Override public String getPaymentStatus() { return paymentStatus; }
        @Override public Boolean getVoided() { return voided; }
        @Override public String getVoidReason() { return voidReason; }
        @Override public String getCancelReasonCode() { return cancelReasonCode; }
        @Override public Long getRevtstmp() { return revtstmp; }
        @Override public String getUserId() { return userId; }
        @Override public String getDisplayName() { return displayName; }
        @Override public String getEmail() { return email; }
    }
}
