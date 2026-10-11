package uk.jtoye.core.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        this.maxLineQuantity = maxLineQuantity;
        this.maxBasketUnits = maxBasketUnits;
        this.maxLines = maxLines;
        this.maxBasketTotalPennies = maxBasketTotalPennies;
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
        // RED (37-26 Task 1): no behaviour yet.
    }
}
