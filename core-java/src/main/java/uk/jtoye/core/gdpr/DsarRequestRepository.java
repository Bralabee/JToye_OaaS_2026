package uk.jtoye.core.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository over the platform-level DSAR intake queue (V62).
 *
 * <p><b>Unlike every other repository in this codebase, these queries are NOT tenant-scoped</b> —
 * {@code dsar_request} has no {@code tenant_id} and no RLS policy, for the reasons V62 and
 * {@link DsarRequest} set out. That is not a hole in the tenant wall: the rows here hold no tenant
 * data at all (a request type, a timestamp and a one-way digest), and the reach that actually
 * touches tenant data belongs to plan 31-09's background worker, which gets it by iterating
 * tenants and pinning {@code app.current_tenant_id} one at a time — never by a query that ignores
 * the wall.
 */
@Repository
public interface DsarRequestRepository extends JpaRepository<DsarRequest, UUID> {

    /**
     * Replay lookup for the {@code Idempotency-Key} contract. Unique across the endpoint by
     * {@code uq_dsar_request_idempotency_key} — see V62 for why the constraint is on the key alone
     * rather than on (subject digest, key).
     */
    Optional<DsarRequest> findByIdempotencyKey(String idempotencyKey);

    /**
     * The background worker's claim query (plan 31-09): outstanding requests in a given state,
     * oldest first, so a backlog drains in the order it was lodged rather than in whatever order
     * the heap happens to return. Backed by the partial index
     * {@code idx_dsar_request_outstanding}, which covers exactly {@code completed_at IS NULL}.
     */
    List<DsarRequest> findByStatusAndCompletedAtIsNullOrderByReceivedAtAsc(DsarRequest.Status status);

    /**
     * All requests lodged for a subject digest, oldest first. The digest is the ONLY handle on a
     * subject that exists here — there is no readable address to search by, by design.
     */
    List<DsarRequest> findBySubjectEmailSha256OrderByReceivedAtAsc(String subjectEmailSha256);
}
