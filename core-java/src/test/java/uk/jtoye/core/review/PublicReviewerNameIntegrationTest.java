package uk.jtoye.core.review;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 37-35 (RWO-044 / UXT-044, T-37-85): a public review names its author as "First L.", never by
 * the full name the customer typed at checkout.
 *
 * <p>{@code ReviewService.createReview} stores {@code order.getCustomerName()} on the review and the
 * public list returned it as is, so every storefront review published a customer's full name to the
 * internet. The display form is derived at READ time by {@link ReviewerDisplayName}; the stored name is
 * kept, because it is the subject's data that an Article 17 erasure scrubs (V67) and an Article 15
 * export returns, and neither may be answered from a truncated copy.
 *
 * <p>The surname is asserted absent from the WHOLE response body, not just from {@code customerName},
 * so a second field carrying it would fail too. Falsifiability: on the unfixed tree the POST and list
 * arms fail on that assertion; the stored-name arm passes on both trees and is the control that stops
 * "store the short form" from passing as a fix. Real Postgres (Testcontainers), real endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class PublicReviewerNameIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    private UUID tenant;
    private UUID shop;
    private String slug;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "37-35 reviewer-name tenant " + tenant);
        shop = UUID.randomUUID();
        slug = "reviews-3735-" + shop.toString().substring(0, 8);
        jdbc.update("INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, "
                        + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                        + "VALUES (?, ?, now(), ?, ?, '1 Test Street, London', true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                shop, tenant, "37-35 Reviews Shop", slug);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("UXT-044: the review a customer posts comes back as 'Grace P.' and the response never names 'Pemberton'")
    void postedReview_namesTheAuthorAsFirstAndInitial() throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@example.com";
        UUID order = seedCompletedOrder(email, "Grace Pemberton");

        MvcResult created = postReview(email, order);
        String body = created.getResponse().getContentAsString();

        assertThat(created.getResponse().getStatus()).as(body).isEqualTo(201);
        assertThat(jsonMapper.readTree(body).path("customerName").asString()).as(body).isEqualTo("Grace P.");
        assertThat(body).as("no field of the response carries the surname").doesNotContain("Pemberton");
    }

    @Test
    @DisplayName("UXT-044: the public reviews list names each author as first name plus initial, and the whole body is free of the surnames")
    void publicList_namesAuthorsAsFirstAndInitial() throws Exception {
        String grace = "grace-" + UUID.randomUUID() + "@example.com";
        String mary = "mary-" + UUID.randomUUID() + "@example.com";
        assertThat(postReview(grace, seedCompletedOrder(grace, "Grace Ann Pemberton")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(postReview(mary, seedCompletedOrder(mary, "Mary-Jane O'Neil")).getResponse().getStatus())
                .isEqualTo(201);

        MvcResult list = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/reviews")).andReturn();
        String body = list.getResponse().getContentAsString();

        assertThat(list.getResponse().getStatus()).as(body).isEqualTo(200);
        JsonNode content = jsonMapper.readTree(body).path("content");
        assertThat(content.size()).as(body).isEqualTo(2);
        assertThat(content.findValuesAsString("customerName")).as(body)
                .containsExactlyInAnyOrder("Grace P.", "Mary-Jane O.");
        assertThat(body).as("the whole public body is free of every surname")
                .doesNotContain("Pemberton").doesNotContain("O'Neil").doesNotContain("Ann");
    }

    @Test
    @DisplayName("UXT-044: a review whose order carried no name lists customerName null, so the storefront shows 'Customer'")
    void publicList_blankName_isNull() throws Exception {
        String email = "anon-" + UUID.randomUUID() + "@example.com";
        assertThat(postReview(email, seedCompletedOrder(email, "   ")).getResponse().getStatus()).isEqualTo(201);

        MvcResult list = mockMvc.perform(get("/api/v1/public/shops/" + slug + "/reviews")).andReturn();
        String body = list.getResponse().getContentAsString();

        JsonNode first = jsonMapper.readTree(body).path("content").get(0);
        assertThat(first.path("customerName").isNull()).as(body).isTrue();
    }

    @Test
    @DisplayName("KEPT: the stored review still holds the full name, for Article 17 erasure and Article 15 export")
    void storedReview_keepsTheFullName() throws Exception {
        String email = "grace-" + UUID.randomUUID() + "@example.com";
        UUID order = seedCompletedOrder(email, "Grace Pemberton");
        assertThat(postReview(email, order).getResponse().getStatus()).isEqualTo(201);

        String stored = inTenant(() -> jdbc.queryForObject(
                "SELECT customer_name FROM reviews WHERE tenant_id = ? AND order_id = ?", String.class, tenant, order));
        assertThat(stored).isEqualTo("Grace Pemberton");
    }

    private MvcResult postReview(String email, UUID orderId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("foodRating", 5);
        body.put("deliveryRating", 4);
        body.put("comment", "Lovely jollof");
        return mockMvc.perform(post("/api/v1/public/shops/" + slug + "/reviews")
                        .param("email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)))
                .andReturn();
    }

    private UUID seedCompletedOrder(String email, String customerName) {
        return inTenant(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_email, customer_name, "
                            + "total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, vat_amount_pennies) "
                            + "VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?, 1000, 0, 1000, 'ZERO', 0)",
                    id, tenant, shop, "ORD-" + id.toString().substring(0, 8), email, customerName);
            return id;
        });
    }

    private <T> T inTenant(java.util.function.Supplier<T> read) {
        TenantContext.set(tenant);
        try {
            return read.get();
        } finally {
            TenantContext.clear();
        }
    }
}
