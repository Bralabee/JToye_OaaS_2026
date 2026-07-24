package uk.jtoye.core.media;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IMG-03 (vendor-visible half) + T-24-19/T-24-20 — the vendor review/rejection queue
 * over real Postgres: the selection is exactly {@code status='FAILED' OR (status='ACTIVE'
 * AND flagged=true)} (clean-ACTIVE + PENDING excluded), it is tenant-isolated by RLS
 * (another tenant's FAILED/flagged rows are invisible), Keep dismisses the flag (stays
 * ACTIVE, drops out of the queue), and a cross-tenant {@code assetId} on Keep is a 404
 * (no cross-tenant oracle).
 *
 * <p>Like {@code MediaAssetRlsPolicyIntegrationTest}, the Testcontainers bootstrap role is
 * a Postgres SUPERUSER (which bypasses FORCE RLS), so each RLS-sensitive assertion runs
 * under a dedicated {@code rls_test_role} (NOSUPERUSER NOBYPASSRLS) via
 * {@code SET LOCAL ROLE}. The tenant GUC is driven by {@link TenantContext} through
 * {@code TenantSetLocalAspect} (the same path production code uses — the aspect re-applies
 * {@code set_config('app.current_tenant_id', ...)} before every repository/JDBC op), so the
 * queue query and the service call fire under the caller's tenant GUC.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class MediaReviewQueueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final String RLS_TEST_ROLE = "rls_test_role";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaAssetService mediaAssetService;

    private UUID tenantA;
    private UUID tenantB;
    private UUID failedId;
    private UUID flaggedActiveId;
    private UUID cleanActiveId;
    private UUID pendingId;
    private UUID tenantBFlaggedId;
    private int seq;

    @BeforeEach
    void seed() {
        jdbc.execute("DO $$ BEGIN " +
                "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + RLS_TEST_ROLE + "') THEN " +
                "    CREATE ROLE " + RLS_TEST_ROLE + " NOSUPERUSER NOBYPASSRLS LOGIN; " +
                "    GRANT ALL ON ALL TABLES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "    GRANT USAGE ON SCHEMA public TO " + RLS_TEST_ROLE + "; " +
                "  END IF; " +
                "END $$");

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        seq = 0;

        // Seed as superuser (FORCE RLS bypassed) with explicit tenant_id per row.
        TenantContext.clear();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantA, "test-" + tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantB, "test-" + tenantB);

        failedId         = insertAsset(tenantA, "FAILED", false, "unsupported image format");
        flaggedActiveId  = insertAsset(tenantA, "ACTIVE", true, null);
        cleanActiveId    = insertAsset(tenantA, "ACTIVE", false, null);
        pendingId        = insertAsset(tenantA, "PENDING", false, null);
        tenantBFlaggedId = insertAsset(tenantB, "ACTIVE", true, null);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Insert a media_asset row directly (no product needed — product_id is a nullable intent column). */
    private UUID insertAsset(UUID tenant, String status, boolean flagged, String failureReason) {
        UUID id = UUID.randomUUID();
        String sha = String.format("%064d", seq++);   // unique 64-char sha per row (uq_media_asset_tenant_sha)
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, flagged, failure_reason) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', ?, ?, ?)",
                id, tenant, tenant + "/media/" + id + ".webp", sha, status, flagged, failureReason);
        return id;
    }

    /** Downgrade to the NOSUPERUSER role so RLS fires, under {@code tenant}'s GUC (via the aspect). */
    private void actAs(UUID tenant) {
        TenantContext.set(tenant);
        jdbc.execute("SET LOCAL ROLE " + RLS_TEST_ROLE);
    }

    @Test
    void listsFailedAndFlaggedOnly() {
        actAs(tenantA);

        List<MediaAssetDto> queue = mediaAssetService.reviewQueue();

        assertThat(queue).extracting(MediaAssetDto::assetId)
                .as("FAILED + flagged-ACTIVE only; clean-ACTIVE + PENDING excluded; tenant B invisible (RLS)")
                .containsExactlyInAnyOrder(failedId, flaggedActiveId)
                .doesNotContain(cleanActiveId, pendingId, tenantBFlaggedId);

        assertThat(queue).filteredOn(d -> d.assetId().equals(failedId)).singleElement()
                .satisfies(d -> {
                    assertThat(d.status()).isEqualTo(MediaAssetStatus.FAILED);
                    assertThat(d.failureReason()).as("FAILED carries the vendor-visible reason")
                            .isEqualTo("unsupported image format");
                });
        assertThat(queue).filteredOn(d -> d.assetId().equals(flaggedActiveId)).singleElement()
                .satisfies(d -> {
                    assertThat(d.status()).isEqualTo(MediaAssetStatus.ACTIVE);
                    assertThat(d.flagged()).as("the flagged entry is ACTIVE + flagged").isTrue();
                    assertThat(d.url()).as("an ACTIVE derivative resolves a servable URL").isNotNull();
                });
    }

    @Test
    void keepDismissesFlag() {
        actAs(tenantA);

        MediaAssetDto kept = mediaAssetService.dismissFlag(flaggedActiveId);
        assertThat(kept.flagged()).as("Keep clears the flag").isFalse();
        assertThat(kept.status()).as("Keep leaves the asset ACTIVE").isEqualTo(MediaAssetStatus.ACTIVE);

        assertThat(mediaAssetService.reviewQueue()).extracting(MediaAssetDto::assetId)
                .as("the kept asset drops out of the queue; the FAILED entry remains")
                .doesNotContain(flaggedActiveId)
                .contains(failedId);
    }

    @Test
    void keepOnCrossTenantAssetIs404() {
        actAs(tenantA);

        assertThatThrownBy(() -> mediaAssetService.dismissFlag(tenantBFlaggedId))
                .as("a foreign tenant's asset is invisible under RLS -> 404 (no cross-tenant oracle)")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
