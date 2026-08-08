package uk.jtoye.core.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeoBounds} exists so the distance query (33-06) can prefilter with plain
 * float8 comparisons — which are LEAKPROOF and therefore usable under RLS — instead
 * of calling a distance function on every row.
 *
 * <p>The contract that matters is <strong>containment</strong>, not tightness. A box
 * that is slightly too large costs a few extra haversine evaluations; a box that is
 * too small silently drops shops that are genuinely inside the radius, and the symptom
 * is "the nearest kitchen never appears" with nothing in any log. So every assertion
 * here is written in the direction of "the circle must fit", and the deliberately
 * excluded point sits at twice the radius rather than a hair outside it.
 */
class GeoBoundsTest {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /** Move due north by {@code km}, in degrees of latitude. */
    private static double latDegreesNorth(double km) {
        return Math.toDegrees(km / EARTH_RADIUS_KM);
    }

    @Test
    @DisplayName("a point at exactly the radius due north is INSIDE the box (the circle fits)")
    void containsPointAtExactlyRadiusDueNorth() {
        double lat = 51.472435, lon = -0.070047, radiusKm = 5;
        GeoBounds box = GeoBounds.boxAround(lat, lon, radiusKm);

        double northLat = lat + latDegreesNorth(radiusKm);

        assertThat(northLat).isLessThanOrEqualTo(box.maxLatitude());
        assertThat(box.contains(northLat, lon)).isTrue();
    }

    @Test
    @DisplayName("a point at twice the radius due north is OUTSIDE the box")
    void excludesPointAtTwiceRadiusDueNorth() {
        double lat = 51.472435, lon = -0.070047, radiusKm = 5;
        GeoBounds box = GeoBounds.boxAround(lat, lon, radiusKm);

        double farLat = lat + latDegreesNorth(2 * radiusKm);

        assertThat(farLat).isGreaterThan(box.maxLatitude());
        assertThat(box.contains(farLat, lon)).isFalse();
    }

    @Test
    @DisplayName("a point at exactly the radius due EAST is inside — longitude scales with cos(lat)")
    void containsPointAtExactlyRadiusDueEast() {
        double lat = 51.472435, lon = -0.070047, radiusKm = 5;
        GeoBounds box = GeoBounds.boxAround(lat, lon, radiusKm);

        // At 51.47N a degree of longitude is ~0.62 of a degree of latitude. A box that
        // used the latitude half-extent for longitude too would be too NARROW here, and
        // this is the assertion that catches it.
        double eastLon = lon + Math.toDegrees(radiusKm / (EARTH_RADIUS_KM * Math.cos(Math.toRadians(lat))));

        assertThat(eastLon).isLessThanOrEqualTo(box.maxLongitude());
        assertThat(box.contains(lat, eastLon)).isTrue();
    }

    @Test
    @DisplayName("near the pole the longitude half-extent clamps to 180 instead of dividing by ~0")
    void polarLongitudeClampsRatherThanBlowingUp() {
        GeoBounds box = GeoBounds.boxAround(89.9999, 0, 5);

        assertThat(box.minLongitude()).isFinite();
        assertThat(box.maxLongitude()).isFinite();
        // cos(89.9999 deg) is ~1.7e-6, so an unguarded divisor produces a half-extent of
        // ~26,000 degrees. Clamped, the box spans the whole longitude range — which is
        // the CORRECT answer near a pole, where every meridian is close by.
        assertThat(box.minLongitude()).isEqualTo(-180.0);
        assertThat(box.maxLongitude()).isEqualTo(180.0);
    }

    @Test
    @DisplayName("latitude half-extent clamps at the poles rather than exceeding +/-90")
    void latitudeClampsAtPoles() {
        GeoBounds box = GeoBounds.boxAround(89.99, 0, 500);

        assertThat(box.maxLatitude()).isLessThanOrEqualTo(90.0);
        assertThat(box.minLatitude()).isGreaterThanOrEqualTo(-90.0);
    }

    @Test
    @DisplayName("a zero radius still yields a valid, degenerate box containing its own centre")
    void zeroRadiusContainsCentre() {
        GeoBounds box = GeoBounds.boxAround(51.472435, -0.070047, 0);

        assertThat(box.contains(51.472435, -0.070047)).isTrue();
    }

    @Test
    @DisplayName("a negative radius is rejected rather than silently producing an inverted box")
    void negativeRadiusRejected() {
        // An inverted box (min > max) matches NOTHING, so a caller passing a bad radius
        // would get "no shops near you" instead of an error.
        assertThat(
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> GeoBounds.boxAround(51.47, -0.07, -1)))
                .hasMessageContaining("radius");
    }
}
