package uk.jtoye.core.geo;

/**
 * The mean centroid of every postcode UNIT sharing one outward code — the "district" answer to
 * a customer who typed {@code SE22} rather than a full address.
 *
 * <p>A Spring Data interface projection over the aggregate in
 * {@link PostcodeCentroidRepository#findDistrictCentroid}, following the same shape as
 * {@code ShopWithDistance}: native query, column aliases, no entity materialised.
 *
 * <h2>Why the coordinates are boxed {@code Double} and {@link #getUnits()} exists</h2>
 *
 * <p>Both are the same defence against one trap. {@code SELECT avg(...)} with no {@code GROUP BY}
 * returns <strong>one row of NULLs</strong> when nothing matches — never zero rows — so a
 * {@code projection != null} check is satisfied by a miss and always passes. Boxing the
 * coordinates makes that NULL representable instead of silently unboxing to {@code 0.0}, which
 * would hand back Null Island: a point roughly 5,800 km from London but nearer the origin than
 * any real shop, and therefore <em>the nearest kitchen to every customer on the platform</em>
 * under a distance sort. {@link #getUnits()} is the positive statement that rows were actually
 * aggregated, and callers must gate on {@code getUnits() > 0} <em>and</em> both coordinates
 * being non-null before trusting this projection.
 */
public interface DistrictCentroid {

    /** Mean latitude of the matched units, or {@code null} when none matched. */
    Double getLatitude();

    /** Mean longitude of the matched units, or {@code null} when none matched. */
    Double getLongitude();

    /** How many postcode units were averaged. Zero means the district is not in the dataset. */
    long getUnits();
}
