package uk.jtoye.core.storefront;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.OrderCapExceededException;
import uk.jtoye.core.order.OrderRepository;

import java.util.Locale;
import java.util.UUID;

/**
 * The per-identity caps on OPEN cash orders at one shop (Phase 37-27, D-18, RWO-041 / UXT-041).
 *
 * <p>Anyone could place many fake cash orders in seconds with throwaway details. The bound is not a
 * rate but a COUNT: how many orders that are still open (PENDING, CONFIRMED, PREPARING or READY), paid
 * in cash and not voided, one identity may hold at a shop. A slot frees when the kitchen completes or
 * cancels an order. Card orders are outside the cap: a card is the customer's own commitment.
 *
 * <p>The counts are repository queries over V81's partial indexes. Normalisation lives here, in one
 * place, so the cap cannot be dodged by respelling an address: email is trimmed and lower-cased.
 *
 * <p>The limits are config ({@code jtoye.storefront.cash-caps.*}), never literals (D-18). A value below
 * 1 refuses to start, naming the key (the {@code requireUsableRadius} / {@code OrderLimits} rule): a
 * zero cap would refuse every cash order on every shop, silently.
 */
@Component
public class CashOrderCaps {

    private final OrderRepository orderRepository;
    private final int perEmail;
    private final int perPhone;
    private final int perIp;

    public CashOrderCaps(OrderRepository orderRepository,
                         @Value("${jtoye.storefront.cash-caps.per-email}") int perEmail,
                         @Value("${jtoye.storefront.cash-caps.per-phone}") int perPhone,
                         @Value("${jtoye.storefront.cash-caps.per-ip}") int perIp) {
        this.orderRepository = orderRepository;
        this.perEmail = requireAtLeastOne(perEmail, "jtoye.storefront.cash-caps.per-email");
        this.perPhone = requireAtLeastOne(perPhone, "jtoye.storefront.cash-caps.per-phone");
        this.perIp = requireAtLeastOne(perIp, "jtoye.storefront.cash-caps.per-ip");
    }

    /**
     * Refuse a cash order whose customer identity already holds the cap at this shop.
     *
     * @param tenantId the shop's tenant (the explicit predicate beside RLS)
     * @param shopId   the shop the order is for — the cap is per shop
     * @param email    the customer email as submitted; normalised here
     * @param phone    the customer phone as submitted; normalised here
     * @throws OrderCapExceededException naming the axis that is full
     */
    public void checkEmailAndPhone(UUID tenantId, UUID shopId, String email, String phone) {
        String normalisedEmail = normaliseEmail(email);
        if (normalisedEmail != null
                && orderRepository.countOpenCashOrdersByEmail(tenantId, shopId, normalisedEmail) >= perEmail) {
            throw new OrderCapExceededException(OrderCapExceededException.Axis.EMAIL, perEmail);
        }
    }

    /** The configured per-email cap (for tests and the public shop payload, should one ever carry it). */
    public int perEmail() {
        return perEmail;
    }

    /** The configured per-phone cap. */
    public int perPhone() {
        return perPhone;
    }

    /** The configured per-IP cap: read here so the key is bound and refused below 1 from day one; 37-28 counts on it. */
    public int perIp() {
        return perIp;
    }

    /** Phone normalisation — Task 2 implements it; this scaffold has no behaviour. */
    static String normalisePhone(String phone) {
        return phone;
    }

    /** Trimmed and lower-cased; {@code null} for a blank address. */
    static String normaliseEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static int requireAtLeastOne(int value, String key) {
        if (value < 1) {
            throw new IllegalStateException(key + " must be at least 1, was " + value
                    + ": a cap below 1 would refuse every cash order at every shop");
        }
        return value;
    }
}
