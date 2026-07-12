package uk.jtoye.core.onboarding;

import jakarta.persistence.EntityManager;
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
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence round-trip proof for the vendor-onboarding aggregate on real
 * Postgres 15 (Testcontainers) — the JPA half of the 18-01 data layer. Proves:
 *
 * <ul>
 *   <li>{@link VendorOnboarding} persists and reloads every business column,
 *       {@code status} defaults to {@code DRAFT}, and {@code @Version} starts at 0;</li>
 *   <li>{@link VendorOnboardingGate} round-trips its {@code evidence} JSONB
 *       ({@code Map<String,Object>}) via the same {@code @JdbcTypeCode(SqlTypes.JSON)}
 *       mapping Product.java uses, and {@code status} defaults to {@code PENDING};</li>
 *   <li>{@link OnboardingProperties} binds the {@code onboarding.*} yaml keys —
 *       {@code fhrs.min-rating = 2}, {@code fhrs.api-version = "2"} — into the bean.</li>
 * </ul>
 *
 * <p>RLS <em>enforcement</em> (NOSUPERUSER cross-tenant denial) is proven
 * separately in {@link VendorOnboardingRlsIntegrationTest}; here the bootstrap
 * superuser role is fine because we only exercise the happy read/write path.
 * Each test method is {@code @Transactional} (rolls back) and sets
 * {@link TenantContext} so {@code TenantSetLocalAspect} applies the tenant GUC
 * exactly as production does.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class VendorOnboardingPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private VendorOnboardingRepository onboardingRepository;
    @Autowired private VendorOnboardingGateRepository gateRepository;
    @Autowired private OnboardingProperties onboardingProperties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void seedTenantAndShop() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        // Seed the FK targets outside any test transaction (autocommit) so they
        // survive the per-method rollback. `tenants` is the registry (no RLS);
        // shops.shop_id is the vendor_onboarding FK target.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenantId, "test-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, address, published, delivery_fee_pennies) " +
                        "VALUES (?, ?, ?, ?, ?, false, 0)",
                shopId, tenantId, "shop-" + shopId, "slug-" + shopId.toString().substring(0, 8), "Test Address");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void onboardingRoundTripsEveryColumnAndDefaultsStatusToDraft() {
        TenantContext.set(tenantId);

        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        // status intentionally left unset — must default to DRAFT.
        onboarding.setCompanyNumber("12345678");
        onboarding.setStripeAccountId("acct_reserved_slice2");
        OffsetDateTime submittedAt = OffsetDateTime.now().minusHours(2);
        onboarding.setSubmittedAt(submittedAt);
        onboarding.setRejectionReason("n/a");

        VendorOnboarding saved = onboardingRepository.saveAndFlush(onboarding);
        UUID id = saved.getId();
        assertThat(id).isNotNull();
        assertThat(saved.getVersion()).isZero();

        // Force a reload from the DB rather than the persistence-context cache.
        entityManager.clear();
        VendorOnboarding reloaded = onboardingRepository.findById(id).orElseThrow();

        assertThat(reloaded.getTenantId()).isEqualTo(tenantId);
        assertThat(reloaded.getShopId()).isEqualTo(shopId);
        assertThat(reloaded.getModel()).isEqualTo(OnboardingModel.MARKETPLACE);
        assertThat(reloaded.getStatus()).isEqualTo(OnboardingState.DRAFT);
        assertThat(reloaded.getCompanyNumber()).isEqualTo("12345678");
        assertThat(reloaded.getStripeAccountId()).isEqualTo("acct_reserved_slice2");
        assertThat(reloaded.getSubmittedAt()).isNotNull();
        assertThat(reloaded.getRejectionReason()).isEqualTo("n/a");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @Transactional
    void gateEvidenceJsonbRoundTripsAndStatusDefaultsToPending() {
        TenantContext.set(tenantId);

        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.MARKETPLACE);
        VendorOnboarding savedOnboarding = onboardingRepository.saveAndFlush(onboarding);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("scheme", "FHRS");
        evidence.put("fhrs_rating", 4);
        evidence.put("establishment_id", "123456");

        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(savedOnboarding.getId());
        gate.setGateType(GateType.FOOD_HYGIENE_RATING);
        // status left unset — must default to PENDING.
        gate.setEvidence(evidence);
        gate.setExternalRef("123456");
        VendorOnboardingGate savedGate = gateRepository.saveAndFlush(gate);
        UUID gateId = savedGate.getId();

        entityManager.clear();
        VendorOnboardingGate reloaded = gateRepository.findById(gateId).orElseThrow();

        assertThat(reloaded.getGateType()).isEqualTo(GateType.FOOD_HYGIENE_RATING);
        assertThat(reloaded.getStatus()).isEqualTo(GateStatus.PENDING);
        assertThat(reloaded.isMandatory()).isTrue();
        assertThat(reloaded.getExternalRef()).isEqualTo("123456");
        assertThat(reloaded.getEvidence()).isNotNull();
        assertThat(reloaded.getEvidence()).containsEntry("scheme", "FHRS");
        assertThat(reloaded.getEvidence()).containsKey("fhrs_rating");

        // Repository finder round-trips too.
        Optional<VendorOnboardingGate> byType = gateRepository
                .findByOnboardingIdAndGateType(savedOnboarding.getId(), GateType.FOOD_HYGIENE_RATING);
        assertThat(byType).isPresent();
        assertThat(gateRepository.findByOnboardingId(savedOnboarding.getId())).hasSize(1);
        assertThat(onboardingRepository.findByTenantId(tenantId)).isPresent();
    }

    @Test
    void onboardingPropertiesBindMinRatingTwoAndApiVersionTwo() {
        assertThat(onboardingProperties.getFhrs().getMinRating()).isEqualTo(2);
        assertThat(onboardingProperties.getFhrs().getApiVersion()).isEqualTo("2");
        assertThat(onboardingProperties.getFhrs().getBaseUrl()).isNotBlank();
        assertThat(onboardingProperties.getCompaniesHouse().getBaseUrl()).isNotBlank();
        // Secret defaults empty (not null) when the env var is unset in the test profile.
        assertThat(onboardingProperties.getCompaniesHouse().getApiKey()).isNotNull();
    }

    /**
     * IN-04: a gate row write stamps {@code updated_at} via {@code @UpdateTimestamp}
     * — before the fix it stayed NULL after every evaluation, misleading ops
     * queries. Proven against the actual column (not just the entity field) so a
     * mapping regression cannot fake a pass.
     */
    @Test
    @Transactional
    void gateUpdateStampsUpdatedAt() {
        TenantContext.set(tenantId);

        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(OnboardingModel.WHITE_LABEL);
        VendorOnboarding savedOnboarding = onboardingRepository.saveAndFlush(onboarding);

        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(tenantId);
        gate.setOnboardingId(savedOnboarding.getId());
        gate.setGateType(GateType.BUSINESS_VERIFIED);
        VendorOnboardingGate savedGate = gateRepository.saveAndFlush(gate);

        // The evaluation-shaped write path: status + checkedAt, then save.
        savedGate.setStatus(GateStatus.PASSED);
        savedGate.setCheckedAt(OffsetDateTime.now());
        gateRepository.saveAndFlush(savedGate);

        OffsetDateTime updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM vendor_onboarding_gate WHERE id = ?",
                OffsetDateTime.class, savedGate.getId());
        assertThat(updatedAt).isNotNull();

        entityManager.clear();
        assertThat(gateRepository.findById(savedGate.getId()).orElseThrow().getUpdatedAt()).isNotNull();
    }
}
