package uk.jtoye.core.notification;

import java.util.List;

/**
 * What the customer ordered, for the order emails (SW-JRD-15, swarm epic #964; issue #826): the
 * item lines, the total, and where the order goes. Read by {@code OrderStateChangeListener} from the
 * order it has already loaded under the event's tenant, so the {@code @Async} send looks nothing up
 * (Pitfall 8, the same rule as {@link CustomerEmailContext}).
 *
 * <p>The customer's allergy note is deliberately not here: no customer surface echoes its text
 * (31.1-13, T-31.1-46), and an email is a customer surface.
 *
 * @param lines             the order lines as the order recorded them (the V30 name snapshot); never null
 * @param totalPennies      the order total in pennies; null when not recorded
 * @param deliveryAddress   the delivery address lines, blank ones dropped; empty for a collection order
 *                          or when none was recorded
 * @param collectionAddress the shop's address, for a collection order; null when the shop has none
 */
public record OrderEmailSummary(
        List<Line> lines,
        Long totalPennies,
        List<String> deliveryAddress,
        String collectionAddress) {

    public OrderEmailSummary {
        lines = lines == null ? List.of() : List.copyOf(lines);
        deliveryAddress = deliveryAddress == null ? List.of() : List.copyOf(deliveryAddress);
    }

    /** One order line: the product name recorded on the line and how many were ordered. */
    public record Line(String name, Integer quantity) {
    }
}
