package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * COR-4 (QA-council 20260902-134741, adjudication A9) — an order records how many THINGS the
 * customer bought, not only how many lines the basket had.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code orders.item_count} means LINES ({@code items.size()}); the browser's basket means
 * UNITS ({@code cart-provider.tsx} reduces over quantity). Both render the identical English
 * string "{n} item(s)". A customer buying 6 Zobos is shown "6 items" on the basket, the checkout
 * header and the cart drawer, then "1 item" on the tracking page, the per-shop order page and My
 * Orders. Live on 24 of 60 orders (40%) on the dev runtime.
 *
 * <h2>Why a second column and not a redefinition</h2>
 *
 * <p>Per A9, {@code item_count} is untouched: redefining it would rewrite the meaning of a
 * persisted column for every existing row with nothing distinguishing migrated rows, would change
 * three public contracts with ZERO OpenAPI diff, and would red the shared invariant I5 on all 60
 * rows. The assertions below therefore pin BOTH numbers, in the same order, so a future
 * "simplification" that collapses them fails here.
 */
class OrderUnitCountTest {

    private static Order orderWith(int... quantities) {
        Order order = new Order();
        order.setTenantId(UUID.randomUUID());
        for (int quantity : quantities) {
            OrderItem item = new OrderItem(UUID.randomUUID(), quantity, 899L);
            order.addItem(item);
        }
        order.calculateTotal();
        return order;
    }

    @Test
    @DisplayName("COR-4: 6 of one product is 6 units on ONE line — the two numbers must both be right")
    void sixOfOneProductIsSixUnitsOnOneLine() {
        Order order = orderWith(6);

        assertEquals(6, order.getUnitCount(),
                "COR-4: the customer bought 6 things and was shown '6 items' in the basket");
        assertEquals(1, order.getItemCount(),
                "A9: item_count keeps its LINES meaning — invariant I5 and every existing contract "
                        + "depend on it");
        assertNotEquals(order.getUnitCount(), order.getItemCount(),
                "this is the exact divergence the finding is about; if these are equal the fixture "
                        + "cannot detect the defect");
    }

    @Test
    @DisplayName("COR-4: units sum across lines")
    void unitsSumAcrossLines() {
        Order order = orderWith(2, 3, 1);

        assertEquals(6, order.getUnitCount());
        assertEquals(3, order.getItemCount());
    }

    @Test
    @DisplayName("COR-4: one of each is the degenerate case where the two agree — and both are still set")
    void oneOfEachAgrees() {
        Order order = orderWith(1, 1);

        assertEquals(2, order.getUnitCount());
        assertEquals(2, order.getItemCount());
    }

    @Test
    @DisplayName("COR-4: an empty order is 0 units and 0 lines — 0 is a RECORDED zero, not a null")
    void emptyOrderIsZeroNotNull() {
        Order order = orderWith();

        assertEquals(0, order.getUnitCount());
        assertEquals(0, order.getItemCount());
    }

    /**
     * The NULL-vs-0 rule, asserted rather than only written down. A historic row that never went
     * through {@code calculateTotal()} after V66 must read as NOT RECORDED, and nothing may
     * substitute {@code item_count} or 0 for it.
     */
    @Test
    @DisplayName("COR-4: a row that predates V66 reads NULL — 'not recorded' is not 'zero units'")
    void aPreV66RowReadsNull() {
        Order historic = new Order();
        historic.setItemCount(3);

        assertNull(historic.getUnitCount(),
                "NULL means not recorded; a backfill would fabricate a figure no customer was shown");
        assertEquals(3, historic.getItemCount());
    }
}
