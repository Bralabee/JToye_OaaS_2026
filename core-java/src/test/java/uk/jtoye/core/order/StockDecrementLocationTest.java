package uk.jtoye.core.order;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Code-search regression guard for CQ-01 Success Criterion #2 —
 * "Stock decrement lives inside the OrderStateMachine CONFIRM transition,
 *  not in {@code OrderService.createOrder}".
 *
 * <p>Phase 14 interprets "inside the CONFIRM transition" as "inside the
 * CONFIRMED branch of {@code OrderService.transitionOrder}"
 * (RESEARCH §4 Option C — dedicated StockService called from transitionOrder,
 * not a state-machine action bean).
 *
 * <p>Fast source-level guard — no Spring context, no Testcontainers. Runs in
 * the default unit suite. If a future refactor moves stock decrement away
 * from the CONFIRMED branch or reintroduces the silent Math.max clamp, this
 * test fails loudly.
 */
class StockDecrementLocationTest {

    private static final Path ORDER_SERVICE = resolveOrderServicePath();

    private static Path resolveOrderServicePath() {
        Path p = Paths.get("src/main/java/uk/jtoye/core/order/OrderService.java");
        if (!Files.exists(p)) {
            p = Paths.get("core-java/src/main/java/uk/jtoye/core/order/OrderService.java");
        }
        return p;
    }

    @Test
    void decrementLivesInTransitionOrderCONFIRMEDBranch() throws Exception {
        assertThat(Files.exists(ORDER_SERVICE))
                .as("OrderService.java must be at the expected path (a refactor relocation "
                        + "would make doesNotContain assertions pass vacuously)")
                .isTrue();

        String src = Files.readString(ORDER_SERVICE);

        // Positive: new delegation present
        assertThat(src)
                .as("OrderService must delegate stock decrement to StockService")
                .contains("stockService.decrementForOrder");

        // Negative: old helper and silent clamp are gone
        assertThat(src)
                .as("Old in-place adjustStockInBatch helper must be deleted")
                .doesNotContain("adjustStockInBatch");
        assertThat(src)
                .as("Silent Math.max(0, ...) stock clamp must be gone")
                .doesNotContain("Math.max(0,");

        // Ordering: decrement must come AFTER sendEvent (state-machine gate)
        //          and BEFORE the transitionOrder save (ordering fix — RESEARCH §11 Q7)
        int sendEventIdx = src.indexOf("sendEvent(orderId, oldStatus, event)");
        int decrementIdx = src.indexOf("stockService.decrementForOrder");
        int saveIdx = src.lastIndexOf("orderRepository.save(order)");
        assertThat(sendEventIdx).as("sendEvent call present").isGreaterThan(0);
        assertThat(decrementIdx).as("decrement call present and after sendEvent")
                .isGreaterThan(sendEventIdx);
        assertThat(saveIdx).as("a final save present AND after the decrement call")
                .isGreaterThan(decrementIdx);
    }

    @Test
    void createOrderDoesNotCallDecrementForOrder() throws Exception {
        String src = Files.readString(ORDER_SERVICE);

        // Extract createOrder method body by brace counting from the signature.
        int start = src.indexOf("public OrderDto createOrder(");
        assertThat(start).as("createOrder method signature present").isGreaterThan(0);

        String createOrderBody = extractMethodBody(src, start);

        assertThat(createOrderBody)
                .as("createOrder must NOT decrement stock — decrement happens on CONFIRM transition")
                .doesNotContain("stockService.decrementForOrder")
                .doesNotContain("adjustStockInBatch");
    }

    private String extractMethodBody(String src, int start) {
        int brace = src.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace, i + 1);
            }
        }
        return src.substring(brace);
    }
}
