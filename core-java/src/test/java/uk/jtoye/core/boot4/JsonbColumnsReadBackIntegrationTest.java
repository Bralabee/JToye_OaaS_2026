package uk.jtoye.core.boot4;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import uk.jtoye.core.onboarding.GateType;
import uk.jtoye.core.onboarding.VendorOnboardingGate;
import uk.jtoye.core.onboarding.VendorOnboardingGateRepository;
import uk.jtoye.core.product.AllergenSpan;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 38 (BOOT4-08): jsonb values the Boot 3.5.16 / Jackson 2 Hibernate JSON mapper wrote must
 * still read back to equal Java values after the Hibernate 7 / Jackson 3 move.
 *
 * <p>Three columns are mapped with {@code @JdbcTypeCode(SqlTypes.JSON)} and so go through
 * Hibernate's JSON {@code FormatMapper}: {@code shops.opening_hours} ({@code Map<String,String>}),
 * {@code products.allergen_spans} ({@code List<AllergenSpan>}) and
 * {@code vendor_onboarding_gate.evidence} ({@code Map<String,Object>}). The fixtures under
 * {@code src/test/resources/jackson2-golden/jsonb/} are the stored text ({@code SELECT col::text})
 * of values the 3.5 runtime path persisted. Each test writes that text into a freshly created row
 * with JDBC ({@code CAST(? AS jsonb)}), so the mapper under test never wrote it, then loads the
 * entity through its production repository under the tenant and compares it with the value built
 * here.
 *
 * <p>The subject is the stored FORMAT, not row security. The Testcontainers bootstrap role is a
 * superuser and is used deliberately; RLS is proven in {@code RlsContractTest}. The tenant is
 * still set through {@link TenantContext} inside the transaction, as the scoping analog does, so
 * the load runs the production path (TenantSetLocalAspect pins the GUC).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@Tag("testcontainers")
class JsonbColumnsReadBackIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    static final Path JSONB_DIR = Path.of("src", "test", "resources", "jackson2-golden", "jsonb");
    static final String OPENING_HOURS = "shops.opening_hours.json";
    static final String ALLERGEN_SPANS = "products.allergen_spans.json";
    static final String GATE_EVIDENCE = "vendor_onboarding_gate.evidence.json";

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000b004");

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private VendorOnboardingGateRepository gateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                TENANT, "Jsonb Read-back Tenant");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- expected Java values (the same ones the capture persisted) ----

    static Map<String, String> expectedOpeningHours() {
        Map<String, String> hours = new LinkedHashMap<>();
        hours.put("monday", "09:00 - 17:00");
        hours.put("saturday", "Closed");
        return hours;
    }

    static List<AllergenSpan> expectedAllergenSpans() {
        return List.of(new AllergenSpan(0, 5), new AllergenSpan(12, 17));
    }

    static Map<String, Object> expectedGateEvidence() {
        Map<String, Object> establishment = new LinkedHashMap<>();
        establishment.put("local_authority", "Southwark");
        establishment.put("score", 4.5);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("fhrs_rating", 4);
        evidence.put("establishment_id", "1234567");
        evidence.put("scheme", "FHRS");
        evidence.put("verified", true);
        evidence.put("sources", List.of("fsa-api", "manual"));
        evidence.put("establishment", establishment);
        return evidence;
    }

    // ---- read-back ----

    @Test
    @DisplayName("shops.opening_hours: Jackson-2-era stored text reads back to the same Map<String,String>")
    void openingHoursReadBack() throws IOException {
        String stored = fixture(OPENING_HOURS);
        UUID shopId = createShop("jsonb-readback-shop", null);
        jdbcTemplate.update("UPDATE shops SET opening_hours = CAST(? AS jsonb) WHERE id = ?", stored, shopId);
        entityManager.clear();

        TenantContext.set(TENANT);
        Shop loaded = shopRepository.findById(shopId).orElseThrow();
        assertThat(loaded.getOpeningHours())
                .as("shops.opening_hours read back from %s", OPENING_HOURS)
                .isEqualTo(expectedOpeningHours());
    }

    @Test
    @DisplayName("products.allergen_spans: Jackson-2-era stored text reads back to the same List<AllergenSpan>")
    void allergenSpansReadBack() throws IOException {
        String stored = fixture(ALLERGEN_SPANS);
        UUID productId = createProduct("JSONB-RB-1", null);
        jdbcTemplate.update("UPDATE products SET allergen_spans = CAST(? AS jsonb) WHERE id = ?", stored, productId);
        entityManager.clear();

        TenantContext.set(TENANT);
        Product loaded = productRepository.findById(productId).orElseThrow();
        assertThat(loaded.getAllergenSpans())
                .as("products.allergen_spans read back from %s", ALLERGEN_SPANS)
                .isEqualTo(expectedAllergenSpans());
    }

    @Test
    @DisplayName("vendor_onboarding_gate.evidence: Jackson-2-era stored text reads back to the same nested Map<String,Object>")
    void gateEvidenceReadBack() throws IOException {
        String stored = fixture(GATE_EVIDENCE);
        UUID gateId = createGate(null);
        jdbcTemplate.update("UPDATE vendor_onboarding_gate SET evidence = CAST(? AS jsonb) WHERE id = ?", stored, gateId);
        entityManager.clear();

        TenantContext.set(TENANT);
        VendorOnboardingGate loaded = gateRepository.findById(gateId).orElseThrow();
        assertThat(loaded.getEvidence())
                .as("vendor_onboarding_gate.evidence read back from %s", GATE_EVIDENCE)
                .isEqualTo(expectedGateEvidence());
    }

    // ---- capture (one-shot, Boot 3.5 tree only; deleted after the capture commit) ----

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "JTOYE_GOLDEN_CAPTURE", matches = "true")
    @DisplayName("CAPTURE: persist the three values through Hibernate's JSON mapper and record the stored text")
    void captureJackson2EraStoredText() throws IOException {
        UUID shopId = createShop("jsonb-capture-shop", expectedOpeningHours());
        UUID productId = createProduct("JSONB-CAP-1", expectedAllergenSpans());
        UUID gateId = createGate(expectedGateEvidence());

        Map<String, String> stored = new java.util.TreeMap<>();
        stored.put(OPENING_HOURS, jdbcTemplate.queryForObject(
                "SELECT opening_hours::text FROM shops WHERE id = ?", String.class, shopId));
        stored.put(ALLERGEN_SPANS, jdbcTemplate.queryForObject(
                "SELECT allergen_spans::text FROM products WHERE id = ?", String.class, productId));
        stored.put(GATE_EVIDENCE, jdbcTemplate.queryForObject(
                "SELECT evidence::text FROM vendor_onboarding_gate WHERE id = ?", String.class, gateId));

        Files.createDirectories(JSONB_DIR);
        StringBuilder manifest = new StringBuilder();
        for (Map.Entry<String, String> e : stored.entrySet()) {
            assertThat(e.getValue()).as("stored text of %s", e.getKey()).isNotBlank();
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            Files.write(JSONB_DIR.resolve(e.getKey()), bytes);
            manifest.append(e.getKey()).append('\t')
                    .append(GoldenFixturesIntegrityTest.sha256Hex(bytes)).append('\t')
                    .append(bytes.length).append('\n');
        }
        Files.writeString(JSONB_DIR.resolve("MANIFEST.tsv"), manifest.toString(), StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    static String fixture(String name) throws IOException {
        Path file = JSONB_DIR.resolve(name);
        assertThat(Files.isRegularFile(file))
                .as("jsonb golden fixture %s is missing (working dir %s): the Jackson-2-era stored text "
                        + "was never captured", file, Path.of("").toAbsolutePath())
                .isTrue();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private UUID createShop(String slug, Map<String, String> openingHours) {
        TenantContext.set(TENANT);
        Shop shop = new Shop();
        shop.setTenantId(TENANT);
        shop.setName("Shop " + slug);
        shop.setSlug(slug);
        shop.setOpeningHours(openingHours);
        UUID id = shopRepository.saveAndFlush(shop).getId();
        TenantContext.clear();
        entityManager.clear();
        return id;
    }

    private UUID createProduct(String sku, List<AllergenSpan> spans) {
        TenantContext.set(TENANT);
        Product product = new Product();
        product.setTenantId(TENANT);
        product.setSku(sku);
        product.setTitle("Jsonb read-back " + sku);
        product.setIngredientsText("wheat flour, milk");
        product.setAllergenMask(0);
        product.setPricePennies(450L);
        product.setAvailable(true);
        product.setAllergenSpans(spans);
        UUID id = productRepository.saveAndFlush(product).getId();
        TenantContext.clear();
        entityManager.clear();
        return id;
    }

    private UUID createGate(Map<String, Object> evidence) {
        UUID onboardingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO vendor_onboarding (id, tenant_id, model) VALUES (?, ?, 'MARKETPLACE')",
                onboardingId, TENANT);
        TenantContext.set(TENANT);
        VendorOnboardingGate gate = new VendorOnboardingGate();
        gate.setTenantId(TENANT);
        gate.setOnboardingId(onboardingId);
        gate.setGateType(GateType.FOOD_HYGIENE_RATING);
        gate.setEvidence(evidence);
        UUID id = gateRepository.saveAndFlush(gate).getId();
        TenantContext.clear();
        entityManager.clear();
        return id;
    }
}
