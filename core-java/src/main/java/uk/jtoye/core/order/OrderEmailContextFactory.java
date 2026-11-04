package uk.jtoye.core.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.notification.CustomerEmailContext;
import uk.jtoye.core.onboarding.TraderIdentityService;
import uk.jtoye.core.product.AllergenCatalog;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.SellerIdentityDto;

import java.util.List;
import java.util.UUID;

/**
 * Builds the immutable {@link CustomerEmailContext} one order email is written from: the seller, the
 * allergen record, the fulfilment type and the order summary.
 *
 * <p>Extracted from {@link OrderStateChangeListener} (unchanged behaviour) so a second sender, the
 * unaccepted-order sweep ({@link UnacceptedOrderSweep}, #821), writes its email from exactly the same
 * context as the status emails and the two cannot drift.
 *
 * <p><b>Tenant (Pitfall 8).</b> Everything here is read under FORCE RLS: the caller must have
 * {@code TenantContext} and the transaction-local GUC pinned to {@code tenantId} before calling, and the
 * result is an immutable value, so the {@code @Async} send that follows reads nothing.
 *
 * <p>The shop is read with an explicit tenant predicate as well as RLS. The seller comes from
 * {@link TraderIdentityService#findPublicSeller} (the SAME read the public shop page uses, 31.1-24). The
 * allergen record is the V63 order-line snapshot (never a live product join) and the V69 acknowledgement
 * mask. A failed seller read must not cost the customer the email: it then says the seller has not provided
 * details, which is true of what the platform can show, rather than not arriving at all.
 */
@Component
public class OrderEmailContextFactory {

    private static final Logger log = LoggerFactory.getLogger(OrderEmailContextFactory.class);

    private final ShopRepository shopRepository;
    private final TraderIdentityService traderIdentityService;

    public OrderEmailContextFactory(ShopRepository shopRepository, TraderIdentityService traderIdentityService) {
        this.shopRepository = shopRepository;
        this.traderIdentityService = traderIdentityService;
    }

    public CustomerEmailContext build(UUID tenantId, String orderNumber, Order order) {
        // Order lines first, while the caller's own session is the active one.
        OrderAllergenSnapshot.OrderAllergenView view = OrderAllergenSnapshot.viewOf(order.getItems());
        List<String> acknowledged = order.getAllergenAckMask() == null
                ? null
                : AllergenCatalog.namesFor(order.getAllergenAckMask());

        Shop shop = order.getShopId() == null
                ? null
                : shopRepository.findByIdAndTenantId(order.getShopId(), tenantId).orElse(null);
        SellerIdentityDto seller = null;
        if (shop != null) {
            try {
                seller = traderIdentityService.findPublicSeller(tenantId, shop);
            } catch (RuntimeException e) {
                log.warn("Seller identity read failed for order {}: {}", orderNumber, e.getClass().getSimpleName());
            }
        }

        return new CustomerEmailContext(
                shop == null ? null : shop.getName(),
                shop == null ? null : shop.getEmail(),
                seller,
                order.getPlacedVia(),
                acknowledged,
                view.declaredNames(),
                view.flags(),
                order.getFulfilmentType(),
                OrderStateChangeListener.summaryOf(order, shop),
                order.getEstimatedReadyAt(),
                order.getCancelReasonCode(),
                shop == null ? null : shop.getPhone());
    }
}
