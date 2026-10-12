package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.exception.OrderLimitExceededException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 37-26 (D-20, RWO-042 / UXT-042) — the one order-size rule, tested once, in isolation, on
 * BOTH sides of every boundary. The storefront and the vendor/REST/MCP path call the same
 * validator, so this is where the numbers are pinned; the integration test proves each writer
 * calls it.
 *
 * <p>The limits here are the configured defaults (50 per line, 200 units, 50 lines, £1,000), passed
 * explicitly so a change to the yml default is a visible change to this test.
 */
class OrderLimitsTest {

    private static final int MAX_LINE_QUANTITY = 50;
    private static final int MAX_BASKET_UNITS = 200;
    private static final int MAX_LINES = 50;
    private static final long MAX_TOTAL_PENNIES = 100_000L;

    private final OrderLimits limits = new OrderLimits(MAX_LINE_QUANTITY, MAX_BASKET_UNITS, MAX_LINES, MAX_TOTAL_PENNIES);

    private static OrderLimits.LineCheck line(String name, int quantity) {
        return new OrderLimits.LineCheck(name, quantity);
    }

    private static List<OrderLimits.LineCheck> linesOf(int count, int quantityEach) {
        List<OrderLimits.LineCheck> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(line("Line " + (i + 1), quantityEach));
        }
        return lines;
    }

    // ---- per-line quantity ------------------------------------------------------------------

    @Test
    @DisplayName("D-20: a line of exactly the per-line limit (50) passes")
    void lineAtTheLimitPasses() {
        assertDoesNotThrow(() -> limits.check(List.of(line("Jollof", MAX_LINE_QUANTITY)), 50_000L));
    }

    @Test
    @DisplayName("D-20: a line one above the per-line limit (51) is refused, naming the line and both numbers")
    void lineAboveTheLimitIsRefused() {
        OrderLimitExceededException e = assertThrows(OrderLimitExceededException.class,
                () -> limits.check(List.of(line("Suya", 2), line("Jollof", MAX_LINE_QUANTITY + 1)), 53_000L));
        assertEquals("quantity", e.getField());
        assertEquals(51L, e.getValue());
        assertEquals(50L, e.getLimit());
        assertEquals("Jollof", e.getProductName(), "the refusal points at the offending line");
        assertEquals("quantity is 51, above the limit of 50.", e.getMessage());
    }

    @Test
    @DisplayName("UXT-042: the persona's 1,000,000-unit line is refused on quantity")
    void absurdLineIsRefused() {
        OrderLimitExceededException e = assertThrows(OrderLimitExceededException.class,
                () -> limits.check(List.of(line("Zobo", 1_000_000)), 1_000_000_000L));
        assertEquals("quantity", e.getField());
        assertEquals(1_000_000L, e.getValue());
    }

    // ---- lines ------------------------------------------------------------------------------

    @Test
    @DisplayName("D-20: exactly the line limit (50 lines) passes")
    void linesAtTheLimitPass() {
        assertDoesNotThrow(() -> limits.check(linesOf(MAX_LINES, 1), 50_000L));
    }

    @Test
    @DisplayName("D-20: one line over the line limit (51 lines) is refused on lines, with no product named")
    void linesAboveTheLimitAreRefused() {
        OrderLimitExceededException e = assertThrows(OrderLimitExceededException.class,
                () -> limits.check(linesOf(MAX_LINES + 1, 1), 51_000L));
        assertEquals("lines", e.getField());
        assertEquals(51L, e.getValue());
        assertEquals(50L, e.getLimit());
        assertNull(e.getProductName(), "a basket-level refusal names no line");
    }

    // ---- units ------------------------------------------------------------------------------

    @Test
    @DisplayName("D-20: exactly the unit limit (200 units over 4 lines) passes")
    void unitsAtTheLimitPass() {
        assertDoesNotThrow(() -> limits.check(linesOf(4, MAX_LINE_QUANTITY), 100_000L));
    }

    @Test
    @DisplayName("D-20: one unit over the unit limit (201 over 5 lines, each within its own limit) is refused on units")
    void unitsAboveTheLimitAreRefused() {
        List<OrderLimits.LineCheck> lines = new ArrayList<>(linesOf(4, MAX_LINE_QUANTITY));
        lines.add(line("Extra", 1));
        OrderLimitExceededException e = assertThrows(OrderLimitExceededException.class,
                () -> limits.check(lines, 100_000L));
        assertEquals("units", e.getField());
        assertEquals(201L, e.getValue());
        assertEquals(200L, e.getLimit());
        assertNull(e.getProductName());
    }

    // ---- total ------------------------------------------------------------------------------

    @Test
    @DisplayName("D-20: a basket of exactly the total limit (£1,000.00) passes")
    void totalAtTheLimitPasses() {
        assertDoesNotThrow(() -> limits.check(List.of(line("Platter", 1)), MAX_TOTAL_PENNIES));
    }

    @Test
    @DisplayName("D-20: a basket one penny over the total limit (£1,000.01) is refused on total")
    void totalAboveTheLimitIsRefused() {
        OrderLimitExceededException e = assertThrows(OrderLimitExceededException.class,
                () -> limits.check(List.of(line("Platter", 1)), MAX_TOTAL_PENNIES + 1));
        assertEquals("total", e.getField());
        assertEquals(100_001L, e.getValue());
        assertEquals(100_000L, e.getLimit());
        assertNull(e.getProductName());
    }

    // ---- the goods the defaults must clear --------------------------------------------------

    @Test
    @DisplayName("P2-CHA-16: a 20-line, 40-unit basket hits no limit")
    void comfortableBasketPasses() {
        assertDoesNotThrow(() -> limits.check(linesOf(20, 2), 40_000L));
    }

    @Test
    @DisplayName("UXT-007 repro basket: about 35 items totalling £198.50 hits no limit")
    void officeLunchPasses() {
        assertDoesNotThrow(() -> limits.check(linesOf(7, 5), 19_850L));
    }

    @Test
    @DisplayName("an empty basket is not this validator's refusal (the DTO's @NotEmpty owns it)")
    void emptyBasketIsNotRefusedHere() {
        assertDoesNotThrow(() -> limits.check(Collections.emptyList(), 0L));
    }

    // ---- configuration ----------------------------------------------------------------------

    @Test
    @DisplayName("a limit below 1 is refused at construction, naming its key, so a misconfigured runtime fails at boot rather than refusing every order")
    void limitBelowOneIsRefusedAtConstruction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new OrderLimits(0, MAX_BASKET_UNITS, MAX_LINES, MAX_TOTAL_PENNIES));
        assertEquals(true, e.getMessage().contains("jtoye.order-limits.max-line-quantity"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new OrderLimits(MAX_LINE_QUANTITY, 0, MAX_LINES, MAX_TOTAL_PENNIES));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderLimits(MAX_LINE_QUANTITY, MAX_BASKET_UNITS, 0, MAX_TOTAL_PENNIES));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderLimits(MAX_LINE_QUANTITY, MAX_BASKET_UNITS, MAX_LINES, 0L));
        // 1 is the smallest usable limit on every axis.
        assertDoesNotThrow(() -> new OrderLimits(1, 1, 1, 1L));
    }
}
