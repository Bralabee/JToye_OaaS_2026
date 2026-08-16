package uk.jtoye.core.security.access;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.media.MediaAsset;
import uk.jtoye.core.media.MediaAssetRepository;
import uk.jtoye.core.media.MediaProcessingEvent;
import uk.jtoye.core.media.MediaProcessingWorker;
import uk.jtoye.core.media.ProductMedia;
import uk.jtoye.core.media.ProductMediaRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * #283 + #284 over real Postgres 15: internal trust is DECLARED, and a background entry
 * point that reaches a gated service without declaring itself fails the build.
 *
 * <h2>What changed, and why this class exists</h2>
 *
 * {@code ShopAccessService.isInternalCaller()} used to return {@code true} whenever the
 * thread carried no {@code Authentication} — "I have no identity" meant "I am trusted"
 * (#283). Its safety rested on an unenforced property: that no gated service was reachable
 * from a background path. #284 recorded that as "one new call away", and Phase 24 made the
 * near-miss concrete — {@code MediaProcessingWorker} (a {@code @RabbitListener}) reaches
 * {@code MediaAssetService}, which gates three of its entry points, via the ONE entry point
 * that does not ({@code placeAsset}).
 *
 * <h2>The three arms</h2>
 * <ol>
 *   <li><b>DENY</b> — no {@code Authentication} and no declaration is now refused with the
 *       typed {@link ShopAccessDeniedException} 403 (never a 500, never a silent pass).</li>
 *   <li><b>ALLOW</b> — the same call inside {@link SystemPrincipal#asSystem} succeeds.</li>
 *   <li><b>#284 GUARD</b> — {@link #backgroundListenerPathStillCompletesUndeclared()} drives
 *       the REAL {@code MediaProcessingWorker} end-to-end on a thread shaped exactly like the
 *       AMQP consumer's (no {@code SecurityContext}, no declaration) and asserts the OUTCOME:
 *       the asset reaches {@code ACTIVE} and the {@code product_media} slot is placed.</li>
 * </ol>
 *
 * <p><b>Why the guard is behavioural, not structural.</b> A structural check — counting
 * {@code @RabbitListener} annotations, matching method names, asserting a declaration is
 * present — passes happily over a dead feature, which is a failure mode this project has
 * recorded going green while the thing it guarded was broken. This guard instead asserts
 * that the background path PRODUCES ITS EFFECT. If a future change routes the worker through
 * a {@code shopAccessService.require(...)} without wrapping it in {@code asSystem}, the
 * worker's thread is undeclared, the gate raises the typed 403, the asset never reaches
 * {@code ACTIVE}, and this test reds — which is precisely the event #284 asks to be caught.
 *
 * <p><b>Non-vacuity control.</b> "The worker completed" is also consistent with "the gate
 * grants everyone", so the guard asserts a second fact from the SAME undeclared thread
 * shape: a bare gated call is refused ({@link #anUndeclaredBackgroundThreadIsRefusedAtTheGate()}
 * and the in-test control inside the guard itself). The pair distinguishes "the background
 * path is legitimately ungated" from "the gate is toothless".
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Transactional
class SystemPrincipalGuardTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    private static final URI SHOP_ACCESS_DENIED_TYPE = URI.create("https://jtoye.uk/errors/shop-access-denied");

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private StaffManagementService staffManagementService;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private MediaProcessingWorker worker;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ProductMediaRepository productMediaRepository;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;
    @SpyBean private StorageService storageService;

    private UUID tenant;

    @BeforeEach
    void seedTenant() {
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "SystemPrincipal Guard Tenant " + tenant);
        SecurityContextHolder.clearContext();
        TenantContext.set(tenant);
        // MinIO is stubbed: the derivative write and the raw delete are captured, not performed.
        Mockito.doReturn("http://minio/derivative").when(storageService).putBytes(
                Mockito.anyString(), Mockito.any(byte[].class), Mockito.anyString());
        Mockito.doNothing().when(storageService).deleteByKey(Mockito.anyString());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        assertThat(SystemPrincipal.isSystem())
                .as("no test may leave a system declaration on the thread for the next one")
                .isFalse();
    }

    // ---- Arm 1: DENY — an undeclared no-principal thread is refused -------------------

    /**
     * The behaviour #283 asks for, and the exact inverse of what shipped before: with the
     * SecurityContext cleared and only {@link TenantContext} set, a gated call is now DENIED.
     *
     * <p>This is the arm that {@code ShopAccessFailClosedIntegrationTest.absentAuthentication*}
     * used to assert in the opposite direction, as the preservation guard for the retained
     * bypass. That test was updated in the same change; the bypass it preserved is the thing
     * being removed.
     */
    @Test
    void anUndeclaredBackgroundThreadIsRefusedAtTheGate() {
        ShopAccessDeniedException denied = catchThrowableOfType(
                () -> staffManagementService.list(), ShopAccessDeniedException.class);

        assertThat(denied)
                .as("an absent Authentication with NO declaration must be denied, not trusted (#283)")
                .isNotNull();

        // Fail-closed in this class's established shape: the typed 403, never an untyped 500.
        ProblemDetail pd = exceptionHandler.handleShopAccessDenied(denied);
        assertThat(pd.getStatus()).isEqualTo(403);
        assertThat(pd.getType()).isEqualTo(SHOP_ACCESS_DENIED_TYPE);
    }

    /**
     * {@code require(shopId, role)} — the write half — is refused on the same terms. Proving
     * only the read path would leave the mutating gate unproven.
     */
    @Test
    void anUndeclaredThreadIsRefusedOnTheWriteGateToo() {
        assertThatThrownBy(() -> shopAccessService.require(UUID.randomUUID(), ShopRole.STAFF))
                .as("the write gate denies an undeclared no-principal caller")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    /**
     * PRESERVED RULE: an anonymous request principal was never internal, and still is not.
     * The #283 change must not have quietly altered this — it is the fail-closed rule 23-08
     * established (CR-03 / D-04), and it now holds for a stronger reason: the condition is a
     * positive declaration rather than the absence of one thing.
     */
    @Test
    void anAnonymousPrincipalIsStillNotInternal() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anon-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> staffManagementService.list())
                .as("an anonymous principal is not internal — unchanged by #283")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    // ---- Arm 2: ALLOW — the same call inside a declared scope succeeds ----------------

    @Test
    void theSameCallSucceedsInsideADeclaredSystemScope() {
        assertThatCode(() -> SystemPrincipal.asSystem(() -> staffManagementService.list()))
                .as("declared internal system work passes the gate (#283: trust is asserted, not inferred)")
                .doesNotThrowAnyException();
    }

    /**
     * The declaration is scoped to the body, proven against the SAME gated call rather than
     * against {@link SystemPrincipal#isSystem()} alone: the call succeeds inside the scope and
     * is refused immediately after it. A marker that leaked past its scope would be a bypass
     * handed to whatever ran next on a pooled thread.
     */
    @Test
    void theDeclarationDoesNotOutliveItsScopeAtTheGate() {
        SystemPrincipal.asSystem(() -> assertThatCode(() -> staffManagementService.list())
                .doesNotThrowAnyException());

        assertThatThrownBy(() -> staffManagementService.list())
                .as("once the declared scope ends, the very same call is denied again")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    // ---- Arm 3: the #284 guard — a real background path, asserted by OUTCOME ----------

    /**
     * #284's guard. Drives the real {@code @RabbitListener} method
     * {@code MediaProcessingWorker.onMediaEvent} on a thread shaped exactly like the AMQP
     * consumer's — no {@code SecurityContext}, no {@link SystemPrincipal} declaration — and
     * asserts the pipeline's OUTCOME: the asset reaches {@code ACTIVE} and its
     * {@code product_media} slot is placed through {@code MediaAssetService.placeAsset}.
     *
     * <p>Today that path is legitimately ungated, so no declaration is needed and none was
     * added (declaring a background task that reaches nothing gated would grant a bypass
     * nothing needs). The moment a gate appears anywhere on it — a {@code require(...)} added
     * to {@code placeAsset}, or the worker routed through one of the three gated
     * {@code MediaAssetService} entry points — the undeclared thread is refused, the asset
     * never flips to {@code ACTIVE}, and this test reds. That is the guard.
     */
    @Test
    void backgroundListenerPathStillCompletesUndeclared() throws Exception {
        // The instrument must be valid: the thread really is undeclared and unauthenticated.
        assertThat(SystemPrincipal.isSystem()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // NON-VACUITY CONTROL: from this exact thread shape a gated call IS refused. Without
        // this, "the worker completed" would be equally consistent with a toothless gate.
        assertThatThrownBy(() -> shopAccessService.require(UUID.randomUUID(), ShopRole.STAFF))
                .as("control: the gate is live on this very thread, so the worker's success below is meaningful")
                .isInstanceOf(ShopAccessDeniedException.class);

        UUID productId = seedProduct();
        String quarantineKey = tenant + "/quarantine/" + UUID.randomUUID() + ".jpg";
        UUID assetId = insertPendingAsset(quarantineKey, productId);
        Mockito.doReturn(jpegOf(800, 600)).when(storageService).getBytes(quarantineKey);

        worker.onMediaEvent(new MediaProcessingEvent(tenant, assetId));
        em.flush();
        em.clear();

        // Re-pin: the worker clears the ThreadLocal in its own finally.
        TenantContext.set(tenant);

        assertThat(mediaAssetRepository.findById(assetId).orElseThrow().getStatus())
                .as("the undeclared @RabbitListener path completed: PENDING -> ACTIVE. If a gate is "
                        + "ever added to this path without a SystemPrincipal.asSystem declaration, "
                        + "this assertion is what fails (#284).")
                .isEqualTo(MediaAsset.Status.ACTIVE);

        List<ProductMedia> links = productMediaRepository
                .findByProductIdOrderByPrimaryDescSortOrderAsc(productId);
        assertThat(links)
                .as("MediaAssetService.placeAsset — the documented near-miss, one method away from "
                        + "three gated entry points — was reached and did its work")
                .hasSize(1);
        assertThat(links.get(0).getAssetId()).isEqualTo(assetId);
    }

    /**
     * The complement of the guard, and the reason a declaration is the right remedy rather
     * than reinstating the bypass: when a background path DOES need a gated call, wrapping it
     * in {@code asSystem} makes it work — so the fix for a future red in
     * {@link #backgroundListenerPathStillCompletesUndeclared()} is a one-line declaration at
     * the entry point, not a change to the gate.
     */
    @Test
    void aBackgroundPathThatNeedsAGateWorksOnceItDeclaresItself() {
        assertThatThrownBy(() -> shopAccessService.requireGroupAdmin())
                .as("undeclared: refused")
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThatCode(() -> SystemPrincipal.asSystem(() -> shopAccessService.requireGroupAdmin()))
                .as("declared: the same background call passes, with RLS still tenant-scoping it")
                .doesNotThrowAnyException();
    }

    // ---- Arm 4: the D-17 DSAR boundary — a request path that must NEVER declare -------

    /**
     * Phase 31 (LGL-01 / D-17, threat T-31-05-03): the public DSAR intake path never declares
     * system authority. {@code ShopAccessService}'s rule — a request thread never enters
     * {@link SystemPrincipal#asSystem}; only background entry points do — is what reconciles a
     * single cross-tenant data-subject-request desk with a project that has twice refused a
     * cross-tenant operator identity. No human ever holds that reach; only plan 31-09's scheduled
     * worker does. This makes the rule executable for the one path where breaking it would be
     * both tempting and catastrophic.
     *
     * <p><b>Why this arm is a SOURCE scan, and why that is not a retreat from behaviour.</b> The
     * runtime probe is genuinely weak here, and the reason is in {@code SystemPrincipal}'s own
     * contract: {@code asSystem} RESTORES the prior value in a {@code finally}, so once the call
     * returns the thread looks byte-for-byte identical whether or not it declared. Any assertion
     * taken before or after the intake therefore passes in both worlds — it is incapable of
     * failing, which is the shape this project refuses to count as evidence. Observing from
     * inside is possible at exactly one point (a spy at the service boundary, which
     * {@code DsarIntakeIntegrationTest.theIntakeRequestThreadNeverDeclaresSystemAuthority} does
     * hold, driving a real HTTP request), but a declaration added INSIDE the service body would
     * slip past it. The scan has no such blind spot: it fails on a declaration anywhere on the
     * intake path, which is the property D-17 actually asks for. The two arms are complements —
     * runtime for "the live path does not declare", source for "no part of it can".
     *
     * <p><b>Non-vacuity.</b> A source scan that reads no files is trivially satisfied, and a
     * renamed or moved class would produce exactly that. So each file is asserted to exist and to
     * be non-empty BEFORE its contents are judged, and the scan is asserted to have covered every
     * expected file — "found nothing" is never "found nothing wrong".
     */
    @Test
    void theDsarIntakePathNeverDeclaresSystemAuthority() throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src", "main", "java");
        if (!java.nio.file.Files.isDirectory(root)) {
            root = java.nio.file.Path.of("core-java", "src", "main", "java");
        }
        assertThat(java.nio.file.Files.isDirectory(root))
                .as("cannot locate the core-java main source root from %s — the scan below would "
                        + "read nothing and pass vacuously", java.nio.file.Path.of("").toAbsolutePath())
                .isTrue();

        List<String> intakePath = List.of(
                "uk/jtoye/core/gdpr/DsarIntakeController.java",
                "uk/jtoye/core/gdpr/DsarIntakeService.java",
                "uk/jtoye/core/gdpr/DsarIntakeRateLimiter.java");

        int scanned = 0;
        for (String relative : intakePath) {
            java.nio.file.Path file = root.resolve(relative);
            assertThat(java.nio.file.Files.isRegularFile(file))
                    .as("%s is missing. If the DSAR intake moved, update this list — otherwise the "
                            + "guard silently stops covering the path it was written for.", relative)
                    .isTrue();

            String source = java.nio.file.Files.readString(file);
            assertThat(source)
                    .as("%s is empty, so scanning it proves nothing", relative)
                    .isNotBlank();

            assertThat(source)
                    .as("%s declares system authority on the DSAR INTAKE path. That path is a "
                            + "REQUEST thread, and ShopAccessService records the rule it must obey: "
                            + "only background entry points declare. D-17 depends on it — the whole "
                            + "reason one cross-tenant DSAR desk is acceptable is that no human "
                            + "path ever gains cross-tenant reach; only the scheduled fan-out "
                            + "worker does, one pinned tenant at a time. If this file genuinely "
                            + "needs a gated call, that is a design change, not a declaration to "
                            + "add here.", relative)
                    .doesNotContain("SystemPrincipal.asSystem");
            scanned++;
        }

        assertThat(scanned)
                .as("NON-VACUITY: the scan covered %d of %d intake files. A zero or partial count "
                        + "makes the 'no declaration found' result evidence about this loop, not "
                        + "about the code.", scanned, intakePath.size())
                .isEqualTo(intakePath.size());
    }

    // ---- helpers ---------------------------------------------------------------------

    private UUID seedProduct() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                id, tenant, "SKU-" + id.toString().substring(0, 8), "Guard Product", "Yam (100%)");
        return id;
    }

    private UUID insertPendingAsset(String quarantineKey, UUID productId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO media_asset "
                        + "(id, tenant_id, object_key, sha256, content_type, status, product_id, is_primary, sort_order) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 'PENDING', ?, true, 0)",
                id, tenant, quarantineKey, randomSha(), productId);
        return id;
    }

    private static String randomSha() {
        String s = UUID.randomUUID().toString().replace("-", "") + "0".repeat(64);
        return s.substring(0, 64);
    }

    private static byte[] jpegOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, w, h, Color.BLUE));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }
}
