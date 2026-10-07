package uk.jtoye.core.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 31.1 plan 03 (#784, D-05): since V69 a storefront guest order must carry
 * {@code acknowledgedAllergenMask}, and it must EQUAL the union of the declared masks the server
 * reads for the basket at submit. Every pre-existing caller that places a guest order sends that
 * value through this helper.
 *
 * <p>Always the CURRENT mask, read at the builder site, never a constant: a constant would turn into
 * a 409 the moment a fixture's mask changed, and would hide which test depended on which mask.
 */
public final class GuestOrderAcknowledgements {

    private GuestOrderAcknowledgements() {
    }

    /**
     * The OR of the products' declared {@code allergen_mask}, read by SQL under {@code tenantId}
     * (restoring the caller's tenant context afterwards). A product id with no row fails loudly
     * rather than contributing 0, so a typo cannot quietly acknowledge an empty set.
     */
    public static int currentMask(JdbcTemplate jdbc, UUID tenantId, Collection<UUID> productIds) {
        Optional<UUID> previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            int mask = 0;
            for (UUID productId : productIds) {
                List<Integer> rows = jdbc.queryForList(
                        "SELECT allergen_mask FROM products WHERE id = ?", Integer.class, productId);
                if (rows.size() != 1) {
                    throw new IllegalStateException("Expected one product row for " + productId + ", found " + rows.size());
                }
                mask |= rows.get(0);
            }
            return mask;
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
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
}
