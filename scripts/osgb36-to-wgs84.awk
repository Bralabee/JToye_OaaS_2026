# OSGB36 (EPSG:27700) easting/northing  ->  WGS84 (EPSG:4326) latitude/longitude
#
# Reads raw OS Code-Point Open CSV on stdin and emits `postcode,lat,lon` with NO header row.
# Driven only by scripts/regen-postcode-centroids.sh — see core-java/src/main/resources/geo/SOURCE.md
# for provenance, licence and limitations.
#
# Input columns (Doc/Code-Point_Open_Column_Headers.csv):
#   $1 PC postcode (quoted, e.g. "AB10 1AB")   $2 PQ positional quality
#   $3 EA easting                              $4 NO northing
#
# TWO FILTERS, BOTH REQUIRED. 879 rows in the 2026-08 release carry PQ=90 with eastings/northings
# 0,0 — Null Island. The sentinel lives in a DIFFERENT column from the coordinates, so a
# single-column filter misses it, and a row that survives becomes the nearest shop to every
# customer on the platform. Keep only  PQ != 90 AND easting != 0.
#
# The postcode key is emitted SPACE-STRIPPED and UPPERCASED. The source field is NOT fixed-width —
# measured across the release, lengths are 6 / 7 / 8 characters — so a padding-based parser mis-keys.
#
# Method: inverse Transverse Mercator on Airy 1830, then a 7-parameter Helmert to WGS84 at H=0.
# The Helmert parameters are the negation of OS's documented WGS84->OSGB36 set.

BEGIN {
    FS = ","
    PI  = 3.14159265358979323846
    D2R = PI / 180.0
    S2R = PI / (180.0 * 3600.0)          # arc-seconds -> radians

    # --- Airy 1830 / National Grid ---
    a1 = 6377563.396; b1 = 6356256.909
    F0 = 0.9996012717
    lat0 = 49.0 * D2R; lon0 = -2.0 * D2R
    N0 = -100000.0; E0 = 400000.0
    e2_1 = (a1 * a1 - b1 * b1) / (a1 * a1)
    n = (a1 - b1) / (a1 + b1); n2 = n * n; n3 = n2 * n

    # --- WGS84 ---
    a2 = 6378137.000; b2 = 6356752.3142
    e2_2 = (a2 * a2 - b2 * b2) / (a2 * a2)

    # --- Helmert OSGB36 -> WGS84 (negation of the documented WGS84 -> OSGB36 set) ---
    tx =  446.448; ty = -125.157; tz = 542.060
    sc = -20.4894e-6
    rx = 0.1502 * S2R; ry = 0.2470 * S2R; rz = 0.8421 * S2R
}

{
    pq = $2 + 0
    E  = $3 + 0
    N  = $4 + 0
    if (pq == 90 || E == 0) next          # Null Island: both columns, never one

    pc = $1
    gsub(/"/, "", pc)
    gsub(/[ \t]/, "", pc)
    pc = toupper(pc)
    if (pc == "") next

    # ---- inverse Transverse Mercator -> OSGB36 lat/lon ----
    lat = lat0 + (N - N0) / (a1 * F0)
    do {
        dlat = lat - lat0; slat = lat + lat0
        M = b1 * F0 * ( \
              (1 + n + 1.25 * n2 + 1.25 * n3) * dlat \
            - (3 * n + 3 * n2 + 2.625 * n3) * sin(dlat) * cos(slat) \
            + (1.875 * n2 + 1.875 * n3) * sin(2 * dlat) * cos(2 * slat) \
            - (35.0 / 24.0) * n3 * sin(3 * dlat) * cos(3 * slat) )
        d = N - N0 - M
        lat = lat + d / (a1 * F0)
    } while (d > 0.00001 || d < -0.00001)

    sl = sin(lat); cl = cos(lat)
    t = sl / cl; sec = 1.0 / cl
    w = 1 - e2_1 * sl * sl
    nu  = a1 * F0 / sqrt(w)
    rho = a1 * F0 * (1 - e2_1) / (w * sqrt(w))
    eta2 = nu / rho - 1

    t2 = t * t; t4 = t2 * t2; t6 = t4 * t2
    nu3 = nu * nu * nu; nu5 = nu3 * nu * nu; nu7 = nu5 * nu * nu

    VII  = t / (2 * rho * nu)
    VIII = t / (24 * rho * nu3) * (5 + 3 * t2 + eta2 - 9 * t2 * eta2)
    IX   = t / (720 * rho * nu5) * (61 + 90 * t2 + 45 * t4)
    X    = sec / nu
    XI   = sec / (6 * nu3) * (nu / rho + 2 * t2)
    XII  = sec / (120 * nu5) * (5 + 28 * t2 + 24 * t4)
    XIIA = sec / (5040 * nu7) * (61 + 662 * t2 + 1320 * t4 + 720 * t6)

    dE = E - E0
    dE2 = dE * dE; dE3 = dE2 * dE; dE4 = dE3 * dE
    dE5 = dE4 * dE; dE6 = dE5 * dE; dE7 = dE6 * dE

    latg = lat - VII * dE2 + VIII * dE4 - IX * dE6
    lng  = lon0 + X * dE - XI * dE3 + XII * dE5 - XIIA * dE7

    # ---- geodetic -> cartesian on Airy 1830 (H = 0) ----
    sl = sin(latg); cl = cos(latg)
    v = a1 / sqrt(1 - e2_1 * sl * sl)
    x1 = v * cl * cos(lng)
    y1 = v * cl * sin(lng)
    z1 = (1 - e2_1) * v * sl

    # ---- 7-parameter Helmert ----
    x2 = tx + x1 * (1 + sc) - y1 * rz + z1 * ry
    y2 = ty + x1 * rz + y1 * (1 + sc) - z1 * rx
    z2 = tz - x1 * ry + y1 * rx + z1 * (1 + sc)

    # ---- cartesian -> geodetic on WGS84 ----
    p = sqrt(x2 * x2 + y2 * y2)
    lat2 = atan2(z2, p * (1 - e2_2))
    for (i = 0; i < 8; i++) {
        s2 = sin(lat2)
        v2 = a2 / sqrt(1 - e2_2 * s2 * s2)
        lat2 = atan2(z2 + e2_2 * v2 * s2, p)
    }
    lon2 = atan2(y2, x2)

    printf "%s,%.6f,%.6f\n", pc, lat2 / D2R, lon2 / D2R
}
