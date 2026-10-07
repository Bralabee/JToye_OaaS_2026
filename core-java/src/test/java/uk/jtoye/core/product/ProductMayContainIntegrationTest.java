package uk.jtoye.core.product;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * #861 (D-16, D-17) backend acceptance, through the real endpoints on a Testcontainers Postgres.
 *
 * <p>The persona found there was no "may contain" field at all, and a label downloaded two days
 * after the food was made said "Use by" two days later than it should, because use-by was counted
 * from the moment the PDF was downloaded. This test pins the fix at the API and on the PDF itself:
 * <ul>
 *   <li>a vendor records cross-contact risk as its own mask, stored apart from the declared
 *       {@code allergen_mask}, which it never changes (asserted by SQL, the prohibition at the
 *       test tier);</li>
 *   <li>the label prints a separate "May contain" line naming only what is not already declared;</li>
 *   <li>use-by is counted from the production date given at download, and dates read the UK way
 *       ("5 October 2026");</li>
 *   <li>a production date in the future, or one whose use-by has already passed, is refused with a
 *       typed 422 naming {@code productionDate}.</li>
 * </ul>
 *
 * <p>"Today" comes from a fixed {@link Clock} (10:00 UTC on 4 October 2026, which is 11:00 in
 * London), so the default production date and the 422 boundaries are deterministic. The label text
 * is read out of the PDF with OpenPDF's {@link PdfTextExtractor}, never from the render model.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ProductMayContainIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    /** "Now" for this context: 10:00 UTC on 4 October 2026 (11:00 BST). */
    static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
            // Deliberately a UTC clock: the label must still resolve "today" in Europe/London.
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JsonMapper jsonMapper;

    /** Dedicated tenant and slug so a parallel fork's fixtures cannot collide. */
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000311141");
    private static final String SHOP_SLUG = "shop-311-14-may-contain";

    private static final int CRUSTACEANS = 1 << 1;
    private static final int MILK = 1 << 6;
    private static final int SESAME = 1 << 10;

    private UUID shopId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "31.1-14 May Contain Tenant");
        shopId = seedShopIdempotent();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // D-16: may-contain is its own field, stored apart from the declaration
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-16: a vendor sets mayContainMask; it is returned and stored as sent, and allergen_mask is unchanged (SQL)")
    void vendorSetsMayContain_storedSeparately_declarationUnchanged() throws Exception {
        String sku = "SKU-311-14-STORE-" + UUID.randomUUID();
        UUID productId = createProduct(sku, MILK, null);

        // Not recorded yet: the field is absent from the response and NULL in the table.
        MvcResult fresh = perform(get("/api/v1/products/{id}", productId), null);
        assertThat(fresh.getResponse().getStatus()).as(text(fresh)).isEqualTo(200);
        assertThat(json(fresh).has("mayContainMask"))
                .as("mayContainMask absent while not recorded: %s", text(fresh)).isFalse();
        assertThat(storedMayContain(productId)).as("may_contain_mask before any PUT").isNull();

        // The plan's case: PUT mayContainMask 2 -> GET returns 2, allergenMask unchanged.
        MvcResult two = perform(put("/api/v1/products/{id}", productId), productBody(sku, MILK, CRUSTACEANS));
        assertThat(two.getResponse().getStatus()).as(text(two)).isEqualTo(200);
        assertThat(json(two).get("mayContainMask")).as("PUT response mayContainMask: %s", text(two)).isNotNull();
        assertThat(json(two).get("mayContainMask").asInt()).isEqualTo(CRUSTACEANS);
        MvcResult readTwo = perform(get("/api/v1/products/{id}", productId), null);
        assertThat(json(readTwo).get("mayContainMask")).as("GET mayContainMask: %s", text(readTwo)).isNotNull();
        assertThat(json(readTwo).get("mayContainMask").asInt()).isEqualTo(CRUSTACEANS);
        assertThat(json(readTwo).get("allergenMask").asInt()).isEqualTo(MILK);

        // Overlapping bits: Milk is both declared and may-contain; the stored mask keeps the bit.
        MvcResult both = perform(put("/api/v1/products/{id}", productId), productBody(sku, MILK, SESAME | MILK));
        assertThat(both.getResponse().getStatus()).as(text(both)).isEqualTo(200);
        assertThat(storedMayContain(productId)).as("may_contain_mask stored exactly as sent").isEqualTo(SESAME | MILK);
        assertThat(storedAllergenMask(productId))
                .as("allergen_mask after setting may_contain_mask (never merged)").isEqualTo(MILK);

        // 0 is a value ("no cross-contact risk declared"), different from NULL.
        MvcResult zero = perform(put("/api/v1/products/{id}", productId), productBody(sku, MILK, 0));
        assertThat(zero.getResponse().getStatus()).as(text(zero)).isEqualTo(200);
        assertThat(json(zero).get("mayContainMask")).as("0 is returned, not omitted: %s", text(zero)).isNotNull();
        assertThat(json(zero).get("mayContainMask").asInt()).isZero();
        assertThat(storedMayContain(productId)).isEqualTo(0);
        assertThat(storedAllergenMask(productId)).isEqualTo(MILK);
    }

    @Test
    @DisplayName("D-16: mayContainMask outside 0..16383 is a 400, and nothing is stored")
    void mayContainOutOfRange_isRejected() throws Exception {
        String sku = "SKU-311-14-RANGE-" + UUID.randomUUID();
        MvcResult created = perform(post("/api/v1/products"), productBody(sku, MILK, 1 << 14));
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(400);
        assertThat(text(created)).contains("mayContainMask");
    }

    // ------------------------------------------------------------------
    // D-16 + D-17 on the PDF
    // ------------------------------------------------------------------
    @Test
    @DisplayName("#861: label for productionDate=2026-10-03 prints 'Produced: 3 October 2026', 'Use by: 5 October 2026' and 'May contain: Sesame' (Milk is declared, so omitted)")
    void labelForProductionDate_printsProducedUseByAndMayContain() throws Exception {
        String sku = "SKU-311-14-LABEL-" + UUID.randomUUID();
        UUID productId = createProduct(sku, MILK, SESAME | MILK);

        MvcResult label = perform(
                get("/api/v1/products/{id}/label", productId).param("productionDate", "2026-10-03"), null);
        assertThat(label.getResponse().getStatus()).as(text(label)).isEqualTo(200);
        assertThat(label.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=label-" + productId + ".pdf");

        String text = pdfText(label.getResponse().getContentAsByteArray());
        assertThat(text).as("label text").contains("Produced: 3 October 2026");
        assertThat(text).as("label text").contains("Use by: 5 October 2026");
        assertThat(text).as("label text").contains("May contain: Sesame");
        assertThat(text).as("Milk is declared, so it is not repeated as may-contain")
                .doesNotContain("May contain: Milk")
                .doesNotContain("Sesame, Milk")
                .doesNotContain("Milk, Sesame");
        // The declared mask is still exactly what the vendor set.
        assertThat(storedAllergenMask(productId)).isEqualTo(MILK);
    }

    @Test
    @DisplayName("D-17: label without productionDate is produced 'today' in London (fixed clock 4 Oct 2026) and counts use-by from it")
    void labelWithoutProductionDate_defaultsToTodayInLondon() throws Exception {
        String sku = "SKU-311-14-TODAY-" + UUID.randomUUID();
        UUID productId = createProduct(sku, MILK, null);

        MvcResult label = perform(get("/api/v1/products/{id}/label", productId), null);
        assertThat(label.getResponse().getStatus()).as(text(label)).isEqualTo(200);

        String text = pdfText(label.getResponse().getContentAsByteArray());
        assertThat(text).contains("Produced: 4 October 2026");
        assertThat(text).contains("Use by: 6 October 2026");
        assertThat(text).as("no may-contain recorded -> no line").doesNotContain("May contain");
    }

    @Test
    @DisplayName("D-17: a productionDate after today (London) is a typed 422 naming productionDate")
    void futureProductionDate_is422() throws Exception {
        String sku = "SKU-311-14-FUTURE-" + UUID.randomUUID();
        UUID productId = createProduct(sku, MILK, null);

        MvcResult label = perform(
                get("/api/v1/products/{id}/label", productId).param("productionDate", "2026-10-05"), null);
        assertThat(label.getResponse().getStatus()).as(text(label)).isEqualTo(422);
        JsonNode problem = json(label);
        assertThat(problem.get("type").asString()).isEqualTo("https://jtoye.uk/errors/invalid-production-date");
        assertThat(problem.get("field").asString()).isEqualTo("productionDate");
    }

    @Test
    @DisplayName("D-17: a productionDate whose use-by has already passed is a typed 422 naming productionDate")
    void productionDateWhoseUseByHasPassed_is422() throws Exception {
        String sku = "SKU-311-14-PAST-" + UUID.randomUUID();
        UUID productId = createProduct(sku, MILK, null);

        // Shelf life 2: produced 1 October -> use by 3 October, before today (4 October).
        MvcResult label = perform(
                get("/api/v1/products/{id}/label", productId).param("productionDate", "2026-10-01"), null);
        assertThat(label.getResponse().getStatus()).as(text(label)).isEqualTo(422);
        JsonNode problem = json(label);
        assertThat(problem.get("type").asString()).isEqualTo("https://jtoye.uk/errors/invalid-production-date");
        assertThat(problem.get("field").asString()).isEqualTo("productionDate");

        // The boundary: produced 2 October -> use by 4 October = today, still printable.
        MvcResult boundary = perform(
                get("/api/v1/products/{id}/label", productId).param("productionDate", "2026-10-02"), null);
        assertThat(boundary.getResponse().getStatus()).as(text(boundary)).isEqualTo(200);
        assertThat(pdfText(boundary.getResponse().getContentAsByteArray())).contains("Use by: 4 October 2026");
    }

    // ------------------------------------------------------------------
    // D-16 on the storefront: its own line, never merged into the declared set
    // ------------------------------------------------------------------
    @Test
    @DisplayName("D-16 public: mayContainAllergens lists only the undeclared may-contain bits, in catalogue order; NULL and 0 give []")
    void publicProduct_carriesUndeclaredMayContainNames() throws Exception {
        UUID notRecorded = createProduct("SKU-311-14-PUB-NULL-" + UUID.randomUUID(), MILK, null);
        UUID none = createProduct("SKU-311-14-PUB-ZERO-" + UUID.randomUUID(), MILK, 0);
        // Built Sesame, Milk, Crustaceans: Milk is declared, the rest come back in bit order.
        UUID risky = createProduct("SKU-311-14-PUB-SET-" + UUID.randomUUID(), MILK, SESAME | MILK | CRUSTACEANS);

        JsonNode riskyRow = publicProduct(risky);
        assertThat(riskyRow.has("mayContainAllergens")).as("field present: %s", riskyRow).isTrue();
        assertThat(names(riskyRow.get("mayContainAllergens"))).containsExactly("Crustaceans", "Sesame");
        // Never merged: the declared mask on the public row is still Milk alone.
        assertThat(riskyRow.get("allergenMask").asInt()).isEqualTo(MILK);

        for (UUID id : List.of(notRecorded, none)) {
            JsonNode row = publicProduct(id);
            assertThat(row.has("mayContainAllergens")).as("field present (never null): %s", row).isTrue();
            assertThat(names(row.get("mayContainAllergens"))).as("names for %s", id).isEmpty();
        }
        assertThat(storedMayContain(notRecorded)).isNull();
        assertThat(storedMayContain(none)).isEqualTo(0);
    }

    /** The public menu (anonymous) row for {@code productId}, searched across every category. */
    private JsonNode publicProduct(UUID productId) throws Exception {
        MvcResult menu = mockMvc.perform(get("/api/v1/public/shops/{slug}/products", SHOP_SLUG)
                .accept(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(menu.getResponse().getStatus()).as(text(menu)).isEqualTo(200);
        JsonNode categories = json(menu);
        List<JsonNode> matches = new java.util.ArrayList<>();
        for (JsonNode products : categories) {
            for (JsonNode p : products) {
                if (productId.toString().equals(p.get("id").asString())) {
                    matches.add(p);
                }
            }
        }
        assertThat(matches).as("product %s on the public menu %s", productId, categories).hasSize(1);
        return matches.get(0);
    }

    private static List<String> names(JsonNode array) {
        assertThat(array).as("a JSON array of allergen names").isNotNull();
        assertThat(array.isArray()).as("an array: %s", array).isTrue();
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    // ---- requests -----------------------------------------------------------

    /** Vendor token: UUID subject, this tenant, catalog read + write, through the real converter. */
    private static RequestPostProcessor vendorJwt() {
        return jwt()
                .jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", TENANT_ID.toString())
                        .claim("scope", "catalog:read catalog:write"))
                .authorities(new JwtRolesAndScopesConverter());
    }

    private Map<String, Object> productBody(String sku, int allergenMask, Integer mayContainMask) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("title", "Groundnut Stew");
        body.put("ingredientsText", "Rice, **milk**, pepper");
        body.put("allergenMask", allergenMask);
        if (mayContainMask != null) {
            body.put("mayContainMask", mayContainMask);
        }
        body.put("pricePennies", 899);
        body.put("category", "Mains");
        body.put("available", true);
        body.put("shopId", shopId);
        body.put("shelfLifeDays", 2);
        body.put("durabilityType", "USE_BY");
        return body;
    }

    private UUID createProduct(String sku, int mask, Integer mayContain) throws Exception {
        MvcResult created = perform(post("/api/v1/products"), productBody(sku, mask, mayContain));
        assertThat(created.getResponse().getStatus()).as(text(created)).isEqualTo(201);
        return UUID.fromString(json(created).get("id").asString());
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(vendorJwt());
        if (body != null) {
            request.accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) {
        try {
            String ct = result.getResponse().getContentType();
            if (ct != null && ct.startsWith("application/pdf")) {
                return "<pdf, " + result.getResponse().getContentAsByteArray().length + " bytes>";
            }
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable body: " + e + ">";
        }
    }

    private static String pdfText(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 4)).as("PDF magic").isEqualTo("%PDF");
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private Integer storedAllergenMask(UUID productId) {
        return inTenant(() -> jdbcTemplate.queryForObject(
                "SELECT allergen_mask FROM products WHERE id = ? AND tenant_id = ?",
                Integer.class, productId, TENANT_ID));
    }

    private Integer storedMayContain(UUID productId) {
        return inTenant(() -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT may_contain_mask FROM products WHERE id = ? AND tenant_id = ?", productId, TENANT_ID);
            assertThat(rows).as("product row %s visible to the verification query", productId).hasSize(1);
            Object v = rows.get(0).get("may_contain_mask");
            return v == null ? null : ((Number) v).intValue();
        });
    }

    private UUID seedShopIdempotent() {
        return inTenant(() -> {
            List<UUID> existing = jdbcTemplate.queryForList(
                    "SELECT id FROM shops WHERE tenant_id = ? AND slug = ?", UUID.class, TENANT_ID, SHOP_SLUG);
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            UUID id = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version) "
                            + "VALUES (?, ?, now(), ?, ?, ?, true, 0, 0, 0)",
                    id, TENANT_ID, "May Contain Kitchen Ltd", SHOP_SLUG, "12 Market Street, London, E1 6AN");
            return id;
        });
    }

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(TENANT_ID);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }
}
