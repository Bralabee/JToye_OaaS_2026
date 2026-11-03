package uk.jtoye.core.customer;

import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.security.access.CustomerContactScope;
import uk.jtoye.core.security.access.ShopRole;

import java.util.List;
import java.util.UUID;

/**
 * Issue #856 / SW-TND-08: whether the caller sees a customer IN FULL, and the write-side guard that
 * follows from it.
 *
 * <p>A caller sees a customer in full when its {@link CustomerContactScope} covers every shop, or
 * when the customer sits in a shop it manages by the SAME Option C predicate the reads use
 * ({@link CustomerRepository#findIdsInShopSet}: origin shop, or an order at a managed shop). The
 * reads minimise everyone else; the WRITES below must not undo that. Two writes would (tenancy review
 * of the #856 branch, findings R0-1 and R0-2):
 * <ul>
 *   <li>linking a customer to an order (create or update) copies the customer's email onto an order
 *       at a shop the caller manages, returns it in full, and from then on the order history places
 *       the customer in a managed shop for every later read;</li>
 *   <li>updating a customer replaces an email the caller has never been shown with whatever it types,
 *       since the edit form can only offer a blank one.</li>
 * </ul>
 * Both are therefore refused, with the typed shop-access 403 (requiredRole SHOP_MANAGER): only a
 * caller who manages one of the customer's shops may link or edit that customer. A caller with
 * full contact everywhere (Group admin, realm admin, tenant-wide SHOP_MANAGER) is never affected.
 */
public final class CustomerContactRule {

    private CustomerContactRule() {
    }

    /** True when {@code scope} shows {@code customer} in full; a {@code null} scope never does. */
    public static boolean seesInFull(CustomerContactScope scope, Customer customer, UUID tenantId,
                                     CustomerRepository repository) {
        if (scope == null || customer == null) {
            return false;
        }
        if (scope.allShops()) {
            return true;
        }
        if (scope.managedShopIds().isEmpty()) {
            return false;
        }
        return !repository.findIdsInShopSet(tenantId, List.of(customer.getId()), scope.managedShopIds())
                .isEmpty();
    }

    /**
     * Refuse a write that would link or edit {@code customer} when the caller sees it minimised.
     *
     * @throws ShopAccessDeniedException 403 {@code shop-access-denied}, requiredRole SHOP_MANAGER,
     *                                   shopId the customer's origin shop when it has one
     */
    public static void requireFullContact(CustomerContactScope scope, Customer customer, UUID tenantId,
                                          CustomerRepository repository) {
        if (!seesInFull(scope, customer, tenantId, repository)) {
            throw new ShopAccessDeniedException(customer.getOriginShopId(), ShopRole.SHOP_MANAGER);
        }
    }
}
