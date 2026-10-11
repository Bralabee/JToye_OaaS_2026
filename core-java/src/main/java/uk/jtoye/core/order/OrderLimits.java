package uk.jtoye.core.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.jtoye.core.exception.OrderLimitExceededException;

import java.util.List;

/**
 * The ONE place an order's SIZE is bounded (Phase 37-26, D-20, RWO-042 / UXT-042).
 *
 * <p>Until this class existed the only bound on a quantity was {@code @Min(1)} on the two request
 * DTOs, so a line of 1,000,000 units was accepted and persisted as a £19 billion order (the persona
 * run's UXT-042). Bean Validation protects a DTO, not the domain: the storefront, the vendor
 * dashboard, the REST API and the MCP {@code create_order} tool are three writers of the same
 * {@code orders} rows, and a rule on one DTO reaches one of them (theme 6). This validator is
 * called by every writer — {@code PublicStorefrontService.placeGuestOrder} and
 * {@code OrderService.createOrder} — on the lines it is about to persist, so no writer can be
 * the one that forgot.
 *
 * <p>Modelled on {@link FulfilmentPolicy}: one rule, one definition, both paths. Unlike it, this
 * is a bean, because the limits are configuration ({@code jtoye.order-limits.*}, env
 * {@code ORDER_LIMIT_*}) and never literals in code (37-CONTEXT: config-declared limits).
 *
 * <p>The defaults are sized to clear every real basket the persona run produced: goods P2-CHA-16
 * (a 20-line, 40-unit basket must hit no limit) and the UXT-007 office lunch (about 35 items,
 * £198.50). They refuse what no kitchen fulfils in one order.
 */
@Component
public class OrderLimits {

    /** One line as the validator sees it: what it is called and how many of it. */
    public record LineCheck(String productName, int quantity) {}

    private final int maxLineQuantity;
    private final int maxBasketUnits;
    private final int maxLines;
    private final long maxBasketTotalPennies;

    public OrderLimits(@Value("${jtoye.order-limits.max-line-quantity:50}") int maxLineQuantity,
                       @Value("${jtoye.order-limits.max-basket-units:200}") int maxBasketUnits,
                       @Value("${jtoye.order-limits.max-lines:50}") int maxLines,
                       @Value("${jtoye.order-limits.max-basket-total-pennies:100000}") long maxBasketTotalPennies) {
        // A limit below 1 would refuse EVERY order. That is an operator's mistake, so it fails the
        // context at boot naming the key (the PublicStorefrontService.requireUsableRadius rule),
        // rather than waiting for the first customer to find it.
        this.maxLineQuantity = (int) requireAtLeastOne("jtoye.order-limits.max-line-quantity", maxLineQuantity);
        this.maxBasketUnits = (int) requireAtLeastOne("jtoye.order-limits.max-basket-units", maxBasketUnits);
        this.maxLines = (int) requireAtLeastOne("jtoye.order-limits.max-lines", maxLines);
        this.maxBasketTotalPennies = requireAtLeastOne("jtoye.order-limits.max-basket-total-pennies", maxBasketTotalPennies);
    }

    private static long requireAtLeastOne(String key, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(key + " must be at least 1, was: " + value);
        }
        return value;
    }

    /** The per-line ceiling, exposed so the storefront stepper can stop where the server refuses. */
    public int maxLineQuantity() {
        return maxLineQuantity;
    }

    public int maxBasketUnits() {
        return maxBasketUnits;
    }

    public int maxLines() {
        return maxLines;
    }

    public long maxBasketTotalPennies() {
        return maxBasketTotalPennies;
    }

    /**
     * Refuse an order that is too large on any axis.
     *
     * @param lines        the lines about to be persisted, in basket order
     * @param totalPennies the basket total (the sum of the line totals, delivery excluded)
     * @throws OrderLimitExceededException naming the field, the value and the limit
     */
    public void check(List<LineCheck> lines, long totalPennies) {
        // Per line first, in basket order, so the refusal points at the line the customer can fix;
        // then the basket as a whole. Each axis is independent: a basket of 51 single-unit lines is
        // refused on lines, four lines of 50 plus one more unit on units, and a within-limits basket
        // of expensive items on total. An empty basket is the DTO's @NotEmpty refusal, not this one.
        long units = 0;
        for (LineCheck line : lines) {
            if (line.quantity() > maxLineQuantity) {
                throw new OrderLimitExceededException("quantity", line.quantity(), maxLineQuantity, line.productName());
            }
            units += line.quantity();
        }
        if (lines.size() > maxLines) {
            throw new OrderLimitExceededException("lines", lines.size(), maxLines, null);
        }
        if (units > maxBasketUnits) {
            throw new OrderLimitExceededException("units", units, maxBasketUnits, null);
        }
        if (totalPennies > maxBasketTotalPennies) {
            throw new OrderLimitExceededException("total", totalPennies, maxBasketTotalPennies, null);
        }
    }
}
