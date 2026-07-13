package uk.jtoye.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.finance.dto.CreateTransactionRequest;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.payment.RefundReason;
import uk.jtoye.core.payment.StripeRefundClient;
import uk.jtoye.core.payment.dto.CreateRefundRequest;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.dto.CreateAnnouncementRequest;
import uk.jtoye.core.shop.dto.CreatePromotionRequest;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Location-header dereferencability contract (issue #97 [P2-6]).
 *
 * <p>WebConfig invisibly prefixes seven controller packages with
 * {@code /api/v1}; six POST endpoints used to hand-build their Location
 * headers WITHOUT that prefix, so the URI a client was told to follow 404'd.
 * Each test here performs the full round trip the RFC expects:
 * POST → 201 + Location → GET &lt;Location&gt; → 200 with the created entity.
 *
 * <p>The refund case (#97 tail) covers the seventh POST endpoint —
 * {@code RefundController} hard-codes its own {@code /api/v1} prefix (BL-01)
 * and, until this issue, had no single-resource GET for its Location URI to
 * dereference to. Stripe is replaced by a {@link MockitoBean} so the round
 * trip stays hermetic.
 *
 * <p>The static convention side of the same bug class is guarded by
 * {@code ApiPrefixConventionTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class LocationHeaderContractTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private OrderRepository orderRepository;

    // Hermetic Stripe: RefundService calls stripeRefundClient.create() before
    // returning; the refund round trip must not leave the test JVM.
    @MockitoBean
    private StripeRefundClient stripeRefundClient;

    private UUID testTenantId;

    @BeforeEach
    void setup() {
        testTenantId = UUID.randomUUID();
        String uniqueTenantName = "Test Tenant " + testTenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name) VALUES (?, ?)",
                testTenantId, uniqueTenantName);
    }

    /**
     * POSTs the body to the given path, asserts 201 + Location containing the
     * expected versioned prefix, then GETs the Location and asserts 200 with
     * the same entity id — i.e. the Location actually dereferences.
     */
    private void assertLocationDereferences(String postPath, Object body, String expectedPathFragment)
            throws Exception {
        MvcResult created = mockMvc.perform(withTenant(post(postPath))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn();

        String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location)
                .as("Location must point at the real (WebConfig /api/v1-prefixed) resource path")
                .contains(expectedPathFragment + "/");

        String createdId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();
        assertThat(location).endsWith("/" + createdId);

        mockMvc.perform(withTenant(get(URI.create(location))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId));
    }

    private MockHttpServletRequestBuilder withTenant(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Tenant-ID", testTenantId.toString());
    }

    /** Creates a shop via the API and returns its id (promotions/announcements need a shop FK). */
    private UUID createShop() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Location Contract Shop " + UUID.randomUUID());
        request.setAddress("1 Contract Way");
        MvcResult result = mockMvc.perform(withTenant(post("/api/v1/shops"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    @Test
    @WithMockUser
    void shopCreateLocationDereferences() throws Exception {
        CreateShopRequest request = new CreateShopRequest();
        request.setName("Deref Shop " + UUID.randomUUID());
        request.setAddress("42 Location Lane");

        assertLocationDereferences("/api/v1/shops", request, "/api/v1/shops");
    }

    @Test
    // issue #206 [AI-4]: product create is now gated on SCOPE_catalog:write. The mock user
    // keeps ROLE_USER (authenticated read of the dereferenced Location) and gains the write
    // scope so the POST reaches the handler (mirrors an operator token, which core-api
    // default-grants catalog:write).
    @WithMockUser(authorities = {"ROLE_USER", "SCOPE_catalog:write"})
    void productCreateLocationDereferences() throws Exception {
        CreateProductRequest request = new CreateProductRequest();
        request.setSku("DEREF-" + UUID.randomUUID());
        request.setTitle("Deref Yam 5kg");
        request.setIngredientsText("Yam");
        request.setAllergenMask(0);
        request.setPricePennies(999L);

        assertLocationDereferences("/api/v1/products", request, "/api/v1/products");
    }

    @Test
    @WithMockUser
    void customerCreateLocationDereferences() throws Exception {
        var request = new uk.jtoye.core.customer.CustomerController.CreateCustomerRequest(
                "Deref Customer",
                "deref-" + UUID.randomUUID() + "@example.com",
                "+441234567890",
                0
        );

        assertLocationDereferences("/api/v1/customers", request, "/api/v1/customers");
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: finance endpoints require the admin realm role
    void financialTransactionCreateLocationDereferences() throws Exception {
        CreateTransactionRequest request = new CreateTransactionRequest(
                10000L,
                VatRate.STANDARD,
                "Location contract transaction"
        );

        assertLocationDereferences("/api/v1/financial-transactions", request, "/api/v1/financial-transactions");
    }

    @Test
    @WithMockUser
    void promotionCreateLocationDereferences() throws Exception {
        CreatePromotionRequest request = new CreatePromotionRequest();
        request.setLabel("Deref Promo");
        request.setDiscountPercent(10);
        request.setValidFrom(OffsetDateTime.now());
        request.setValidUntil(OffsetDateTime.now().plusDays(7));
        request.setShopId(createShop());

        assertLocationDereferences("/api/v1/promotions", request, "/api/v1/promotions");
    }

    @Test
    @WithMockUser
    void announcementCreateLocationDereferences() throws Exception {
        CreateAnnouncementRequest request = new CreateAnnouncementRequest();
        request.setTitle("Deref Announcement");
        request.setBody("The Location header now resolves.");
        request.setShopId(createShop());

        assertLocationDereferences("/api/v1/announcements", request, "/api/v1/announcements");
    }

    @Test
    @WithMockUser(roles = "admin")  // issue #83 P1-1: refunds require the admin realm role
    void refundCreateLocationDereferences() throws Exception {
        UUID orderId = seedRefundableOrder();

        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_deref_" + UUID.randomUUID().toString().substring(0, 8));
        stripeRefund.setStatus("succeeded");
        when(stripeRefundClient.create(any(), any())).thenReturn(stripeRefund);

        CreateRefundRequest request = new CreateRefundRequest(
                500L, RefundReason.REQUESTED_BY_CUSTOMER, "Location contract refund");

        // POST /orders/{id}/refund → 201 + Location /orders/{id}/refunds/{refundId}
        // → GET <Location> → 200. The GET side is the #97-tail endpoint.
        assertLocationDereferences(
                "/api/v1/orders/" + orderId + "/refund",
                request,
                "/api/v1/orders/" + orderId + "/refunds");
    }

    /**
     * Seeds a shop + CONFIRMED/CAPTURED order with a Stripe payment reference —
     * the minimum RefundService accepts. Repository saves run as the
     * Testcontainers bootstrap superuser, so RLS does not block seeding.
     */
    private UUID seedRefundableOrder() {
        TenantContext.set(testTenantId);
        try {
            Shop shop = new Shop();
            shop.setTenantId(testTenantId);
            shop.setName("Refund Contract Shop");
            shop.setSlug("refund-contract-" + UUID.randomUUID().toString().substring(0, 8));
            shop.setAddress("1 Contract Way");
            Shop savedShop = shopRepository.save(shop);

            Order order = new Order();
            order.setTenantId(testTenantId);
            order.setShopId(savedShop.getId());
            order.setOrderNumber("ORD-DEREF-" + UUID.randomUUID().toString().substring(0, 8));
            order.setStatus(OrderStatus.CONFIRMED);
            order.setPaymentStatus(PaymentStatus.CAPTURED);
            order.setPaymentReference("pi_deref_" + UUID.randomUUID().toString().substring(0, 8));
            order.setCustomerEmail("deref-refund@test.local");
            order.setCustomerName("Deref Refund Customer");
            order.setTotalAmountPennies(1000L);
            order.setSubtotalPennies(1000L);
            return orderRepository.save(order).getId();
        } finally {
            TenantContext.clear();
        }
    }
}
