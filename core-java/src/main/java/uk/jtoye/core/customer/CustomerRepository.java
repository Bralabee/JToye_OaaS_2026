package uk.jtoye.core.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Customer entity.
 * All queries are automatically tenant-scoped via RLS policies.
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    /**
     * Find customer by email (tenant-scoped).
     * Email is unique per tenant.
     */
    Optional<Customer> findByEmail(String email);

    /**
     * Check if customer with email exists (tenant-scoped).
     */
    boolean existsByEmail(String email);

    /**
     * Find customer by phone (tenant-scoped).
     */
    Optional<Customer> findByPhone(String phone);

    /**
     * Option C (Phase 39-08): per-shop customer narrowing for non-{@code allShops} callers.
     * Matches customers in {@code tenantId} whose {@code originShopId} is in {@code shopIds}
     * OR who have at least one order placed at a shop in {@code shopIds}.
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE c.tenantId = :tenantId
              AND (c.originShopId IN :shopIds
                   OR EXISTS (
                       SELECT 1 FROM Order o
                       WHERE o.tenantId = :tenantId
                         AND o.customerId = c.id
                         AND o.shopId IN :shopIds
                   ))
            """)
    Page<Customer> findTenantScopedInGrantSet(@Param("tenantId") UUID tenantId,
                                              @Param("shopIds") Collection<UUID> shopIds,
                                              Pageable pageable);

    /**
     * Option C (Phase 39-08): single-customer lookup narrowed to {@code shopIds} for
     * non-{@code allShops} callers. Out-of-scope customer IDs return {@link Optional#empty()}
     * so callers translate to HTTP 404 (no existence oracle).
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE c.id = :id
              AND c.tenantId = :tenantId
              AND (c.originShopId IN :shopIds
                   OR EXISTS (
                       SELECT 1 FROM Order o
                       WHERE o.tenantId = :tenantId
                         AND o.customerId = c.id
                         AND o.shopId IN :shopIds
                   ))
            """)
    Optional<Customer> findByIdAndTenantScopedInGrantSet(@Param("id") UUID id,
                                                         @Param("tenantId") UUID tenantId,
                                                         @Param("shopIds") Collection<UUID> shopIds);

    /**
     * Every customer id + email for ONE tenant — the projection the DSAR fan-out matches subject
     * digests against (Phase 31, plan 31-09).
     *
     * <p><b>The {@code tenant_id} predicate is explicit and mandatory.</b> RLS already scopes this
     * table under FORCE row-level security, and the fan-out worker pins the GUC before every call;
     * the predicate is defence in depth, and it is also the property that makes this method safe to
     * read at a glance. The worker's cross-tenant reach comes from ITERATING tenants, never from a
     * query that ignores the wall — a repository method without this predicate would be exactly the
     * hole D-17's design exists to avoid.
     *
     * <p>A projection rather than whole entities: this loads two columns, is never used to mutate,
     * and keeps the digest comparison in Java where there is exactly ONE normalisation
     * implementation ({@code DsarSubjectDigest} records why the SQL equivalent is not equivalent).
     *
     * @return rows of {@code [UUID id, String email]}
     */
    @Query(value = "SELECT id, email FROM customers WHERE tenant_id = :tenantId",
            nativeQuery = true)
    List<Object[]> findIdAndEmailByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * GDPR Article-17 scrub of pre-erasure PII from the append-only {@code customers_aud}
     * Envers history (Issue #84 [P1-2]). Redacts the subject's name and nulls
     * email/phone/notes across every audit revision of the customer.
     *
     * <p>{@code tenant_id} is an explicit WHERE predicate — mandatory defense-in-depth
     * per the multi-tenancy constraint: a native UPDATE on an {@code _aud} table must
     * never rely on RLS alone. The V42 {@code customers_aud_update_policy} gates the
     * same scope at the policy layer.
     *
     * @return number of audit rows scrubbed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE customers_aud SET name = :redacted, email = NULL, "
            + "phone = NULL, notes = NULL "
            + "WHERE tenant_id = :tenantId AND id = :customerId",
            nativeQuery = true)
    int scrubCustomerAudit(@Param("tenantId") UUID tenantId,
                           @Param("customerId") UUID customerId,
                           @Param("redacted") String redacted);
}
