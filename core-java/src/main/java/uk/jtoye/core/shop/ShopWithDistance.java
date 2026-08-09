package uk.jtoye.core.shop;

import java.util.UUID;

/**
 * A published shop and its distance from a caller-supplied coordinate, as computed by the
 * database (33-06 / #460 link 5).
 *
 * <p>A Spring Data <em>interface projection</em> over the native query in
 * {@link ShopRepository#findPublishedNear}. The proxy is backed by the JDBC result tuple, so
 * {@code distanceKm} is the number PostgreSQL produced — not a second calculation performed in
 * Java over the same inputs.
 *
 * <h2>Why the distance is never recomputed</h2>
 *
 * <p>The ordering and the displayed figure come from ONE SQL expression. Computing the sort in
 * SQL and the label in Java gives two formulas that can drift by a constant, a radius, or a
 * rounding rule, and the symptom is a correctly-ordered list whose printed distances are not
 * monotonic. No unit test on either half sees that, because each half is right on its own.
 *
 * <h2>Why this carries an id and not the shop's fields</h2>
 *
 * <p>The obvious shape — a projection exposing every column {@code PublicShopDto} needs — cannot
 * express {@code shops.opening_hours}. That column is {@code jsonb}, mapped on the entity with
 * {@code @JdbcTypeCode(SqlTypes.JSON)}; a native tuple hands back the raw JSON, and a projection
 * getter typed {@code Map<String, String>} fails conversion. Reproducing Hibernate's JSON handling
 * in a second place would give the storefront two shop-mapping paths that can disagree, and the
 * located one would be the one quietly missing opening hours.
 *
 * <p>So the projection carries identity plus the distance, and the service resolves the entities
 * through the SAME {@code toPublicShopDto} that the unlocated listing uses. One extra query per
 * page, bounded by the page size; in exchange, a located result and an unlocated result differ by
 * exactly one field.
 *
 * <p>{@code slug} is included because it is a plain {@code varchar} and it lets an ordering
 * assertion name what it is asserting without a second lookup.
 */
public interface ShopWithDistance {

    /** Primary key of the published shop. */
    UUID getId();

    /** Stable public identifier — handy in assertions and logs; carries no personal data. */
    String getSlug();

    /**
     * Great-circle distance in kilometres from the caller's coordinate to the shop's stored
     * coordinate, computed by the {@code asin} haversine in {@link ShopRepository#findPublishedNear}
     * and mapped from the {@code distance_km} column alias.
     */
    Double getDistanceKm();
}
