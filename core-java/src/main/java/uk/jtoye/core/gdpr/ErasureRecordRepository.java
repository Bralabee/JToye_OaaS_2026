package uk.jtoye.core.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@link ErasureRecord}, the durable proof-of-erasure artifact.
 * Tenant-scoped via the V42 RLS policies on {@code erasure_records}.
 */
@Repository
public interface ErasureRecordRepository extends JpaRepository<ErasureRecord, UUID> {

    /**
     * Write the number of review photos the store ACTUALLY removed onto an erasure record — once.
     *
     * <p>Review photos are deleted only after the erasure transaction commits (#764), so the record
     * is inserted with {@code photos_deleted = 0} and this sets the real count afterwards. This is the
     * ONLY update the database accepts on an erasure record: V67's
     * {@code erasure_records_photo_count_update} policy limits the targets to this tenant's zero-count
     * records, and its {@code erasure_records_write_once} trigger refuses (SQLSTATE 42501), for every
     * role, any update that is not {@code photos_deleted} going from 0 to a positive count with every
     * other column unchanged. Call it only with a positive count. The explicit {@code tenant_id}
     * predicate is scoping in its own right, not a stand-in for RLS.
     *
     * <p>Post-commit only, and it MUST run in a {@code REQUIRES_NEW} transaction: inside an
     * {@code afterCommit} hook a default-propagation call joins the transaction that has already
     * committed and the write is lost.
     *
     * @return rows updated — 1 on success; 0 means the record was not visible to this tenant or
     *         already carried a count
     */
    @Modifying
    @Query(value = "UPDATE erasure_records SET photos_deleted = :photosDeleted "
            + "WHERE id = :id AND tenant_id = :tenantId AND photos_deleted = 0", nativeQuery = true)
    int recordPhotosDeleted(@Param("id") UUID id,
                            @Param("tenantId") UUID tenantId,
                            @Param("photosDeleted") int photosDeleted);
}
