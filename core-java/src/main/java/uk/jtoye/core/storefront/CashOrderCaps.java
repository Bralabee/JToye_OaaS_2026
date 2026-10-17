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
        // Email first: it is the axis the customer is told about in the confirmation flow, and the
        // cheaper query (one indexed count). The order is pinned by CashOrderCapsTest.
        String normalisedEmail = normaliseEmail(email);
        if (normalisedEmail != null
                && orderRepository.countOpenCashOrdersByEmail(tenantId, shopId, normalisedEmail) >= perEmail) {
            throw new OrderCapExceededException(OrderCapExceededException.Axis.EMAIL, perEmail);
        }
        // Phone: the stored spellings differ ("07700 900123" on one order, "+44 7700 900123" on the
        // next), so the shop's open cash phones are read through the partial index and folded here,
        // where the one normaliser lives; the matches are counted in Java.
        String normalisedPhone = normalisePhone(phone);
        if (normalisedPhone != null) {
            long held = orderRepository.findOpenCashOrderPhones(tenantId, shopId).stream()
                    .map(CashOrderCaps::normalisePhone)
                    .filter(normalisedPhone::equals)
                    .count();
            if (held >= perPhone) {
                throw new OrderCapExceededException(OrderCapExceededException.Axis.PHONE, perPhone);
            }
        }
    }

    /**
     * Refuse a cash order whose client address already holds the cap at this shop (37-28, D-27). The
     * identity is the KEYED DIGEST {@code ClientIpDigester} computed for the request — this class never
     * sees an address — counted over V81's {@code idx_orders_open_cash_ip}. A {@code null} digest (nothing
     * to digest) is not an identity and is not counted.
     *
     * @param tenantId the shop's tenant (the explicit predicate beside RLS)
     * @param shopId   the shop the order is for — the cap is per shop
     * @param digest   the HMAC digest of the resolved client address, or {@code null}
     * @throws OrderCapExceededException naming the {@code IP} axis
     */
    public void checkIp(UUID tenantId, UUID shopId, String digest) {
        if (digest == null || digest.isBlank()) {
            return;
        }
        if (orderRepository.countOpenCashOrdersByClientIpDigest(tenantId, shopId, digest) >= perIp) {
            throw new OrderCapExceededException(OrderCapExceededException.Axis.IP, perIp);
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

    /** The configured per-IP cap (37-28 counts on it through {@link #checkIp}). */
    public int perIp() {
        return perIp;
    }

    /**
     * Digits only, with a UK number's leading {@code 0} read as the country code {@code 44} and an
     * international {@code 00} prefix dropped, so {@code "07700 900123"}, {@code "+44 7700 900123"},
     * {@code "0044 7700 900123"} and {@code "(07700) 900-123"} are one key. A non-UK number keeps its
     * digits as typed ({@code "+1 415 555 0100"} is {@code 14155550100}). {@code null} when there is no
     * digit at all, so a blank or "n/a" never matches anything. Exact: a neighbouring number sharing a
     * prefix is a different key. {@code GuestOrderRequest} enforces only {@code @NotBlank @Size(max = 50)}
     * on the phone, so the spellings this folds are the ones customers actually submit.
     */
    public static String normalisePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.startsWith("00") && digits.length() > 2) {
            // International dialling prefix: "0044 7700 900123" -> "447700900123".
            return digits.substring(2);
        }
        if (digits.startsWith("0") && digits.length() > 1) {
            // UK trunk prefix: "07700 900123" -> "447700900123". A lone "0" is left alone.
            return "44" + digits.substring(1);
        }
        return digits;
    }

    /** Trimmed and lower-cased; {@code null} for a blank address. */
    public static String normaliseEmail(String email) {
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
