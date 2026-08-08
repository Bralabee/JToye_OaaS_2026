package uk.jtoye.core.geo;

/**
 * A latitude/longitude bounding box that fully contains a circle of a given radius.
 *
 * <p><strong>Why this exists at all.</strong> The distance query (33-06) has to run under
 * row-level security, and PostgreSQL will only push a predicate below an RLS barrier if the
 * operator is {@code LEAKPROOF}. Plain {@code float8} comparisons are; a call to a distance
 * function is not. So the shape of the query is "cheap leakproof box prefilter, then exact
 * haversine on the survivors", and this class computes the box.
 *
 * <p>It is deliberately NOT a database extension. {@code cube} and {@code earthdistance} were
 * both measured on the live stack and neither can be installed: Flyway runs as
 * {@code jtoye_app}, which is {@code rolsuper=f} with no {@code CREATE} on the database, so
 * even the <em>trusted</em> {@code cube} fails with "permission denied to create extension".
 * Granting that role {@code CREATE ON DATABASE} would be a privilege escalation on the very
 * role the RLS wall is built around. PostGIS is not available at all. Plain arithmetic is the
 * answer here, not a fallback.
 *
 * <p><strong>The contract is containment, not tightness.</strong> A box slightly too large
 * costs a few extra haversine evaluations on rows that then get filtered out. A box slightly
 * too small silently drops shops that really are inside the radius — and that failure is
 * invisible: no error, no log, just "the nearest kitchen never appears". Every rounding
 * decision here is therefore made outward.
 *
 * @param minLatitude  southern edge, clamped to -90
 * @param maxLatitude  northern edge, clamped to +90
 * @param minLongitude western edge, clamped to -180
 * @param maxLongitude eastern edge, clamped to +180
 */
public record GeoBounds(
        double minLatitude,
        double maxLatitude,
        double minLongitude,
        double maxLongitude) {

    /**
     * Mean Earth radius (IUGG), in kilometres. The same constant the distance query uses, so
     * the prefilter and the exact test agree about what "5 km" means.
     */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * The smallest box that is guaranteed to contain every point within {@code radiusKm} of
     * the given centre.
     *
     * @param latitude  centre latitude in degrees
     * @param longitude centre longitude in degrees
     * @param radiusKm  radius in kilometres; must be finite and non-negative
     * @throws IllegalArgumentException if the radius is negative or any argument is not finite
     */
    public static GeoBounds boxAround(double latitude, double longitude, double radiusKm) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            throw new IllegalArgumentException(
                    "latitude and longitude must be finite: " + latitude + ", " + longitude);
        }
        if (!Double.isFinite(radiusKm) || radiusKm < 0) {
            throw new IllegalArgumentException(
                    // Named explicitly: an inverted box (min > max) matches NOTHING, so a bad
                    // radius would surface to a customer as "no shops near you" rather than as
                    // an error anybody could act on.
                    "radius must be finite and non-negative, was: " + radiusKm);
        }

        double latDelta = Math.toDegrees(radiusKm / EARTH_RADIUS_KM);

        // Longitude degrees shrink towards the poles by cos(latitude). Two guards, for two
        // different failure modes:
        //   - cos() approaches 0 at the poles, so the divisor blows the half-extent up to
        //     tens of thousands of degrees (or infinity exactly at the pole);
        //   - the box may also cross a pole, after which "east" stops being meaningful.
        // In both cases the correct answer is the full longitude range: near a pole, every
        // meridian genuinely is close by.
        double cosLat = Math.cos(Math.toRadians(latitude));
        double lonDelta;
        if (cosLat <= 1e-12) {
            lonDelta = 180.0;
        } else {
            lonDelta = Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * cosLat));
        }

        double minLat = latitude - latDelta;
        double maxLat = latitude + latDelta;
        if (minLat <= -90.0 || maxLat >= 90.0) {
            // The box reaches over a pole; longitude no longer bounds anything useful.
            lonDelta = 180.0;
        }

        return new GeoBounds(
                Math.max(-90.0, minLat),
                Math.min(90.0, maxLat),
                Math.max(-180.0, longitude - lonDelta),
                Math.min(180.0, longitude + lonDelta));
    }

    /** Whether a point lies inside this box, edges included. */
    public boolean contains(double latitude, double longitude) {
        return latitude >= minLatitude && latitude <= maxLatitude
                && longitude >= minLongitude && longitude <= maxLongitude;
    }
}
