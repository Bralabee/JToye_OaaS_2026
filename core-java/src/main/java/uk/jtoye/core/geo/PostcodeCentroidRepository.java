package uk.jtoye.core.geo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Access to {@link PostcodeCentroid}: a primary-key lookup for a full unit, and one bounded
 * range aggregate for an outward code.
 *
 * <p>{@code findById} is the unit path, and the key is the normalised postcode, so a hit is a
 * primary-key hit — O(log n) on 1.7 M rows with no scan.
 *
 * <p>{@link #findDistrictCentroid} is the district path added by 33-08 (#619), and it is the
 * first non-primary-key query on this table. <strong>The earlier claim that this repository
 * contains "no {@code LIKE}, nothing an untrusted string can steer" is superseded rather than
 * merely relaxed:</strong> there is still no {@code LIKE} and still nothing concatenated into
 * SQL, because the range bounds are computed in Java and bound as named parameters. What
 * changed is that a search term now selects a RANGE of keys instead of exactly one, so the
 * index-eligibility and unit-length arguments below carry the safety that the primary key
 * used to carry on its own.
 *
 * <p>No tenant filter and no {@code set_config} pin: {@code postcode_centroid} carries no
 * {@code tenant_id} and has no RLS policy, so this repository is safe to call before or
 * outside a {@code TenantContext}. That is unusual in this codebase and deliberate — see the
 * class comment on {@link PostcodeCentroid}.
 */
@Repository
public interface PostcodeCentroidRepository extends JpaRepository<PostcodeCentroid, String> {

    /**
     * Mean centroid of every unit under one outward code (33-08 / #619).
     *
     * <p>Bounds and length are computed by {@link PostcodeGeocoder}, in Java, for the same
     * reason {@code GeoBounds.boxAround} computes its bounding box in Java: a value the planner
     * can compare directly stays index-eligible, while an expression built inside SQL does not.
     *
     * @param rangeStart inclusive lower bound, {@code outward + "0AA"}
     * @param rangeEnd   inclusive upper bound, {@code outward + "9ZZ"}
     * @param unitLength {@code outward.length() + 3} — the exact key length of a unit in this
     *                   district, and the guard without which nine districts are averaged as one
     * @return a projection that is NEVER null and whose coordinates ARE null on a miss; gate on
     *         {@link DistrictCentroid#getUnits()} — see that interface's class comment
     */
    // A CLOSED RANGE, NEVER LIKE. The database collates en_US.utf8, so a LIKE prefix cannot use
    // postcode_centroid_pkey and plans as a Parallel Seq Scan over 1,748,230 rows — on an
    // ANONYMOUS endpoint, which makes it a denial-of-service surface (T-33-08-02). Measured
    // 2026-08-09 on the live jtoye-postgres, EXPLAIN (COSTS OFF):
    //   postcode LIKE 'SE22%'                                    -> Parallel Seq Scan
    //   postcode >= 'SE220AA' AND <= 'SE229ZZ' AND length(..)=7   -> Index Scan using
    //                                                               postcode_centroid_pkey
    // and both return the SAME 507 rows, so the fast form is not a different answer.
    //
    // CLOSED (<=) rather than the half-open successor form (>= 'SE22' AND < 'SE23'). The
    // successor requires incrementing the outward code's final character, which has a carry case
    // for a 9- or Z-suffixed code that the permissive search regex accepts even though no real
    // district uses one. Every Code-Point Open inward code is digit + letter + letter, so "0AA"
    // and "9ZZ" are the true inclusive bounds of the district and there is no arithmetic to get
    // wrong.
    //
    // THE LENGTH GUARD IS NOT OPTIONAL AND IS NOT REDUNDANT WITH THE BOUNDS. 'M11 1AA' is stored
    // space-stripped as 'M111AA', which sorts INSIDE [M10AA, M19ZZ]; only length(postcode) = 5
    // excludes it. Measured on the same live table: without the guard 'M1' matches 6,422 units
    // spanning M1 and M11..M19 and averages to (53.459673, -2.220227), which is not Manchester
    // city centre; with it, 548 units averaging (53.477526, -2.236137), which is. The plan stays
    // an Index Scan either way — the guard is applied as a Filter, so it costs nothing.
    //
    // Every value is a NAMED JPA parameter. Nothing is concatenated into this SQL.
    @Query(value = """
            SELECT avg(latitude)  AS latitude,
                   avg(longitude) AS longitude,
                   count(*)       AS units
              FROM postcode_centroid
             WHERE postcode >= :rangeStart
               AND postcode <= :rangeEnd
               AND length(postcode) = :unitLength
            """, nativeQuery = true)
    DistrictCentroid findDistrictCentroid(@Param("rangeStart") String rangeStart,
                                          @Param("rangeEnd") String rangeEnd,
                                          @Param("unitLength") int unitLength);
}
