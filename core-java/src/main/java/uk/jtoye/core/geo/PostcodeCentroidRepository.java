package uk.jtoye.core.geo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Primary-key access to {@link PostcodeCentroid}.
 *
 * <p>The only lookup this needs is {@code findById}, and that is the point: the key is the
 * normalised postcode, so a hit is a primary-key hit — O(log n) on 1.7 M rows with no scan,
 * no {@code LIKE}, and nothing an untrusted address string can steer.
 *
 * <p>No tenant filter and no {@code set_config} pin: {@code postcode_centroid} carries no
 * {@code tenant_id} and has no RLS policy, so this repository is safe to call before or
 * outside a {@code TenantContext}. That is unusual in this codebase and deliberate — see the
 * class comment on {@link PostcodeCentroid}.
 */
@Repository
public interface PostcodeCentroidRepository extends JpaRepository<PostcodeCentroid, String> {
}
