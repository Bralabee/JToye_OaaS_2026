package uk.jtoye.core.finance;

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
import uk.jtoye.core.finance.dto.FinancialSummaryDto;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.SystemPrincipal;
import uk.jtoye.core.storefront.PublicStorefrontService;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.testsupport.GuestOrderAcknowledgements;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #962 (owner ruling 2026-11-04, persona close-out): a sale by a seller with NO declared VAT number is
 * recorded with ZERO VAT, in the order AND in the financial ledger (and therefore the Article 15 export),
 * so the checkout line "this seller has not declared a VAT registration, so no VAT is shown" is true of
 * what is stored. A VAT-registered seller (trader identity carries a VAT number) is unchanged.
 *
 * <p>Both arms, both order writers (storefront and vendor), and the ledger aggregate. No backfill: past
 * orders keep the VAT they were recorded with (rewriting historic ledger rows would fabricate a record).
 *
 * <p>Real Postgres (Testcontainers): the trader identity is FORCE RLS tenant-scoped and the lookup must
 * work under the pinned tenant.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
@uk.jtoye.core.testsupport.AsSystemHarness
class SellerVatRegistrationIntegrationTest {

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
    @Autowired FinancialTransactionService financialTransactionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private record Seller(UUID tenantId, UUID shopId, String slug, UUID productId) {
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Storefront writer
    // ---------------------------------------------------------------------------------------------

    @Test
    void storefrontOrderOfAnUnregisteredSellerRecordsZeroVat() {
        Seller seller = seed(false);
        UUID orderId = placeGuestOrder(seller);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT vat_rate, vat_amount_pennies, total_amount_pennies FROM orders WHERE id = ?", orderId);
        assertThat(row.get("vat_rate")).as("no declared VAT number, so the order is zero-rated").isEqualTo("ZERO");
        assertThat(((Number) row.get("vat_amount_pennies")).longValue()).isZero();
        assertThat(((Number) row.get("total_amount_pennies")).longValue())
                .as("the customer's total is untouched: prices are VAT-inclusive, only the VAT carved out of it changes")
                .isEqualTo(1000L);
    }

    @Test
    void storefrontOrderOfARegisteredSellerIsUnchanged() {
        Seller seller = seed(true);
        UUID orderId = placeGuestOrder(seller);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT vat_rate, vat_amount_pennies FROM orders WHERE id = ?", orderId);
        assertThat(row.get("vat_rate")).isEqualTo("STANDARD");
        assertThat(((Number) row.get("vat_amount_pennies")).longValue()).as("1000 * 20 / 120").isEqualTo(166L);
    }

    @Test
    void aTenantWithNoTraderIdentityAtAllDeclaresNoVatNumberSoIsZeroRated() {
        Seller seller = seed(null);
        UUID orderId = placeGuestOrder(seller);
        assertThat(jdbcTemplate.queryForObject("SELECT vat_rate FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("ZERO");
    }

    // ---------------------------------------------------------------------------------------------
    // Vendor writer
    // ---------------------------------------------------------------------------------------------

    @Test
    void vendorOrderOfAnUnregisteredSellerRecordsZeroVat() {
        Seller seller = seed(false);
        UUID orderId = placeVendorOrder(seller);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT vat_rate, vat_amount_pennies FROM orders WHERE id = ?", orderId);
        assertThat(row.get("vat_rate")).isEqualTo("ZERO");
        assertThat(((Number) row.get("vat_amount_pennies")).longValue()).isZero();
    }

    @Test
    void vendorOrderOfARegisteredSellerIsUnchanged() {
        Seller seller = seed(true);
        UUID orderId = placeVendorOrder(seller);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT vat_rate, vat_amount_pennies FROM orders WHERE id = ?", orderId);
        assertThat(row.get("vat_rate")).isEqualTo("STANDARD");
        assertThat(((Number) row.get("vat_amount_pennies")).longValue()).isEqualTo(166L);
    }

    // ---------------------------------------------------------------------------------------------
    // Ledger
    // ---------------------------------------------------------------------------------------------

    @Test
    void ledgerOfAnUnregisteredSellerCarriesZeroVat() {
        Seller seller = seed(false);
        UUID orderId = placeGuestOrder(seller);
        complete(seller, orderId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT vat_rate FROM financial_transactions WHERE order_id = ?", String.class, orderId))
                .isEqualTo("ZERO");
        FinancialSummaryDto summary = summary(seller);
        assertThat(summary.totalRevenuePennies()).isEqualTo(1000L);
        assertThat(summary.totalVatPennies()).as("the ledger aggregate agrees with the checkout line").isZero();
    }

    @Test
    void ledgerOfARegisteredSellerIsUnchanged() {
        Seller seller = seed(true);
        UUID orderId = placeGuestOrder(seller);
        complete(seller, orderId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT vat_rate FROM financial_transactions WHERE order_id = ?", String.class, orderId))
                .isEqualTo("STANDARD");
        assertThat(summary(seller).totalVatPennies()).isEqualTo(166L);
    }

    @Test
    void anOrderPlacedWhileRegisteredKeepsItsVatAfterTheSellerDeregisters() {
        // The rate is stamped on the order when it is placed; a later change of registration status
        // does not rewrite it (and no migration rewrites past orders: that would fabricate a record).
        Seller seller = seed(true);
        UUID orderId = placeGuestOrder(seller);

        TenantContext.set(seller.tenantId());
        try {
            jdbcTemplate.update("UPDATE trader_identity SET vat_number = NULL WHERE tenant_id = ?", seller.tenantId());
        } finally {
            TenantContext.clear();
        }
        complete(seller, orderId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT vat_rate FROM financial_transactions WHERE order_id = ?", String.class, orderId))
                .isEqualTo("STANDARD");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private FinancialSummaryDto summary(Seller seller) {
        TenantContext.set(seller.tenantId());
        try {
            // Scoped to the seller's shop: the harness connection is a superuser (RLS-bypassing), so an
            // unscoped aggregate here would add up every test tenant's ledger. Same arithmetic, same query shape.
            return financialTransactionService.getSummary(
                    new FinanceScope(seller.shopId(), "962 Shop", seller.slug(), null, null));
        } finally {
            TenantContext.clear();
        }
    }

    private void complete(Seller seller, UUID orderId) {
        TenantContext.set(seller.tenantId());
        try {
            SystemPrincipal.asSystem(() -> {
                orderService.confirmOrder(orderId);
                orderService.startPreparation(orderId);
                orderService.markOrderReady(orderId);
                orderService.completeOrder(orderId);
            });
        } finally {
            TenantContext.clear();
        }
    }

    private UUID placeVendorOrder(Seller seller) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(seller.productId());
        item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setShopId(seller.shopId());
        request.setCustomerName("Vendor Keyed");
        request.setCustomerEmail("vendor-962-" + UUID.randomUUID() + "@example.com");
        request.setCustomerPhone("07700900962");
        request.setFulfilmentType("COLLECTION");
        request.setItems(List.of(item));
        TenantContext.set(seller.tenantId());
        try {
            return orderService.createOrder(request).getId();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID placeGuestOrder(Seller seller) {
        GuestOrderItemRequest item = new GuestOrderItemRequest();
        item.setProductId(seller.productId());
        item.setQuantity(1);
        GuestOrderRequest request = new GuestOrderRequest();
        request.setCustomerName("Guest Buyer");
        String email = "guest-962-" + UUID.randomUUID() + "@example.com";
        request.setCustomerEmail(email);
        request.setCustomerPhone("07700" + String.format(java.util.Locale.ROOT, "%06d",
                Math.floorMod(email.hashCode(), 1_000_000)));
        request.setIdempotencyKey("p962-" + UUID.randomUUID());
        request.setItems(List.of(item));
        request.setFulfilmentType("COLLECTION");
        GuestOrderAcknowledgements.acknowledgeCurrent(request, jdbcTemplate, seller.tenantId());
        GuestOrderAcknowledgements.expectCurrentPrices(request, jdbcTemplate, seller.tenantId());

        TenantContext.clear();
        GuestOrderConfirmation confirmation = publicStorefrontService.createGuestOrder(seller.slug(), request);
        TenantContext.clear();
        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_number = ?", UUID.class, confirmation.getOrderNumber());
    }

    /** One tenant, one published shop, one STANDARD-rated 10.00 product; a trader identity with or without VAT. */
    private Seller seed(Boolean vatRegistered) {
        UUID tenantId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String slug = "shop-962-" + tenantId.toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, now())",
                tenantId, "962 tenant " + tenantId);
        TenantContext.set(tenantId);
        try {
            jdbcTemplate.update(
                    "INSERT INTO shops (id, tenant_id, created_at, name, slug, published, "
                            + "delivery_fee_pennies, minimum_order_pennies, version, hours_source) "
                            + "VALUES (?, ?, now(), ?, ?, true, 0, 0, 0, 'LEGACY_ALWAYS_OPEN')",
                    shopId, tenantId, "962 Shop", slug);
            jdbcTemplate.update(
                    "INSERT INTO products (id, tenant_id, created_at, sku, title, ingredients_text, "
                            + "allergen_mask, price_pennies, display_order, available, featured, "
                            + "shop_id, quantity_in_stock, vat_rate, version) "
                            + "VALUES (?, ?, now(), ?, ?, '', 0, 1000, 0, true, false, ?, 100, 'STANDARD', 0)",
                    productId, tenantId, "SKU-962-" + tenantId.toString().substring(0, 8), "962 dish", shopId);
            if (vatRegistered != null) { // null = the tenant has published NO trader identity at all
                jdbcTemplate.update(
                        "INSERT INTO trader_identity (id, tenant_id, legal_name, entity_type, address_line1, "
                                + "address_city, address_postcode, vat_number) "
                                + "VALUES (?, ?, '962 Trading', 'SOLE_TRADER', '1 High St', 'Leeds', 'LS1 1AA', ?)",
                        UUID.randomUUID(), tenantId, vatRegistered ? "GB123456789" : null);
            }
        } finally {
            TenantContext.clear();
        }
        return new Seller(tenantId, shopId, slug, productId);
    }
}
