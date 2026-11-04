package uk.jtoye.core.storefront;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.CustomerJwtVerifier;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #820 (owner ruling 2026-11-04, persona close-out): when a shop cancels an order the customer is told the
 * reason CATEGORY in plain English and the shop's public phone, on the order/tracking page (the guest lookup
 * and the signed-in order history both feed it), as well as in the cancellation email.
 *
 * <p>Only the category, mapped here to words: never the enum code, and never any free text. Anything softer
 * than the vendor's own word is deliberate for SUSPECTED_FAKE, which the customer reads as "could not verify",
 * never as an accusation. Shown only for a CANCELLED order; a live order discloses neither.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class CancelledOrderCustomerDisclosureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;

    @MockitoBean CustomerJwtVerifier customerJwtVerifier;

    private static final String OWNER_TOKEN = "owner-customer-token-820";
    private static final String OWNER_EMAIL = "cancelled.persona820@example.com";
    private static final String SHOP_PHONE = "0121 496 0820";
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000008200");

    private UUID shop;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now()) ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "820 Cancel Disclosure Tenant");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        shop = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shops (id, tenant_id, created_at, name, slug, address, published, delivery_fee_pennies, "
                        + "minimum_order_pennies, version, hours_source, phone) "
                        + "VALUES (?, ?, now(), ?, ?, 'Test Address', true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN', ?)",
                shop, TENANT_ID, "820 Shop " + suffix, "shop-820-" + suffix, SHOP_PHONE);
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"))
                .when(customerJwtVerifier).verifiedEmail(anyString());
        doThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"))
                .when(customerJwtVerifier).verifiedEmail(isNull());
        doReturn(OWNER_EMAIL).when(customerJwtVerifier).verifiedEmail(OWNER_TOKEN);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("#820: a cancelled order's guest lookup carries the reason category in plain English and the shop's phone, and never the code")
    void guestLookupOfACancelledOrderDisclosesCategoryAndPhone() throws Exception {
        String number = seedOrder("CANCELLED", "CANNOT_FULFIL");

        MvcResult result = lookup(number);
        JsonNode body = json(result);

        assertThat(body.path("status").asString()).as(text(result)).isEqualTo("CANCELLED");
        assertThat(body.path("cancellationReason").asString()).as(text(result))
                .isEqualTo("The shop could not make or deliver this order.");
        assertThat(body.path("shopPhone").asString()).as(text(result)).isEqualTo(SHOP_PHONE);
        assertThat(result.getResponse().getContentAsString())
                .as("the enum code never reaches the customer").doesNotContain("CANNOT_FULFIL");
    }

    @Test
    @DisplayName("#820: each category has its own words; none says 'fake'; OTHER and CUSTOMER_REQUEST are mapped too")
    void everyCategoryIsMapped() throws Exception {
        Map<String, String> expected = Map.of(
                "SUSPECTED_FAKE", "The shop could not verify this order.",
                "CANNOT_FULFIL", "The shop could not make or deliver this order.",
                "CUSTOMER_REQUEST", "This order was cancelled at your request.",
                "OTHER", "The shop cancelled this order for another reason.");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String number = seedOrder("CANCELLED", entry.getKey());
            MvcResult result = lookup(number);
            JsonNode body = json(result);
            assertThat(body.path("cancellationReason").asString()).as(entry.getKey()).isEqualTo(entry.getValue());
            assertThat(result.getResponse().getContentAsString().toLowerCase(java.util.Locale.ROOT))
                    .as(entry.getKey()).doesNotContain("fake");
            assertThat(result.getResponse().getContentAsString()).as("the code itself never appears")
                    .doesNotContain(entry.getKey());
        }
    }

    @Test
    @DisplayName("#820: a cancelled order with no recorded reason carries the phone but no reason field at all")
    void noRecordedReasonIsNoField() throws Exception {
        String number = seedOrder("CANCELLED", null);

        JsonNode body = json(lookup(number));

        assertThat(body.has("cancellationReason")).as(body.toString()).isFalse();
        assertThat(body.path("shopPhone").asString()).isEqualTo(SHOP_PHONE);
    }

    @Test
    @DisplayName("#820: an order that is not cancelled discloses neither a reason nor the phone")
    void aLiveOrderDisclosesNeither() throws Exception {
        String number = seedOrder("CONFIRMED", null);

        JsonNode body = json(lookup(number));

        assertThat(body.has("cancellationReason")).as(body.toString()).isFalse();
        assertThat(body.has("shopPhone")).as(body.toString()).isFalse();
    }

    @Test
    @DisplayName("#820: the signed-in order history carries the same two fields for a cancelled order")
    void orderHistoryCarriesThem() throws Exception {
        String number = seedOrder("CANCELLED", "OTHER");

        MvcResult mine = mockMvc.perform(get("/api/v1/public/orders/mine").header("X-Customer-Token", OWNER_TOKEN))
                .andReturn();
        assertThat(mine.getResponse().getStatus()).as(text(mine)).isEqualTo(200);
        JsonNode row = null;
        for (JsonNode n : jsonMapper.readTree(mine.getResponse().getContentAsString()).path("content")) {
            if (number.equals(n.path("orderNumber").asString())) {
                row = n;
            }
        }
        assertThat(row).as("the order is in the history: " + text(mine)).isNotNull();
        assertThat(row.path("cancellationReason").asString()).isEqualTo("The shop cancelled this order for another reason.");
        assertThat(row.path("shopPhone").asString()).isEqualTo(SHOP_PHONE);
    }

    // ---- helpers ----

    private MvcResult lookup(String orderNumber) throws Exception {
        return mockMvc.perform(post("/api/v1/public/orders/" + orderNumber + "/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("email", OWNER_EMAIL))))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(text(result)).isEqualTo(200);
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String text(MvcResult result) throws Exception {
        return "HTTP " + result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private String seedOrder(String status, String cancelReasonCode) {
        UUID id = UUID.randomUUID();
        String number = "ORD-820-" + id.toString().substring(0, 12);
        TenantContext.set(TENANT_ID);
        try {
            jdbcTemplate.update(
                    "INSERT INTO orders (id, tenant_id, shop_id, order_number, status, customer_email, customer_name, "
                            + "total_amount_pennies, delivery_fee_pennies, subtotal_pennies, vat_rate, "
                            + "vat_amount_pennies, cancel_reason_code) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'Cancel Persona', 2700, 0, 2700, 'ZERO', 0, ?)",
                    id, TENANT_ID, shop, number, status, OWNER_EMAIL, cancelReasonCode);
        } finally {
            TenantContext.clear();
        }
        return number;
    }
}
