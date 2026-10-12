package uk.jtoye.core.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import uk.jtoye.core.order.FulfilmentPolicy;
import uk.jtoye.core.order.FulfilmentType;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Phase 31.1 plan 03 (#784, D-05): since V69 a storefront guest order must carry
 * {@code acknowledgedAllergenMask}, and it must EQUAL the union of the declared masks the server
 * reads for the basket at submit. Every pre-existing caller that places a guest order sends that
 * value through this helper.
 *
 * <p>Phase 37 plan 32 (D-16, RWO-007): a storefront guest order also carries the prices the customer
 * SAW — {@code GuestOrderItemRequest.expectedUnitPricePennies} per line and
 * {@code GuestOrderRequest.expectedTotalPennies} for the order — and 37-33 refuses a submit whose
 * expectation is missing or differs from the server's current figures. Every caller sends them through
 * {@link #expectCurrentPrices} and its Map-body siblings, so the refusal can land without a red window.
 *
 * <p>Always the CURRENT values, read at the builder site, never a constant: a constant would turn into
 * a 409 the moment a fixture's mask or price changed, and would hide which test depended on which value.
 * The expected total is computed the way {@code PublicStorefrontService.placeGuestOrder} computes
 * {@code Order.totalAmountPennies}: the sum of the line totals at the current server prices plus
 * {@link FulfilmentPolicy#deliveryFeePennies} for the shop and fulfilment type (VAT is inclusive, so it
 * adds nothing). The shop is read from the products' own {@code shop_id}; a basket naming products of
 * more than one shop fails loudly rather than guessing, and a caller that deliberately builds one passes
 * the shop explicitly through the five-argument {@link #expectedTotal}.
 */
public final class GuestOrderAcknowledgements {

    private GuestOrderAcknowledgements() {
    }

    /** One basket line as the server reads it: a product and a quantity. */
    public record Line(UUID productId, int quantity) {
    }

    /**
     * The OR of the products' declared {@code allergen_mask}, read by SQL under {@code tenantId}
     * (restoring the caller's tenant context afterwards). A product id with no row fails loudly
     * rather than contributing 0, so a typo cannot quietly acknowledge an empty set.
     */
    public static int currentMask(JdbcTemplate jdbc, UUID tenantId, Collection<UUID> productIds) {
        return inTenant(tenantId, () -> {
            int mask = 0;
            for (UUID productId : productIds) {
                mask |= oneRow(jdbc, "SELECT allergen_mask FROM products WHERE id = ?", Integer.class, productId);
            }
            return mask;
        });
    }

    /** Sets the request's acknowledgement to the current mask of the products its items name. */
    public static GuestOrderRequest acknowledgeCurrent(GuestOrderRequest request, JdbcTemplate jdbc, UUID tenantId) {
        List<UUID> productIds = request.getItems().stream().map(GuestOrderItemRequest::getProductId).toList();
        request.setAcknowledgedAllergenMask(currentMask(jdbc, tenantId, productIds));
        return request;
    }

    /** For mocked unit tests: the OR of the in-memory products' declared masks, as the service would read them. */
    public static int currentMask(Product... products) {
        int mask = 0;
        for (Product product : products) {
            mask |= product.getAllergenMask();
        }
        return mask;
    }

    // ------------------------------------------------------------------ 37-32: the prices the customer saw

    /**
     * The product's CURRENT {@code price_pennies}, read by SQL under {@code tenantId} (restoring the
     * caller's tenant context afterwards). A product id with no row fails loudly rather than
     * contributing 0, so a typo cannot quietly expect a free line.
     */
    public static long expectedUnitPrice(JdbcTemplate jdbc, UUID tenantId, UUID productId) {
        return inTenant(tenantId, () -> oneRow(jdbc, "SELECT price_pennies FROM products WHERE id = ?", Long.class, productId));
    }

    /** For mocked unit tests: the in-memory product's price, as the service would read it. */
    public static long expectedUnitPrice(Product product) {
        return product.getPricePennies();
    }

    /**
     * The total the server will compute for {@code lines} at the shop the products belong to:
     * sum(current price × quantity) plus the shop's delivery fee for {@code fulfilmentType} under
     * {@link FulfilmentPolicy#deliveryFeePennies}. Fails loudly when the lines name products of more
     * than one shop — pass the shop explicitly for such a basket.
     */
    public static long expectedTotal(JdbcTemplate jdbc, UUID tenantId, String fulfilmentType, List<Line> lines) {
        return inTenant(tenantId, () -> {
            Set<UUID> shopIds = new LinkedHashSet<>();
            for (Line line : lines) {
                shopIds.add(oneRow(jdbc, "SELECT shop_id FROM products WHERE id = ?", UUID.class, line.productId()));
            }
            if (shopIds.size() != 1) {
                throw new IllegalStateException("Expected the basket's products to belong to one shop, found " + shopIds
                        + " — pass the shop explicitly to expectedTotal(jdbc, tenantId, shopId, fulfilmentType, lines)");
            }
            return expectedTotal(jdbc, tenantId, shopIds.iterator().next(), fulfilmentType, lines);
        });
    }

    /** As {@link #expectedTotal(JdbcTemplate, UUID, String, List)}, with the shop named by the caller. */
    public static long expectedTotal(JdbcTemplate jdbc, UUID tenantId, UUID shopId, String fulfilmentType, List<Line> lines) {
        return inTenant(tenantId, () -> {
            long subtotal = 0L;
            for (Line line : lines) {
                subtotal += expectedUnitPrice(jdbc, tenantId, line.productId()) * line.quantity();
            }
            Map<String, Object> shop = jdbc.queryForMap(
                    "SELECT delivery_fee_pennies, free_delivery_threshold_pennies FROM shops WHERE id = ?", shopId);
            return subtotal + FulfilmentPolicy.deliveryFeePennies(
                    FulfilmentPolicy.resolve(fulfilmentType, FulfilmentType.DELIVERY), subtotal,
                    asLong(shop.get("delivery_fee_pennies")), asLong(shop.get("free_delivery_threshold_pennies")));
        });
    }

    /**
     * For mocked unit tests: the total the service will compute for the request against in-memory
     * products (each line's product resolved by id from {@code products}) and the shop's fee rule.
     */
    public static long expectedTotal(Shop shop, GuestOrderRequest request, Product... products) {
        long subtotal = 0L;
        for (GuestOrderItemRequest item : request.getItems()) {
            subtotal += expectedUnitPrice(productById(item.getProductId(), products)) * item.getQuantity();
        }
        return subtotal + FulfilmentPolicy.deliveryFeePennies(
                FulfilmentPolicy.resolve(request.getFulfilmentType(), FulfilmentType.DELIVERY), subtotal,
                shop.getDeliveryFeePennies(), shop.getFreeDeliveryThresholdPennies());
    }

    /**
     * Sets every item's {@code expectedUnitPricePennies} and the request's {@code expectedTotalPennies}
     * from the CURRENT server prices and the shop's fee rule, as the checkout does from the menu it
     * rendered. The shop is the one the products belong to.
     */
    public static GuestOrderRequest expectCurrentPrices(GuestOrderRequest request, JdbcTemplate jdbc, UUID tenantId) {
        List<Line> lines = new ArrayList<>();
        for (GuestOrderItemRequest item : request.getItems()) {
            item.setExpectedUnitPricePennies(expectedUnitPrice(jdbc, tenantId, item.getProductId()));
            lines.add(new Line(item.getProductId(), item.getQuantity()));
        }
        request.setExpectedTotalPennies(expectedTotal(jdbc, tenantId, request.getFulfilmentType(), lines));
        return request;
    }

    /** For mocked unit tests: as {@link #expectCurrentPrices(GuestOrderRequest, JdbcTemplate, UUID)} against in-memory products. */
    public static GuestOrderRequest expectCurrentPrices(GuestOrderRequest request, Shop shop, Product... products) {
        for (GuestOrderItemRequest item : request.getItems()) {
            item.setExpectedUnitPricePennies(expectedUnitPrice(productById(item.getProductId(), products)));
        }
        request.setExpectedTotalPennies(expectedTotal(shop, request, products));
        return request;
    }

    /**
     * For callers that build the body as maps: puts {@code expectedUnitPricePennies} on every item map
     * (each must be mutable and carry {@code productId} and {@code quantity}) and returns the total to put
     * on the body as {@code expectedTotalPennies}.
     */
    public static long expectCurrentPrices(JdbcTemplate jdbc, UUID tenantId, String fulfilmentType,
                                           List<Map<String, Object>> items) {
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> item : items) {
            UUID productId = (UUID) item.get("productId");
            int quantity = ((Number) item.get("quantity")).intValue();
            item.put("expectedUnitPricePennies", expectedUnitPrice(jdbc, tenantId, productId));
            lines.add(new Line(productId, quantity));
        }
        return expectedTotal(jdbc, tenantId, fulfilmentType, lines);
    }

    // ------------------------------------------------------------------ internals

    private static <T> T inTenant(UUID tenantId, Supplier<T> work) {
        Optional<UUID> previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return work.get();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private static <T> T oneRow(JdbcTemplate jdbc, String sql, Class<T> type, UUID productId) {
        List<T> rows = jdbc.queryForList(sql, type, productId);
        if (rows.size() != 1 || rows.get(0) == null) {
            throw new IllegalStateException("Expected one product row for " + productId + " (" + sql + "), found " + rows);
        }
        return rows.get(0);
    }

    private static Product productById(UUID productId, Product... products) {
        for (Product product : products) {
            if (productId.equals(product.getId())) {
                return product;
            }
        }
        throw new IllegalStateException("No in-memory product with id " + productId + " among the " + products.length + " given");
    }

    private static Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
