package uk.jtoye.core.order;

import org.junit.jupiter.api.AfterEach;
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
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.storefront.PublicStorefrontService;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #825 (owner ruling 2026-11-04, persona close-out): each shop has a default preparation time (minutes,
 * nullable); the order confirmation, tracking page and kitchen ticket show an ESTIMATED ready-by time built
 * from it. A shop with no prep time shows nothing.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@uk.jtoye.core.testsupport.AsSystemHarness
class OrderReadyByEstimateIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired PublicStorefrontService publicStorefrontService;
    @Autowired OrderService orderService;
    @Autowired ShopService shopService;
    @Autowired JdbcTemplate jdbcTemplate;

    private record Fixture(UUID tenantId, UUID shopId, String slug, UUID productId) {
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void storefrontConfirmationAndTrackingCarryAnEstimateBuiltFromThePrepTime() {
        Fixture f = seed(25);
        OffsetDateTime before = OffsetDateTime.now();
        GuestOrderConfirmation confirmation = placeGuestOrder(f, "track-825@example.com");
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(confirmation.getEstimatedReadyAt()).as("confirmation").isNotNull();
        assertThat(confirmation.getEstimatedReadyAt()).isBetween(before.plusMinutes(25), after.plusMinutes(25));

        PublicOrderStatus tracked = publicStorefrontService.trackOrder(confirmation.getOrderNumber(), "track-825@example.com");
        assertThat(tracked.getEstimatedReadyAt()).as("tracking page").isNotNull();
        assertThat(Duration.between(confirmation.getEstimatedReadyAt(), tracked.getEstimatedReadyAt()).abs())
                .as("tracking shows the stored snapshot, not a recomputation")
                .isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void aShopWithNoPrepTimeShowsNoEstimateAnywhere() {
        Fixture f = seed(null);
        GuestOrderConfirmation confirmation = placeGuestOrder(f, "none-825@example.com");

        assertThat(confirmation.getEstimatedReadyAt()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT estimated_ready_at FROM orders WHERE order_number = ?",
                OffsetDateTime.class, confirmation.getOrderNumber())).isNull();
        assertThat(publicStorefrontService.trackOrder(confirmation.getOrderNumber(), "none-825@example.com")
                .getEstimatedReadyAt()).isNull();
    }

    @Test
    void acceptingTheOrderRestampsTheEstimateFromAcceptanceAndTheKitchenTicketCarriesIt() throws Exception {
        Fixture f = seed(20);
        GuestOrderConfirmation confirmation = placeGuestOrder(f, "accept-825@example.com");
        UUID orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", UUID.class, confirmation.getOrderNumber());
        OffsetDateTime placementEstimate = confirmation.getEstimatedReadyAt();
        Thread.sleep(50); // the restamp must be observably later than the placement estimate

        OffsetDateTime beforeAccept = OffsetDateTime.now();
        OrderDetailDto detail;
        TenantContext.set(f.tenantId());
        try {
            detail = SystemPrincipal.asSystem(() -> {
                orderService.confirmOrder(orderId);
                return orderService.getOrderDetailById(orderId).orElseThrow();
            });
        } finally {
            TenantContext.clear();
        }
        OffsetDateTime afterAccept = OffsetDateTime.now();

        assertThat(detail.getEstimatedReadyAt()).as("kitchen ticket (order detail)").isNotNull();
        assertThat(detail.getEstimatedReadyAt()).isAfter(placementEstimate);
        assertThat(detail.getEstimatedReadyAt()).isBetween(beforeAccept.plusMinutes(20), afterAccept.plusMinutes(20));
    }

    @Test
    void aVendorKeyedOrderGetsTheSameEstimate() {
        Fixture f = seed(15);
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(f.productId());
        item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(f.shopId());
        request.setCustomerName("Phone Order");
        request.setCustomerEmail("vendor-825@example.com");
        request.setCustomerPhone("07700900825");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(item));

        OffsetDateTime before = OffsetDateTime.now();
        TenantContext.set(f.tenantId());
        try {
            UUID id = SystemPrincipal.asSystem(() -> orderService.createOrder(request).getId());
            OffsetDateTime estimate = jdbcTemplate.queryForObject(
                    "SELECT estimated_ready_at FROM orders WHERE id = ?", OffsetDateTime.class, id);
            assertThat(estimate).isNotNull().isAfter(before.plusMinutes(15).minusSeconds(1));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void theShopSettingIsSetAndClearedThroughTheShopUpdate() {
        Fixture f = seed(null);
        TenantContext.set(f.tenantId());
        try {
            CreateShopRequest set = new CreateShopRequest();
            set.setName("825 Shop");
            set.setPrepTimeMinutes(40);
            ShopDto saved = SystemPrincipal.asSystem(() -> shopService.updateShop(f.shopId(), set));
            assertThat(saved.getPrepTimeMinutes()).isEqualTo(40);

            CreateShopRequest unrelated = new CreateShopRequest();
            unrelated.setName("825 Shop");
            ShopDto kept = SystemPrincipal.asSystem(() -> shopService.updateShop(f.shopId(), unrelated));
            assertThat(kept.getPrepTimeMinutes()).as("absent keeps the stored value").isEqualTo(40);

            CreateShopRequest clear = new CreateShopRequest();
            clear.setName("825 Shop");
            clear.setPrepTimeMinutes(null);
            ShopDto cleared = SystemPrincipal.asSystem(() -> shopService.updateShop(f.shopId(), clear));
            assertThat(cleared.getPrepTimeMinutes()).as("an explicit null clears it").isNull();
        } finally {
            TenantContext.clear();
        }
    }

    // ---- helpers ----

    private GuestOrderConfirmation placeGuestOrder(Fixture f, String email) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(f.productId());
        item.setQuantity(1);
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Guest Buyer");
        request.setCustomerEmail(email);
        request.setCustomerPhone("07700" + String.format(java.util.Locale.ROOT, "%06d",
                Math.floorMod(email.hashCode(), 1_000_000)));
        request.setIdempotencyKey("p825-" + UUID.randomUUID());
        request.setItems(List.of(item));
        request.setFulfilmentType("COLLECTION");
        GuestOrderAcknowledgements.acknowledgeCurrent(request, jdbcTemplate, f.tenantId());
        GuestOrderAcknowledgements.expectCurrentPrices(request, jdbcTemplate, f.tenantId());
        TenantContext.clear();
        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(f.slug(), request);
        TenantContext.clear();
        return confirmation;
    }

    private Fixture seed(Integer prepTimeMinutes) {
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String slug = "shop-825-" + tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())", tenantId, "825 " + tenantId);
        TenantContext.set(tenantId);
        try {
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source, prep_time_minutes) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN', ?)",
                    shopId, tenantId, "825 Shop", slug, prepTimeMinutes);
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, '', 0, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, tenantId, "SKU-825-" + tenantId.toString().substring(0, 8), "825 dish", shopId);
        } finally {
            TenantContext.clear();
        }
        return new Fixture(tenantId, shopId, slug, productId);
    }
}
