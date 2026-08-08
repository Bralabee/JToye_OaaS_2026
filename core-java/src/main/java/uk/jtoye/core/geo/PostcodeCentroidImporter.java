package uk.jtoye.core.geo;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Loads the committed OS Code-Point Open centroids into {@code postcode_centroid} at startup.
 *
 * <p><strong>Enabled by default.</strong> {@code jtoye.geo.postcode-import.enabled} defaults to
 * {@code true} ({@code matchIfMissing = true} below mirrors the yml default, so an absent key
 * cannot silently disable this). That default is the load-bearing decision: with the table
 * empty, every downstream locality feature degrades to a correct-looking empty answer while
 * every automated check stays green. The test profile sets it {@code false} and loads a 7-row
 * fixture instead — that divergence is deliberate, because dev must be populated and tests must
 * be fast and offline.
 *
 * <p><strong>Why COPY and a staging table.</strong> 1,748,230 rows through JPA would be minutes
 * of round-trips. {@code COPY … FROM STDIN} streams them in one statement. Loading into a
 * staging table first means the live table is never half-populated: if anything about the load
 * is wrong, the promotion never happens and the previous state stands.
 *
 * <p><strong>Idempotent.</strong> If the table already holds exactly the manifest's row count,
 * this is a no-op — so a normal restart costs one {@code COUNT(*)}.
 *
 * <p><strong>It fails loudly, and refuses to serve a partial table.</strong> Two conditions
 * abort startup rather than degrade:
 * <ul>
 *   <li><strong>Any {@code (0,0)} row.</strong> Null Island is the specific hazard: such a shop
 *       is nearer the origin than any real one, so under a distance sort it becomes the nearest
 *       kitchen to <em>every</em> customer on the platform. 879 rows in the 2026-08 release
 *       carry the upstream {@code positional_quality_indicator = 90} sentinel, and the sentinel
 *       lives in a <em>different column</em> from the coordinates, so a single-column filter
 *       misses it. {@code scripts/regen-postcode-centroids.sh} already drops them — this is the
 *       independent second check, because the artefact is a committed binary that a future
 *       regeneration could get wrong, and by the time it reaches this class the sentinel column
 *       is gone and {@code (0,0)} is all that is left to detect.</li>
 *   <li><strong>A row-count mismatch against the manifest.</strong> A short load is not a
 *       degraded service, it is a silently wrong one: the missing postcodes simply never
 *       geocode, and nothing distinguishes that from a vendor genuinely being in Northern
 *       Ireland.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(
        prefix = "jtoye.geo.postcode-import",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PostcodeCentroidImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PostcodeCentroidImporter.class);

    /**
     * Matches the generated manifest's row line, which is a bold markdown table cell:
     * {@code | **Rows after filter** | **1748230** (dropped 879) |}.
     *
     * <p>The {@code **} emphasis markers around BOTH the label and the number are the whole
     * difficulty. A first version of this pattern ignored them and matched neither the real
     * {@code SOURCE.md} nor the fixture — it would have aborted startup on every environment,
     * which the integration test caught before it ever ran anywhere.
     */
    private static final Pattern MANIFEST_ROWS = Pattern.compile(
            "Rows after filter\\*{0,2}\\s*\\|\\s*\\*{0,2}([0-9]{1,12})");

    private static final String STAGING_TABLE = "postcode_centroid_staging";

    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final String resourceLocation;
    private final String manifestLocation;

    public PostcodeCentroidImporter(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            @Value("${jtoye.geo.postcode-import.resource:classpath:geo/postcode-centroids.csv.gz}")
            String resourceLocation,
            @Value("${jtoye.geo.postcode-import.manifest:classpath:geo/SOURCE.md}")
            String manifestLocation) {
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.resourceLocation = resourceLocation;
        this.manifestLocation = manifestLocation;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        importIfNeeded();
    }

    /**
     * Load the dataset unless the table already matches the manifest.
     *
     * @return the number of rows in {@code postcode_centroid} afterwards
     * @throws IllegalStateException if the artefact contains a Null Island row, if the loaded
     *                               count does not match the manifest, or if the manifest count
     *                               cannot be read — never a partial table, never a warning
     */
    public long importIfNeeded() throws Exception {
        long expected = readExpectedRowCount();

        try (Connection connection = dataSource.getConnection()) {
            // One connection for the whole operation: the staging table is TEMP, and a temp
            // table is scoped to the connection that created it. Taking a fresh connection from
            // the pool per statement would silently create it and then not find it.
            connection.setAutoCommit(false);

            long existing = countRows(connection, "postcode_centroid");
            if (existing == expected) {
                log.info("postcode_centroid already holds {} rows, matching {} — skipping import",
                        existing, manifestLocation);
                connection.rollback();
                return existing;
            }
            if (existing > 0) {
                log.warn("postcode_centroid holds {} rows but the manifest expects {} — reloading",
                        existing, expected);
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE " + STAGING_TABLE + " ("
                        + "postcode TEXT, latitude DOUBLE PRECISION, longitude DOUBLE PRECISION"
                        + ") ON COMMIT DROP");
            }

            long copied = copyInto(connection);

            // The Null Island guard, run BEFORE anything is promoted.
            long nullIsland = countRows(connection,
                    STAGING_TABLE + " WHERE latitude = 0 AND longitude = 0");
            if (nullIsland > 0) {
                connection.rollback();
                throw new IllegalStateException(
                        "Refusing to import: " + nullIsland + " row(s) in " + resourceLocation
                        + " sit at (0,0). A shop at Null Island ranks as the nearest kitchen to "
                        + "every customer on the platform. Regenerate the dataset with "
                        + "scripts/regen-postcode-centroids.sh, which filters the upstream "
                        + "positional_quality_indicator = 90 sentinel.");
            }

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("TRUNCATE postcode_centroid");
                statement.executeUpdate(
                        "INSERT INTO postcode_centroid (postcode, latitude, longitude) "
                        + "SELECT postcode, latitude, longitude FROM " + STAGING_TABLE);
            }

            long loaded = countRows(connection, "postcode_centroid");
            if (loaded != expected) {
                connection.rollback();
                throw new IllegalStateException(
                        "Refusing to serve a partial postcode table: loaded " + loaded
                        + " row(s) from " + resourceLocation + " but " + manifestLocation
                        + " records " + expected + ". A short load is not a degraded service — "
                        + "the missing postcodes simply never geocode, and that is "
                        + "indistinguishable from a vendor legitimately being outside GB.");
            }

            connection.commit();
            log.info("Imported {} postcode centroids from {} ({} rows copied)",
                    loaded, resourceLocation, copied);
            return loaded;
        }
    }

    /** Streams the (optionally gzipped) CSV straight into the staging table via COPY. */
    private long copyInto(Connection connection) throws Exception {
        CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
        try (InputStream raw = resourceLoader.getResource(resourceLocation).getInputStream();
             InputStream decoded = resourceLocation.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             Reader reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
            // The artefact has NO header row, deliberately — see SOURCE.md. A header would make
            // the upstream Null-Island assertion `("lat"+0)==0` true and fail a correct dataset.
            return copyManager.copyIn(
                    "COPY " + STAGING_TABLE + " (postcode, latitude, longitude) "
                    + "FROM STDIN WITH (FORMAT csv)", reader);
        }
    }

    private long countRows(Connection connection, String fromClause) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + fromClause)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Reads the expected row count out of the generated provenance file rather than holding a
     * copy of the number. A copy is a second source of truth whose failure mode is this check
     * confidently passing a dataset it has never actually counted.
     */
    private long readExpectedRowCount() throws Exception {
        String manifest;
        try (InputStream in = resourceLoader.getResource(manifestLocation).getInputStream()) {
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher matcher = MANIFEST_ROWS.matcher(manifest);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Could not read the expected row count from " + manifestLocation
                    + " — refusing to import against an unread manifest. \"Could not check\" is "
                    + "never \"clean\".");
        }
        return Long.parseLong(matcher.group(1));
    }
}
