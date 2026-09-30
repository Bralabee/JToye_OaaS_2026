package uk.jtoye.core.gdpr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.BlobObjectStore;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.AzuriteTestSupport;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issues #764 and #771, proven against the real posture: Postgres 15 with every migration applied, the
 * connection role downgraded to NOSUPERUSER so FORCE row-level security is genuinely enforced,
 * and a real Azurite with {@link StorageService} unstubbed.
 *
 * <p>Three defects, one arm each:
 * <ol>
 *   <li><b>A</b> — {@code reviews} carries FORCE RLS and had no UPDATE policy, so the anonymising
 *       UPDATE matched zero rows for the application role and the whole erasure rolled back — after
 *       the review photos had already been deleted from Blob.</li>
 *   <li><b>B</b> — {@code reviews_tenant_read} exposes PUBLISHED shops' reviews across tenants, so an
 *       email-only lookup pulled another tenant's review into this tenant's erasure.</li>
 *   <li><b>C</b> — photos were deleted inside the transaction, so a rollback could not undo them.</li>
 *   <li><b>D, E</b> — the fix for C writes the photo count onto {@code erasure_records} AFTER commit, so
 *       V67 had to open an UPDATE path on the Article-17 proof row. A row policy alone opened EVERY
 *       column of every record whose count was still 0; D and E prove the only write the database
 *       accepts is the one-time photo count, and E proves it for a role that bypasses row-level
 *       security too.</li>
 *   <li><b>F</b> (#771) — a review's {@code photo_urls} were never validated, and erasure deleted
 *       every own-tenant object they named. A customer could therefore name the shop's catalogue
 *       images (product image and gallery, shop logo and banner, a media_asset derivative and its
 *       thumbnail) or ANOTHER customer's review photo, file an erasure, and destroy them. Arm F
 *       proves erasure deletes only the photo under the review's OWN order path. Its review is
 *       seeded by SQL on purpose: rows planted before creation-time validation existed bypass that
 *       validation, so the erasure guard must hold on its own.</li>
 *   <li><b>G</b> (#771) — a photo under the review's own order path that a catalogue row references
 *       (the vendor pointed a product at it) is retained: deleting it would break a live catalogue
 *       image, which is the #771 harm.</li>
 * </ol>
 *
 * <p>The class is deliberately NOT {@code @Transactional}: the post-commit photo step only fires
 * when a transaction really commits. Every seed and every read therefore runs in its own
 * transaction with the tenant GUC pinned ON THE SAME CONNECTION through {@code Session.doWork}
 * (the {@code DsarFanoutIntegrationTest} recipe — a {@code JdbcTemplate} inside a JPA transaction
 * may take an autocommit connection, which reverts a transaction-local {@code set_config}).
 * Fresh tenants, emails and slugs per test, so data left by one method cannot satisfy another.
 *
 * <p>Every arm carries a precondition that proves it CAN fail: the role is NOSUPERUSER, the rows
 * under test are visible to the pinned session, and each photo answers 200 before the erasure.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class GdprErasureReviewRlsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        AzuriteTestSupport.registerAzuriteProperties(registry, AZURITE);
    }

    private static final String DOWNGRADED_APP_ROLE = "test";
    private static final AtomicBoolean DOWNGRADED = new AtomicBoolean(false);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String EXTERNAL_URL = "https://example.invalid/not-ours.jpg";
    /**
     * A role the RLS policies do not apply to (NOSUPERUSER, BYPASSRLS). Arm E uses it to prove the
     * erasure-record guard is role-agnostic: whatever a role's RLS posture, the evidence row accepts
     * only the one-time photo count. Created while the bootstrap role is still a superuser, because
     * only a superuser may grant BYPASSRLS.
     */
    private static final String BYPASS_ROLE = "erasure_764_bypass";
    private static final String BYPASS_PW = "bypass-" + UUID.randomUUID();
    /** A token only the V67 write-once guard's message carries — not an RLS or constraint refusal. */
    private static final String GUARD_TOKEN = "erasure_records is write-once";

    @Autowired private GdprService gdprService;
    @Autowired private StorageService storageService;
    @Autowired private BlobObjectStore blobObjectStore;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // The test profile keeps the boot probe off, so the fixture creates the containers.
        AzuriteTestSupport.createContainers(blobObjectStore, ContainerAccess.BLOB);
        // Only a superuser may run ALTER ROLE, so this must happen exactly once per container.
        if (DOWNGRADED.compareAndSet(false, true)) {
            assertThat(postgres.getUsername())
                    .as("the role this test downgrades must be the one it names")
                    .isEqualTo(DOWNGRADED_APP_ROLE);
            jdbc.execute("CREATE ROLE " + BYPASS_ROLE + " NOSUPERUSER BYPASSRLS LOGIN PASSWORD '" + BYPASS_PW + "'");
            jdbc.execute("GRANT SELECT, UPDATE ON erasure_records TO " + BYPASS_ROLE);
            jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER");
        }
        assertThat(jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = ?", Boolean.class, DOWNGRADED_APP_ROLE))
                .as("PRECONDITION: every assertion below is meaningless if the role still bypasses "
                        + "row-level security")
                .isFalse();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- Arm A ---------------------------------------------------------------------------------

    @Test
    void erasureAnonymisesReviewAndDeletesOwnTenantPhotosUnderRls() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        String email = "reviewer-a-" + UUID.randomUUID() + "@example.com";
        UUID shopA = seedShop(a, false);
        UUID customerId = seedCustomer(a, email);
        UUID orderA = seedOrder(a, shopA, email);

        byte[] a1Bytes = webpBytes();
        byte[] a2Bytes = webpBytes();
        byte[] bxBytes = webpBytes();
        String a1 = putPhoto(a, orderA, a1Bytes);
        String a2 = putPhoto(a, orderA, a2Bytes);
        // Same order, FOREIGN tenant: the most adversarial shape for a key that names this review.
        String bx = putPhoto(b, orderA, bxBytes);
        UUID reviewId = seedReview(a, shopA, orderA, email, "Reviewer A", "lovely jollof",
                a1, a2, EXTERNAL_URL, bx);

        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE id = ?", reviewId))
                .as("PRECONDITION: tenant A's session sees the review it is about to erase")
                .isEqualTo(1L);
        assertThat(anonymousGet(a1).statusCode()).as("PRECONDITION: A1 is served").isEqualTo(200);
        assertThat(anonymousGet(a2).statusCode()).as("PRECONDITION: A2 is served").isEqualTo(200);
        assertThat(anonymousGet(bx).statusCode()).as("PRECONDITION: Bx is served").isEqualTo(200);

        // var, not a named type: the same source compiles against the unfixed tree (the RED run) and
        // the fixed one. @AfterEach clears the context if the call throws.
        TenantContext.set(a);
        var outcome = gdprService.eraseCustomerData(customerId);
        TenantContext.clear();

        ReviewRow row = readReview(a, reviewId);
        assertThat(row.customerName()).isEqualTo("[REDACTED]");
        assertThat(row.customerEmail()).isEqualTo("redacted@erased.invalid");
        assertThat(row.comment()).isNull();
        assertThat(row.photoUrls()).isNull();

        assertThat(anonymousGet(a1).statusCode()).as("tenant A's own photo A1 after erasure").isEqualTo(404);
        assertThat(anonymousGet(a2).statusCode()).as("tenant A's own photo A2 after erasure").isEqualTo(404);
        HttpResponse<byte[]> bxAfter = anonymousGet(bx);
        assertThat(bxAfter.statusCode()).as("tenant B's photo Bx on A's review (D-09)").isEqualTo(200);
        assertThat(bxAfter.body()).isEqualTo(bxBytes);

        assertThat(outcome.photosDeleted())
                .as("WR-02: only the two objects the store removed are counted").isEqualTo(2);
        assertThat(outcome.reviewsAnonymised()).isEqualTo(1);

        assertThat(countUnder(a, "SELECT COUNT(*) FROM erasure_records WHERE subject_customer_id = ?", customerId))
                .as("exactly one Article-17 evidence row").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT photos_deleted FROM erasure_records WHERE subject_customer_id = ?",
                customerId))
                .as("the DURABLE photo count, read back in a fresh tenant-A transaction")
                .isEqualTo(2L);
    }

    // ---- Arm B ---------------------------------------------------------------------------------

    @Test
    void erasureLeavesAnotherTenantsPublishedReviewUntouched() throws Exception {
        UUID a = seedTenant();
        UUID b = seedTenant();
        String email = "shared-reviewer-" + UUID.randomUUID() + "@example.com";

        UUID shopA = seedShop(a, false);
        UUID customerA = seedCustomer(a, email);
        UUID orderA = seedOrder(a, shopA, email);
        String aPhoto = putPhoto(a, orderA, webpBytes());
        UUID reviewA = seedReview(a, shopA, orderA, email, "Reviewer in A", "comment in A", aPhoto);

        UUID shopB = seedShop(b, true);
        UUID orderB = seedOrder(b, shopB, email);
        byte[] bPhotoBytes = webpBytes();
        String bPhoto = putPhoto(b, orderB, bPhotoBytes);
        UUID reviewB = seedReview(b, shopB, orderB, email, "Reviewer in B", "comment in B", bPhoto);
        ReviewRow bBefore = readReview(b, reviewB);

        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE customer_email = ?", email))
                .as("PRECONDITION: the SELECT policy exposes tenant B's PUBLISHED review to tenant A's "
                        + "session, so an email-only lookup WOULD pick it up — this arm can discriminate")
                .isEqualTo(2L);
        assertThat(anonymousGet(bPhoto).statusCode()).as("PRECONDITION: B's photo is served").isEqualTo(200);

        TenantContext.set(a);
        var outcome = gdprService.eraseCustomerData(customerA);
        TenantContext.clear();

        assertThat(outcome.reviewsAnonymised())
                .as("only the erasing tenant's review is counted").isEqualTo(1);

        ReviewRow bAfter = readReview(b, reviewB);
        assertThat(bAfter.customerName()).isEqualTo("Reviewer in B").isEqualTo(bBefore.customerName());
        assertThat(bAfter.customerEmail()).isEqualTo(email);
        assertThat(bAfter.comment()).isEqualTo("comment in B");
        assertThat(bAfter.photoUrls()).containsExactly(bPhoto);
        HttpResponse<byte[]> bPhotoAfter = anonymousGet(bPhoto);
        assertThat(bPhotoAfter.statusCode()).as("tenant B's photo after tenant A's erasure").isEqualTo(200);
        assertThat(bPhotoAfter.body()).isEqualTo(bPhotoBytes);

        ReviewRow aAfter = readReview(a, reviewA);
        assertThat(aAfter.customerName()).isEqualTo("[REDACTED]");
        assertThat(aAfter.customerEmail()).isEqualTo("redacted@erased.invalid");
        assertThat(aAfter.photoUrls()).isNull();
    }

    // ---- Arm C ---------------------------------------------------------------------------------

    @Test
    void rolledBackErasureDeletesNoPhoto() throws Exception {
        UUID a = seedTenant();
        String email = "rollback-" + UUID.randomUUID() + "@example.com";
        UUID shopA = seedShop(a, false);
        UUID customerId = seedCustomer(a, email);
        UUID orderA = seedOrder(a, shopA, email);
        byte[] photoBytes = webpBytes();
        String photo = putPhoto(a, orderA, photoBytes);
        UUID reviewId = seedReview(a, shopA, orderA, email, "Rollback Reviewer", "keep me", photo);
        assertThat(anonymousGet(photo).statusCode()).as("PRECONDITION: the photo is served").isEqualTo(200);

        AtomicBoolean reachedEnd = new AtomicBoolean(false);
        AtomicReference<RuntimeException> escaped = new AtomicReference<>();
        TenantContext.set(a);
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                // Deliberately NOT reading photosDeleted() here: inside an enclosing transaction the
                // photo step has not run yet.
                gdprService.eraseCustomerData(customerId);
                reachedEnd.set(true);
                status.setRollbackOnly();
            });
        } catch (RuntimeException e) {
            escaped.set(e);
        } finally {
            TenantContext.clear();
        }

        HttpResponse<byte[]> after = anonymousGet(photo);
        assertThat(after.statusCode())
                .as("the photo after an erasure whose transaction ROLLED BACK (escaped: %s)", escaped.get())
                .isEqualTo(200);
        assertThat(after.body()).isEqualTo(photoBytes);

        assertThat(reachedEnd.get())
                .as("PRECONDITION: the erasure body ran to completion inside the rolled-back transaction "
                        + "(escaped: %s)", escaped.get())
                .isTrue();

        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE id = ?", reviewId))
                .as("PRECONDITION: the review is visible to the verification read").isEqualTo(1L);
        ReviewRow row = readReview(a, reviewId);
        assertThat(row.customerName()).isEqualTo("Rollback Reviewer");
        assertThat(row.customerEmail()).isEqualTo(email);
        assertThat(row.comment()).isEqualTo("keep me");
        assertThat(row.photoUrls()).containsExactly(photo);

        assertThat(countUnder(a, "SELECT COUNT(*) FROM erasure_records WHERE subject_customer_id = ?", customerId))
                .as("a rolled-back erasure leaves no evidence row").isZero();
    }

    // ---- Arm D ---------------------------------------------------------------------------------

    /**
     * The application role, tenant pinned, against a record whose photo count is still 0 — exactly the
     * rows V67's {@code erasure_records_photo_count_update} policy makes UPDATE targets. A policy is a
     * ROW filter, so on its own it lets every column of such a row be rewritten; the only write the
     * evidence row may accept is photos_deleted going from 0 to a count, with nothing else changing.
     */
    @Test
    void erasureRecordAcceptsOnlyTheOneTimePhotoCountFromTheAppRole() {
        UUID a = seedTenant();
        UUID b = seedTenant();
        UUID recordId = seedErasureRecord(a, 0);

        assertThat(countUnder(a, "SELECT COUNT(*) FROM erasure_records WHERE id = ? AND photos_deleted = 0",
                recordId))
                .as("PRECONDITION: the record is visible to tenant A with a zero count, so V67's UPDATE policy "
                        + "makes it a target — a refusal below cannot be RLS filtering the row away")
                .isEqualTo(1L);
        assertThat(countUnder(b, "SELECT COUNT(*) FROM erasure_records WHERE id = ?", recordId))
                .as("PRECONDITION: RLS is really enforced on this role — tenant B cannot see A's record")
                .isZero();
        String before = recordJson(a, recordId);

        Attempt forgedBy = attemptUnder(a, "UPDATE erasure_records SET erased_by = 'forged' WHERE id = ?", recordId);
        assertRefusedByGuard(forgedBy, "rewriting erased_by on a zero-count record");
        assertThat(recordJson(a, recordId)).as("the record after the refused erased_by rewrite").isEqualTo(before);

        Attempt smuggled = attemptUnder(a,
                "UPDATE erasure_records SET photos_deleted = 3, erased_by = 'forged' WHERE id = ?", recordId);
        assertRefusedByGuard(smuggled, "a forged erased_by riding along with a legitimate photo count");
        assertThat(recordJson(a, recordId)).as("the record after the refused smuggled rewrite").isEqualTo(before);

        Attempt backdated = attemptUnder(a,
                "UPDATE erasure_records SET erased_at = erased_at - interval '1 year' WHERE id = ?", recordId);
        assertRefusedByGuard(backdated, "back-dating erased_at");
        assertThat(recordJson(a, recordId)).as("the record after the refused back-dating").isEqualTo(before);

        Attempt negative = attemptUnder(a, "UPDATE erasure_records SET photos_deleted = -1 WHERE id = ?", recordId);
        assertRefusedByGuard(negative, "a negative photo count");
        assertThat(recordJson(a, recordId)).as("the record after the refused negative count").isEqualTo(before);

        // The legitimate path — the SQL ErasureRecordRepository.recordPhotosDeleted issues — still works,
        // and changes photos_deleted and nothing else.
        Attempt legitimate = attemptUnder(a,
                "UPDATE erasure_records SET photos_deleted = 3 WHERE id = ? AND tenant_id = ? AND photos_deleted = 0",
                recordId, a);
        assertThat((Throwable) legitimate.refusal()).as("the one-time photo count write").isNull();
        assertThat(legitimate.rows()).as("the one-time photo count write").isEqualTo(1);
        assertThat(countUnder(a, "SELECT photos_deleted FROM erasure_records WHERE id = ?", recordId)).isEqualTo(3L);
        assertThat(recordJsonWithout(a, recordId, "photos_deleted"))
                .as("every column except photos_deleted is unchanged by the legitimate write")
                .isEqualTo(jsonWithout(before, "photos_deleted"));
    }

    // ---- Arm E ---------------------------------------------------------------------------------

    /**
     * Once a count is recorded the record is final — photos_deleted included — for the app role AND for
     * a role row-level security does not apply to. The app-role half is ALREADY true under a bare V67
     * policy (its USING clause stops matching the row), so it cannot discriminate on its own and is kept
     * as the record; the bypass-role half is the discriminating one, and it is what makes the guard
     * role-agnostic rather than a property of one role's policy posture.
     */
    @Test
    void recordedErasureEvidenceIsFinalForEveryRole() throws Exception {
        UUID a = seedTenant();
        UUID recorded = seedErasureRecord(a, 2);
        UUID unrecorded = seedErasureRecord(a, 0);
        String recordedBefore = recordJson(a, recorded);
        String unrecordedBefore = recordJson(a, unrecorded);
        List<String> rewrites = List.of(
                "UPDATE erasure_records SET photos_deleted = 7 WHERE id = ?",
                "UPDATE erasure_records SET photos_deleted = 0 WHERE id = ?",
                "UPDATE erasure_records SET erased_by = 'forged' WHERE id = ?");

        for (String sql : rewrites) {
            Attempt attempt = attemptUnder(a, sql, recorded);
            assertThat(attempt.refusal() != null || attempt.rows() == 0)
                    .as("app role: %s changed %s row(s) of a recorded record", sql, attempt.rows())
                    .isTrue();
            assertThat(recordJson(a, recorded)).as("app role: the recorded record after %s", sql)
                    .isEqualTo(recordedBefore);
        }

        try (Connection bypass = DriverManager.getConnection(postgres.getJdbcUrl(), BYPASS_ROLE, BYPASS_PW)) {
            try (PreparedStatement ps = bypass.prepareStatement(
                    "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
                 ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).as("PRECONDITION: the bypass role is not a superuser").isFalse();
                assertThat(rs.getBoolean(2)).as("PRECONDITION: the bypass role bypasses RLS").isTrue();
            }
            try (PreparedStatement ps = bypass.prepareStatement(
                    "SELECT COUNT(*) FROM erasure_records WHERE id IN (?, ?)")) {
                bind(ps, recorded, unrecorded);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong(1))
                            .as("PRECONDITION: with NO tenant pinned the bypass role sees both records, so no "
                                    + "RLS policy stands between it and the UPDATEs below")
                            .isEqualTo(2L);
                }
            }

            for (String sql : rewrites) {
                assertRefusedByGuard(attemptOn(bypass, sql, recorded), "bypass role on a recorded record: " + sql);
                assertThat(recordJson(a, recorded)).as("the recorded record after the bypass role's %s", sql)
                        .isEqualTo(recordedBefore);
            }

            assertRefusedByGuard(attemptOn(bypass,
                            "UPDATE erasure_records SET subject_email_sha256 = repeat('0', 64) WHERE id = ?",
                            unrecorded),
                    "bypass role rewriting the subject digest on a zero-count record");
            assertThat(recordJson(a, unrecorded)).as("the zero-count record after the bypass role's rewrite")
                    .isEqualTo(unrecordedBefore);
        }
    }

    // ---- Arm F (#771) --------------------------------------------------------------------------

    @Test
    void erasureNeverDeletesCatalogueImagesOrAnotherReviewsPhoto() throws Exception {
        UUID a = seedTenant();
        String email = "reviewer-771-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "other-771-" + UUID.randomUUID() + "@example.com";
        UUID shopId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        String hex = HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()).substring(0, 16);

        Stored pimg = store("PIMG", a + "/products/" + productId + "/" + UUID.randomUUID() + ".webp");
        Stored padd = store("PADD", a + "/products/" + productId + "/" + UUID.randomUUID() + ".webp");
        Stored logo = store("LOGO", a + "/shops/" + shopId + "/logo-" + hex + ".webp");
        Stored banner = store("BANNER", a + "/shops/" + shopId + "/banner-" + hex + ".webp");
        Stored mderiv = store("MDERIV", a + "/media/" + assetId + ".webp");
        Stored mthumb = store("MTHUMB", a + "/media/" + assetId + "_thumb.webp");
        Stored orph = store("ORPH", a + "/products/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".webp");

        seedShopWithImages(a, shopId, logo.url(), banner.url());
        seedProductWithImages(a, productId, pimg.url(), padd.url());
        seedActiveMediaAsset(a, assetId, a + "/media/" + assetId + ".webp");
        seedProductMedia(a, productId, assetId);

        UUID customerId = seedCustomer(a, email);
        UUID orderA = seedOrder(a, shopId, email);
        seedCustomer(a, otherEmail);
        UUID order2 = seedOrder(a, shopId, otherEmail);

        Stored other = store("OTHER", a + "/reviews/" + order2 + "/p.webp");
        Stored own = store("OWN", a + "/reviews/" + orderA + "/p.webp");
        UUID otherReview = seedReview(a, shopId, order2, otherEmail, "Other Reviewer", "not mine", other.url());

        List<Stored> kept = List.of(pimg, padd, logo, banner, mderiv, mthumb, orph, other);
        List<Stored> all = new ArrayList<>(kept);
        all.add(own);
        // Seeded by SQL, NOT through ReviewService: a row planted before creation-time validation.
        UUID reviewId = seedReview(a, shopId, orderA, email, "Reviewer 771", "names the catalogue",
                all.stream().map(Stored::url).toArray(String[]::new));

        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE id = ?", reviewId))
                .as("PRECONDITION: tenant A's session sees the subject review").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM products WHERE id = ? AND image_url = ?", productId, pimg.url()))
                .as("PRECONDITION: the product and its image_url are visible under tenant A's RLS").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM media_asset WHERE id = ?", assetId))
                .as("PRECONDITION: the media_asset row is visible under tenant A's RLS").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM shops WHERE id = ? AND logo_url = ?", shopId, logo.url()))
                .as("PRECONDITION: the shop and its logo_url are visible under tenant A's RLS").isEqualTo(1L);
        Map<String, HttpResponse<byte[]>> before = fetchAll(all);
        before.forEach((name, r) -> assertThat(r.statusCode()).as("PRECONDITION: %s is served", name).isEqualTo(200));

        TenantContext.set(a);
        var outcome = gdprService.eraseCustomerData(customerId);
        TenantContext.clear();

        Map<String, HttpResponse<byte[]>> after = fetchAll(all);
        long durable = countUnder(a, "SELECT photos_deleted FROM erasure_records WHERE subject_customer_id = ?",
                customerId);
        ReviewRow subject = readReview(a, reviewId);
        ReviewRow otherRow = readReview(a, otherReview);
        SoftAssertions.assertSoftly(softly -> {
            for (Stored o : kept) {
                HttpResponse<byte[]> r = after.get(o.name());
                softly.assertThat(r.statusCode()).as("%s after erasure", o.name()).isEqualTo(200);
                softly.assertThat(r.body()).as("%s bytes after erasure", o.name()).isEqualTo(o.bytes());
            }
            softly.assertThat(after.get("OWN").statusCode()).as("OWN (the review's own-order photo) after erasure")
                    .isEqualTo(404);
            softly.assertThat(outcome.photosDeleted()).as("photosDeleted").isEqualTo(1);
            softly.assertThat(outcome.reviewsAnonymised()).as("reviewsAnonymised").isEqualTo(1);
            softly.assertThat(durable).as("durable erasure_records.photos_deleted").isEqualTo(1L);
            softly.assertThat(subject.photoUrls()).as("the subject review's photo_urls").isNull();
            softly.assertThat(otherRow.photoUrls()).as("the other customer's review photo_urls")
                    .containsExactly(other.url());
        });
    }

    // ---- Arm G (#771) --------------------------------------------------------------------------

    @Test
    void erasureKeepsAReviewPhotoTheCatalogueReferences() throws Exception {
        UUID a = seedTenant();
        String email = "shared-771-" + UUID.randomUUID() + "@example.com";
        UUID shopA = seedShop(a, false);
        UUID customerId = seedCustomer(a, email);
        UUID orderA = seedOrder(a, shopA, email);

        Stored shared = store("SHARED", a + "/reviews/" + orderA + "/shared.webp");
        Stored own2 = store("OWN2", a + "/reviews/" + orderA + "/own.webp");
        UUID p2 = UUID.randomUUID();
        seedProductWithImages(a, p2, shared.url());
        UUID reviewId = seedReview(a, shopA, orderA, email, "Reviewer 771 G", "shared photo",
                shared.url(), own2.url());

        assertThat(countUnder(a, "SELECT COUNT(*) FROM reviews WHERE id = ?", reviewId))
                .as("PRECONDITION: tenant A's session sees the subject review").isEqualTo(1L);
        assertThat(countUnder(a, "SELECT COUNT(*) FROM products WHERE id = ? AND image_url = ?", p2, shared.url()))
                .as("PRECONDITION: P2 and its image_url are visible under tenant A's RLS").isEqualTo(1L);
        Map<String, HttpResponse<byte[]>> before = fetchAll(List.of(shared, own2));
        before.forEach((name, r) -> assertThat(r.statusCode()).as("PRECONDITION: %s is served", name).isEqualTo(200));

        TenantContext.set(a);
        var outcome = gdprService.eraseCustomerData(customerId);
        TenantContext.clear();

        Map<String, HttpResponse<byte[]>> after = fetchAll(List.of(shared, own2));
        long durable = countUnder(a, "SELECT photos_deleted FROM erasure_records WHERE subject_customer_id = ?",
                customerId);
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(after.get("SHARED").statusCode()).as("SHARED (catalogue-referenced) after erasure")
                    .isEqualTo(200);
            softly.assertThat(after.get("SHARED").body()).as("SHARED bytes after erasure").isEqualTo(shared.bytes());
            softly.assertThat(after.get("OWN2").statusCode()).as("OWN2 (unreferenced own-order photo) after erasure")
                    .isEqualTo(404);
            softly.assertThat(outcome.photosDeleted()).as("photosDeleted").isEqualTo(1);
            softly.assertThat(durable).as("durable erasure_records.photos_deleted").isEqualTo(1L);
        });
    }

    // ---- helpers -------------------------------------------------------------------------------

    private UUID seedTenant() {
        UUID id = UUID.randomUUID();
        // tenants carries no RLS.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "764-" + id);
        return id;
    }

    private UUID seedShop(UUID tenant, boolean published) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0)",
                id, tenant, "shop-" + id, "shop-764-" + id, "Test Address", published);
        return id;
    }

    private UUID seedCustomer(UUID tenant, String email) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO customers (id, tenant_id, name, email) VALUES (?, ?, ?, ?)",
                id, tenant, "Subject " + id, email);
        return id;
    }

    private UUID seedOrder(UUID tenant, UUID shop, String email) {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, "
                        + "  customer_email, customer_name, total_amount_pennies, delivery_fee_pennies, "
                        + "  subtotal_pennies, vat_rate, vat_amount_pennies) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 1000, 0, 1000, 'ZERO', 0)",
                id, tenant, shop, "ORD-" + id.toString().substring(0, 8), email, "Test " + email);
        return id;
    }

    private UUID seedReview(UUID tenant, UUID shop, UUID order, String email, String name, String comment,
                            String... photoUrls) {
        UUID id = UUID.randomUUID();
        String array = "ARRAY[" + String.join(", ", java.util.Collections.nCopies(photoUrls.length, "?"))
                + "]::text[]";
        List<Object> params = new ArrayList<>(List.of(id, tenant, shop, order, email, name, comment));
        params.addAll(Arrays.asList(photoUrls));
        update(tenant, "INSERT INTO reviews (id, tenant_id, shop_id, order_id, customer_email, customer_name, "
                        + "  food_rating, comment, photo_urls) VALUES (?, ?, ?, ?, ?, ?, 5, ?, " + array + ")",
                params.toArray());
        return id;
    }

    /**
     * A review photo keyed the way a review-photo upload path must store one:
     * {@code <tenant>/reviews/<orderId>/<one plain name>}. Built here as a string rather than through
     * the production rule so the class compiles on a tree that does not have that rule yet.
     */
    private String putPhoto(UUID tenant, UUID order, byte[] bytes) {
        return putObject(tenant + "/reviews/" + order + "/" + UUID.randomUUID() + ".webp", bytes);
    }

    private String putObject(String key, byte[] bytes) {
        return storageService.putBytes(key, bytes, "image/webp");
    }

    private void seedShopWithImages(UUID tenant, UUID id, String logoUrl, String bannerUrl) {
        update(tenant, "INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies, "
                        + "  logo_url, banner_url) VALUES (?, ?, ?, ?, ?, false, 0, ?, ?)",
                id, tenant, "shop-" + id, "shop-771-" + id, "Test Address", logoUrl, bannerUrl);
    }

    private void seedProductWithImages(UUID tenant, UUID id, String imageUrl, String... galleryUrls) {
        String array = galleryUrls.length == 0 ? "'{}'::text[]"
                : "ARRAY[" + String.join(", ", java.util.Collections.nCopies(galleryUrls.length, "?")) + "]::text[]";
        List<Object> params = new ArrayList<>(List.of(id, tenant, "SKU-" + id.toString().substring(0, 8),
                "Product " + id, "Yam (100%)"));
        params.add(imageUrl);
        params.addAll(Arrays.asList(galleryUrls));
        update(tenant, "INSERT INTO products (id, tenant_id, sku, title, ingredients_text, image_url, "
                        + "  additional_image_urls) VALUES (?, ?, ?, ?, ?, ?, " + array + ")",
                params.toArray());
    }

    private void seedActiveMediaAsset(UUID tenant, UUID id, String objectKey) {
        byte[] sha = new byte[32];
        ThreadLocalRandom.current().nextBytes(sha);
        update(tenant, "INSERT INTO media_asset (id, tenant_id, object_key, sha256, content_type, status) "
                        + "VALUES (?, ?, ?, ?, 'image/webp', 'ACTIVE')",
                id, tenant, objectKey, HexFormat.of().formatHex(sha));
    }

    private void seedProductMedia(UUID tenant, UUID productId, UUID assetId) {
        update(tenant, "INSERT INTO product_media (id, tenant_id, product_id, asset_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, true, 0)",
                UUID.randomUUID(), tenant, productId, assetId);
    }

    /** One named object in Blob and the bytes it was written with. */
    private record Stored(String name, String url, byte[] bytes) {
    }

    private Stored store(String name, String key) {
        byte[] bytes = webpBytes();
        return new Stored(name, putObject(key, bytes), bytes);
    }

    /** Every object's GET, taken before any soft assertion so the block itself throws nothing checked. */
    private static Map<String, HttpResponse<byte[]>> fetchAll(List<Stored> objects) throws Exception {
        Map<String, HttpResponse<byte[]>> out = new LinkedHashMap<>();
        for (Stored o : objects) {
            out.put(o.name(), anonymousGet(o.url()));
        }
        return out;
    }

    private record ReviewRow(String customerName, String customerEmail, String comment, List<String> photoUrls) {
    }

    private ReviewRow readReview(UUID tenant, UUID reviewId) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT customer_name, customer_email, comment, photo_urls FROM reviews WHERE id = ?")) {
                ps.setObject(1, reviewId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("review %s is visible to tenant %s", reviewId, tenant).isTrue();
                    Array arr = rs.getArray(4);
                    List<String> urls = arr == null ? null : Arrays.asList((String[]) arr.getArray());
                    return new ReviewRow(rs.getString(1), rs.getString(2), rs.getString(3), urls);
                }
            }
        });
    }

    private long countUnder(UUID tenant, String sql, Object... params) {
        Long n = inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        return n == null ? 0 : n;
    }

    private void update(UUID tenant, String sql, Object... params) {
        inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    private UUID seedErasureRecord(UUID tenant, int photosDeleted) {
        UUID id = UUID.randomUUID();
        byte[] digest = new byte[32];
        ThreadLocalRandom.current().nextBytes(digest);
        update(tenant, "INSERT INTO erasure_records (id, tenant_id, subject_customer_id, subject_email_sha256, "
                        + "  orders_anonymised, reviews_anonymised, aud_rows_scrubbed, photos_deleted, erased_by) "
                        + "VALUES (?, ?, ?, ?, 1, 1, 2, ?, 'admin-764')",
                id, tenant, UUID.randomUUID(), HexFormat.of().formatHex(digest), photosDeleted);
        return id;
    }

    /** The whole row, by content, read in a fresh tenant-pinned transaction. */
    private String recordJson(UUID tenant, UUID recordId) {
        return recordJsonWithout(tenant, recordId, "");
    }

    private String recordJsonWithout(UUID tenant, UUID recordId, String column) {
        return inTenant(tenant, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT (to_jsonb(e) - ?::text)::text FROM erasure_records e WHERE id = ?")) {
                bind(ps, column, recordId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("erasure record %s is visible to tenant %s", recordId, tenant).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    /** Drops one key from a jsonb text value in the database, so both sides use the same rendering. */
    private String jsonWithout(String json, String column) {
        return jdbc.queryForObject("SELECT (?::jsonb - ?::text)::text", String.class, json, column);
    }

    /** The outcome of one UPDATE: its row count, or the SQLException that refused it. */
    private record Attempt(int rows, SQLException refusal) {
    }

    private Attempt attemptUnder(UUID tenant, String sql, Object... params) {
        try {
            Integer rows = inTenant(tenant, connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    bind(ps, params);
                    return ps.executeUpdate();
                }
            });
            return new Attempt(rows == null ? -1 : rows, null);
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException refusal) {
                    return new Attempt(-1, refusal);
                }
            }
            throw e;
        }
    }

    private static Attempt attemptOn(Connection connection, String sql, Object... params) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return new Attempt(ps.executeUpdate(), null);
        } catch (SQLException e) {
            return new Attempt(-1, e);
        }
    }

    /**
     * Refused, and refused by the write-once guard specifically: SQLSTATE 42501 AND the guard's own
     * message token, so an unrelated error (a typo, a lost connection, an RLS WITH CHECK) cannot pass.
     */
    private static void assertRefusedByGuard(Attempt attempt, String what) {
        assertThat((Throwable) attempt.refusal())
                .as("%s must be REFUSED by the database, but it updated %s row(s)", what, attempt.rows())
                .isNotNull();
        assertThat(attempt.refusal().getSQLState())
                .as("%s: SQLSTATE (message: %s)", what, attempt.refusal().getMessage())
                .isEqualTo("42501");
        assertThat(attempt.refusal().getMessage()).as("%s: refused by the write-once guard", what)
                .contains(GUARD_TOKEN);
    }

    private interface ConnectionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** One fresh transaction with the tenant GUC pinned on the Hibernate transaction's own connection. */
    private <T> T inTenant(UUID tenant, ConnectionWork<T> work) {
        return new TransactionTemplate(txManager).execute(s ->
                entityManager.unwrap(Session.class).doReturningWork(connection -> {
                    try (PreparedStatement pin = connection.prepareStatement(
                            "SELECT set_config('app.current_tenant_id', ?, true)")) {
                        pin.setString(1, tenant.toString());
                        pin.execute();
                    }
                    return work.run(connection);
                }));
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static byte[] webpBytes() {
        byte[] bytes = new byte[64];
        ThreadLocalRandom.current().nextBytes(bytes);
        byte[] header = {0x52, 0x49, 0x46, 0x46, 0x38, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static HttpResponse<byte[]> anonymousGet(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
