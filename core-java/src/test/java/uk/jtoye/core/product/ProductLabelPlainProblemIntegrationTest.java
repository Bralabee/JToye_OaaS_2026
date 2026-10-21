package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.JwtRolesAndScopesConverter;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #960 (swarm SW-ADE-03): the allergen label could not be printed, the 422 named database columns
 * and a UUID, and the product form had no field to supply what was missing.
 *
 * <p>Driven through the real filter chain and a real Postgres: a product is created exactly as the
 * dashboard form posts it, the label is requested, and the RFC 7807 answer is read back. The path that
 * works is proven too: the same product given a shelf life and a durability type through the edit route
 * downloads a PDF, and so does a product created with both from the start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ProductLabelPlainProblemIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ShopService shopService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static final java.util.regex.Pattern UUID_IN_TEXT =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-");

    /**
     * The SW-ADE-03 repro, the text half. RED on the unfixed tree: the detail is "Cannot generate PPDS label
     * for product ea66e02b-...: missing shelf life (shelf_life_days), durability type (durability_type)".
     */
    @Test
    void the422DetailIsPlainWordsWithNoUuidAndNoColumnNames() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "NOLIFE-" + f.tag, null, null);

        MvcResult res = mockMvc.perform(get("/api/v1/products/" + id + "/label").with(adminJwt(f.tenant)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/incomplete-label-data"))
                .andReturn();

        String detail = detail(res);
        assertThat(UUID_IN_TEXT.matcher(detail).find()).as("no UUID in the vendor-facing text: " + detail).isFalse();
        assertThat(detail)
                .doesNotContain("shelf_life_days")
                .doesNotContain("durability_type")
                .doesNotContain("shop_id")
                .doesNotContain(id.toString())
                .doesNotContain("PPDS")
                .contains("Add a shelf life (in days) and choose use-by or best-before on the product");
    }

    /** The machine-readable half: the missing items as field names, and the product id as its own member. */
    @Test
    void the422CarriesTheMissingFieldsAsAMachineReadableMember() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "NOLIFE2-" + f.tag, null, null);

        mockMvc.perform(get("/api/v1/products/" + id + "/label").with(adminJwt(f.tenant)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields.length()").value(2))
                .andExpect(jsonPath("$.missingFields[0]").value("shelfLifeDays"))
                .andExpect(jsonPath("$.missingFields[1]").value("durabilityType"))
                .andExpect(jsonPath("$.productId").value(id.toString()));
    }

    /** The repro's second half and its acceptance: supply the two fields through the edit route, get a PDF. */
    @Test
    void afterTheEditFormSuppliesShelfLifeAndDurabilityTheLabelDownloads() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "FIXME-" + f.tag, null, null);

        mockMvc.perform(put("/api/v1/products/" + id).with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson("FIXME-" + f.tag, f.shop, 3, "USE_BY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelfLifeDays").value(3))
                .andExpect(jsonPath("$.durabilityType").value("USE_BY"));

        assertPdf(mockMvc.perform(get("/api/v1/products/" + id + "/label").with(adminJwt(f.tenant)))
                .andExpect(status().isOk()).andReturn());
    }

    /** The create form posts both fields: the product it creates is labellable at once, and BEST_BEFORE prints. */
    @Test
    void aProductCreatedThroughTheFormWithBothFieldsDownloadsItsLabel() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "FORMED-" + f.tag, 2, "BEST_BEFORE");

        MvcResult res = mockMvc.perform(get("/api/v1/products/" + id + "/label").with(adminJwt(f.tenant)))
                .andExpect(status().isOk()).andReturn();
        assertPdf(res);
        mockMvc.perform(get("/api/v1/products/" + id).with(adminJwt(f.tenant)))
                .andExpect(jsonPath("$.shelfLifeDays").value(2))
                .andExpect(jsonPath("$.durabilityType").value("BEST_BEFORE"));
    }

    /** An edit that does not mention the two fields keeps them (the form omits them when left blank). */
    @Test
    void anEditThatOmitsShelfLifeAndDurabilityKeepsTheStoredValues() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "KEEP-" + f.tag, 5, "USE_BY");

        mockMvc.perform(put("/api/v1/products/" + id).with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson("KEEP-" + f.tag, f.shop, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shelfLifeDays").value(5))
                .andExpect(jsonPath("$.durabilityType").value("USE_BY"));
    }

    /** Validation: both fields are checked before anything is stored. 99999 days is RED on the unfixed tree. */
    @Test
    void shelfLifeAndDurabilityAreValidatedOnCreate() throws Exception {
        Fixture f = seed();
        for (String bad : new String[] {
                productJson("BAD1-" + f.tag, f.shop, 99999, "USE_BY"),
                productJson("BAD2-" + f.tag, f.shop, -1, "USE_BY"),
                productJson("BAD3-" + f.tag, f.shop, 3, "FOREVER")}) {
            mockMvc.perform(post("/api/v1/products").with(adminJwt(f.tenant))
                            .contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ? AND sku LIKE 'BAD_-%'",
                Long.class, f.tenant)).as("nothing stored for a refused create").isZero();
    }

    /** A shop without an address: still plain words, still no column names, field list says shopAddress. */
    @Test
    void aShopWithoutAnAddressIsNamedInPlainWords() throws Exception {
        Fixture f = seed();
        UUID id = createProduct(f, "NOADDR-" + f.tag, 3, "USE_BY");
        jdbc.update("UPDATE shops SET address = '' WHERE id = ?", f.shop);

        MvcResult res = mockMvc.perform(get("/api/v1/products/" + id + "/label").with(adminJwt(f.tenant)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.missingFields.length()").value(1))
                .andExpect(jsonPath("$.missingFields[0]").value("shopAddress"))
                .andReturn();
        assertThat(detail(res)).contains("Add the shop's business address in the shop's settings")
                .doesNotContain("shop_id").doesNotContain(id.toString());
    }

    // ---------------------------------------------------------------------

    private record Fixture(UUID tenant, UUID shop, String tag) { }

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Label Problem Tenant " + tenant);
        String tag = tenant.toString().substring(0, 8);
        UUID shop = asRealmAdmin(tenant, () -> shopService.createShop(shopRequest("Label Shop " + tag)).getId());
        return new Fixture(tenant, shop, tag);
    }

    /** Creates through POST /api/v1/products, as the dashboard form does, and returns the new id. */
    private UUID createProduct(Fixture f, String sku, Integer shelfLifeDays, String durabilityType) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/products").with(adminJwt(f.tenant))
                        .contentType("application/json")
                        .content(productJson(sku, f.shop, shelfLifeDays, durabilityType)))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString()).get("id").asString());
    }

    private static String productJson(String sku, UUID shop, Integer shelfLifeDays, String durabilityType) {
        return "{\"sku\":\"" + sku + "\",\"title\":\"Dish " + sku + "\","
                + "\"ingredientsText\":\"Rice, **fish**\",\"allergenMask\":64,\"pricePennies\":1100,"
                + "\"shopId\":\"" + shop + "\""
                + (shelfLifeDays == null ? "" : ",\"shelfLifeDays\":" + shelfLifeDays)
                + (durabilityType == null ? "" : ",\"durabilityType\":\"" + durabilityType + "\"") + "}";
    }

    private static void assertPdf(MvcResult res) throws Exception {
        assertThat(res.getResponse().getContentType()).isEqualTo("application/pdf");
        byte[] pdf = res.getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    private static String detail(MvcResult res) throws Exception {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(res.getResponse().getContentAsString()).path("detail").asString();
    }

    private static CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private <T> T asRealmAdmin(UUID tenant, Supplier<T> action) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(UUID.randomUUID().toString())
                .claim("email", "seed-admin@example.com")
                .build();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_admin"));
        SecurityContext fresh = SecurityContextHolder.createEmptyContext();
        fresh.setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        SecurityContextHolder.setContext(fresh);
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenant_id", tenant.toString())
                        .claim("email", "operator@example.com")
                        .claim("scope", "catalog:read catalog:write")
                        .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new JwtRolesAndScopesConverter());
    }
}
