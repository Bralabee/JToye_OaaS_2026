package uk.jtoye.core.gdpr;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #764, proven against the real posture: Postgres 15 with every migration applied, the
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
        String a1 = putPhoto(a, a1Bytes);
        String a2 = putPhoto(a, a2Bytes);
        String bx = putPhoto(b, bxBytes);
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
        String aPhoto = putPhoto(a, webpBytes());
        UUID reviewA = seedReview(a, shopA, orderA, email, "Reviewer in A", "comment in A", aPhoto);

        UUID shopB = seedShop(b, true);
        UUID orderB = seedOrder(b, shopB, email);
        byte[] bPhotoBytes = webpBytes();
        String bPhoto = putPhoto(b, bPhotoBytes);
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
        String photo = putPhoto(a, photoBytes);
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

    private String putPhoto(UUID tenant, byte[] bytes) {
        return storageService.putBytes(tenant + "/reviews/" + UUID.randomUUID() + "/p.webp", bytes, "image/webp");
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
