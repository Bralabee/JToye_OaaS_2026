package uk.jtoye.core.security.access;

import java.util.Set;
import java.util.UUID;

/**
 * Which shops' customer contact data the current caller may see in full (issue #856 / SW-TND-08,
 * owner ruling 2026-11-03).
 *
 * <p><b>The rule.</b> A caller whose best honoured role on an order's or customer's shop is STAFF
 * sees the customer's name and phone, but not the customer email, and no address on a
 * non-DELIVERY order. SHOP_MANAGER and above on that shop, a tenant-wide SHOP_MANAGER, a
 * GROUP_ADMIN and a realm admin see everything, unchanged. The basis is data minimisation, UK GDPR
 * Art 5(1)(c): a kitchen-only account has no operational need for the email, and STAFF still
 * performs the delivery hand-off, so a DELIVERY address is kept.
 *
 * <p>The rule is applied when the response DTO is built, by the role on the ROW's shop, never in
 * the UI: a client-side hide leaves the data in the response.
 *
 * <p>Decided by {@link ShopAccessService#customerContactScope()} with the same ladder the access
 * gate uses, so what is shown can never disagree with what is enforced. A {@code null} scope
 * anywhere it is consumed counts as minimised: a missing decision is minimisation, never
 * disclosure.
 *
 * @param allShops       full contact on every shop of the tenant
 * @param managedShopIds the shops on which the caller holds SHOP_MANAGER or above (ignored when
 *                       {@code allShops}); defensively copied
 */
public record CustomerContactScope(boolean allShops, Set<UUID> managedShopIds) {

    /** Full contact everywhere: GROUP_ADMIN, realm admin, a tenant-wide SHOP_MANAGER. */
    public static final CustomerContactScope ALL_SHOPS = new CustomerContactScope(true, Set.of());

    /** Full contact nowhere: STAFF only, or an unidentifiable caller (fail closed). */
    public static final CustomerContactScope NONE = new CustomerContactScope(false, Set.of());

    public CustomerContactScope {
        managedShopIds = managedShopIds == null ? Set.of() : Set.copyOf(managedShopIds);
    }

    /** True when the caller sees full customer contact on {@code shopId}. */
    public boolean fullContactOn(UUID shopId) {
        return allShops || (shopId != null && managedShopIds.contains(shopId));
    }
}
