package uk.jtoye.core.media;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MediaAsset} (V53 {@code media_asset}).
 *
 * <p>All reads are tenant-scoped by the RLS wall. {@link #findByTenantIdAndSha256}
 * additionally carries an explicit {@code tenantId} predicate and backs the sha256
 * dedup short-circuit (IMG-01): an identical raw upload within a tenant reuses the
 * existing asset rather than storing duplicate bytes (the V53
 * {@code uq_media_asset_tenant_sha} unique index enforces it).
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    /** Dedup short-circuit: the existing asset for an identical raw sha256 within a tenant. */
    Optional<MediaAsset> findByTenantIdAndSha256(UUID tenantId, String sha256);
}
