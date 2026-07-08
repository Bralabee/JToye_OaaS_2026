package uk.jtoye.core.gdpr;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Persistence for {@link ErasureRecord}, the durable proof-of-erasure artifact.
 * Tenant-scoped via the V42 RLS policies on {@code erasure_records}.
 */
@Repository
public interface ErasureRecordRepository extends JpaRepository<ErasureRecord, UUID> {
}
