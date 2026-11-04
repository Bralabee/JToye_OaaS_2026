package uk.jtoye.core.webhook;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.gdpr.DsarSubjectDigest;
import uk.jtoye.core.gdpr.GdprService;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.testsupport.IntegrationTestSupport;
import uk.jtoye.core.testsupport.NoScheduledTriggersTestConfig;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #864 (P2-RAV-08): an order webhook carries what a receiver needs to build a row (lines, totals, fulfilment,
 * the customer), not seven ids and a status.
 *
 * <p>Driven through the real {@link WebhookFanoutListener} over Postgres with Flyway applied: the order, its
 * lines and the subscription are real rows, and the assertions read the {@code webhook_delivery.payload} that the
 * worker would sign and POST, so a field that the serializer drops or the assembler forgets fails here.
 *
 * <p>The privacy half: the payload now holds the customer's contact details (a subscription is GROUP_ADMIN-only
 * and tenant-wide, so the recipient is entitled to them; the STAFF field-minimisation ruling of 2026-11-03 does
 * not reach a GROUP_ADMIN). A stored copy must not outlive an Article 17 erasure, so erasure is proven to scrub
 * the stored payloads of the erased subject's orders and to leave another customer's alone.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@Import(NoScheduledTriggersTestConfig.class)
class OrderWebhookPayloadIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        registry.add("webhook.delivery.interval-ms", () -> "3600000");
        registry.add("webhook.delivery.retention-interval-ms", () -> "3600000");
    }

    @Autowired WebhookFanoutListener listener;
    @Autowired OrderRepository orderRepository;
    @Autowired GdprService gdprService;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    UUID tenantId;
    UUID shopId;
    UUID productId;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jdbc.update("DELETE FROM webhook_delivery");
        jdbc.update("DELETE FROM webhook_subscription");
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "ow-" + tenantId);
        jdbc.update("INSERT INTO shops (id, tenant_id, name, slug, delivery_fee_pennies) VALUES (?, ?, ?, ?, 250)",
                shopId, tenantId, "Mama's Kitchen", "mamas-" + tenantId.toString().substring(0, 8));
        jdbc.update("INSERT INTO products (id, tenant_id, sku, title, ingredients_text) VALUES (?, ?, ?, ?, ?)",
                productId, tenantId, "JOL-1", "Jollof rice", "Rice, tomato");
        jdbc.update("INSERT INTO webhook_subscription (id, tenant_id, target_url, event_types, signing_secret, status) "
                        + "VALUES (?, ?, ?, '{ORDER_STATE_CHANGED}'::text[], 'sig-value-ow', 'ACTIVE')",
                UUID.randomUUID(), tenantId, "https://hooks.example.com/jtoye");
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Order seedOrder(String number, String name, String email, FulfilmentType type) {
        Order order = new Order();
        order.setTenantId(tenantId);
        order.setShopId(shopId);
        order.setOrderNumber(number);
        order.setCustomerName(name);
        order.setCustomerEmail(email);
        order.setCustomerPhone("+447700900111");
        order.setFulfilmentType(type);
        order.setDeliveryFeePennies(type == FulfilmentType.DELIVERY ? 250L : 0L);
        if (type == FulfilmentType.DELIVERY) {
            order.setAddressLine1("12 Peckham High Street");
            order.setAddressCity("London");
            order.setAddressPostcode("SE15 5EB");
        }
        OrderItem item = new OrderItem(productId, 2, 899L);
        item.setTenantId(tenantId);
        item.setProductName("Jollof rice");
        order.addItem(item);
        order.calculateTotal();
        order.setStatus(OrderStatus.READY);
        return orderRepository.saveAndFlush(order);
    }

    private JsonNode fanOut(Order order) {
        listener.onOrderState(new OrderStateChangeEvent(order.getId(), tenantId, order.getOrderNumber(),
                OrderStatus.PREPARING, OrderStatus.READY, OffsetDateTime.now(), shopId));
        return json.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_delivery WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId));
    }

    @Test
    @DisplayName("#864: order.ready carries the lines, totals, fulfilment type and customer, beside the original seven")
    void orderWebhookCarriesTheOrderSummary() {
        Order order = seedOrder("JT-864-1", "Ravi Shah", "ravi@example.com", FulfilmentType.DELIVERY);

        JsonNode envelope = fanOut(order);
        JsonNode data = envelope.path("data");

        // The original seven fields are unchanged, so an existing receiver keeps working.
        assertThat(data.path("orderId").asString()).isEqualTo(order.getId().toString());
        assertThat(data.path("orderNumber").asString()).isEqualTo("JT-864-1");
        assertThat(data.path("previousStatus").asString()).isEqualTo("PREPARING");
        assertThat(data.path("newStatus").asString()).isEqualTo("READY");
        assertThat(data.path("shopId").asString()).isEqualTo(shopId.toString());
        assertThat(data.path("tenantId").asString()).isEqualTo(tenantId.toString());
        assertThat(data.has("timestamp")).isTrue();

        // The additions.
        assertThat(data.path("dataVersion").asInt()).isEqualTo(2);
        assertThat(data.path("fulfilmentType").asString()).isEqualTo("DELIVERY");
        assertThat(data.path("items")).hasSize(1);
        JsonNode line = data.path("items").get(0);
        assertThat(line.path("name").asString()).isEqualTo("Jollof rice");
        assertThat(line.path("quantity").asInt()).isEqualTo(2);
        assertThat(line.path("unitPricePennies").asLong()).isEqualTo(899L);
        assertThat(line.path("totalPricePennies").asLong()).isEqualTo(1798L);
        assertThat(data.path("subtotalPennies").asLong()).isEqualTo(1798L);
        assertThat(data.path("deliveryFeePennies").asLong()).isEqualTo(250L);
        assertThat(data.path("totalPennies").asLong()).isEqualTo(2048L);
        assertThat(data.path("customer").path("name").asString()).isEqualTo("Ravi Shah");
        assertThat(data.path("customer").path("email").asString()).isEqualTo("ravi@example.com");
        assertThat(data.path("customer").path("phone").asString()).isEqualTo("+447700900111");
        assertThat(data.path("deliveryAddress").path("postcode").asString()).isEqualTo("SE15 5EB");
    }

    @Test
    @DisplayName("#864: a collection order carries no delivery address")
    void collectionOrderHasNoDeliveryAddress() {
        Order order = seedOrder("JT-864-2", "Ade Obi", "ade@example.com", FulfilmentType.COLLECTION);

        JsonNode data = fanOut(order).path("data");

        assertThat(data.path("fulfilmentType").asString()).isEqualTo("COLLECTION");
        assertThat(data.has("deliveryAddress")).isFalse();
        assertThat(data.path("deliveryFeePennies").asLong()).isZero();
    }

    @Test
    @DisplayName("#864: an event whose order cannot be read is still delivered as the thin original, never dropped")
    void unreadableOrderFallsBackToTheThinEvent() {
        UUID missingOrder = UUID.randomUUID();
        listener.onOrderState(new OrderStateChangeEvent(missingOrder, tenantId, "JT-GONE",
                OrderStatus.PREPARING, OrderStatus.READY, OffsetDateTime.now(), shopId));

        JsonNode data = json.readTree(jdbc.queryForObject(
                "SELECT payload FROM webhook_delivery WHERE tenant_id = ?", String.class, tenantId)).path("data");

        assertThat(data.path("orderNumber").asString()).isEqualTo("JT-GONE");
        assertThat(data.has("items")).isFalse();
        assertThat(data.has("customer")).isFalse();
        assertThat(data.has("dataVersion")).isFalse();
    }

    @Test
    @DisplayName("#864 / Article 17: erasing a customer scrubs their contact details out of the stored webhook "
            + "payloads, keeps the order facts, and leaves another customer's payload alone")
    void erasureScrubsStoredWebhookPayloads() {
        Order erased = seedOrder("JT-864-3", "Erase Me", "erase.me@example.com", FulfilmentType.DELIVERY);
        Order kept = seedOrder("JT-864-4", "Keep Me", "keep.me@example.com", FulfilmentType.DELIVERY);
        fanOut(erased);
        fanOut(kept);
        assertThat(storedPayloadOf(erased)).as("pre-condition: the stored payload holds the address")
                .contains("erase.me@example.com").contains("SE15 5EB");

        gdprService.eraseSubjectByDigest(tenantId, DsarSubjectDigest.of("erase.me@example.com"));

        String scrubbed = storedPayloadOf(erased);
        assertThat(scrubbed).doesNotContain("erase.me@example.com").doesNotContain("Erase Me")
                .doesNotContain("SE15 5EB").doesNotContain("+447700900111");
        JsonNode data = json.readTree(scrubbed).path("data");
        assertThat(data.path("orderNumber").asString()).as("order facts survive").isEqualTo("JT-864-3");
        assertThat(data.path("items")).hasSize(1);
        assertThat(data.path("totalPennies").asLong()).isEqualTo(2048L);

        assertThat(storedPayloadOf(kept)).as("another customer's payload is untouched")
                .contains("keep.me@example.com").contains("SE15 5EB");
    }

    private String storedPayloadOf(Order order) {
        return jdbc.queryForObject("SELECT payload FROM webhook_delivery WHERE tenant_id = ? "
                        + "AND payload::jsonb #>> '{data,orderId}' = ?",
                String.class, tenantId, order.getId().toString());
    }
}
