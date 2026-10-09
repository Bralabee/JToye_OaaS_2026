package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The kitchen board's statuses are declared twice, and the two must agree (Phase 37-16, D-11).
 *
 * <p>{@link OrderService#KITCHEN_STATUSES} decides what {@code GET /orders/kitchen} RETURNS; the
 * frontend's {@code KITCHEN_STATUSES} in {@code frontend/lib/kitchen-orders-api.ts} decides whether a
 * status change arriving over STOMP puts a ticket ON the board without asking the API again. If they
 * drift, the board shows one set on a read and another set on a live frame: a PENDING order that the
 * read returns but the socket handler ignores (or the reverse) appears and disappears depending on
 * which path touched it last. Their own comments have said "the two must agree" since #564, and
 * nothing checked it until now.
 *
 * <p>Parsed from the TypeScript SOURCE, the precedent being {@code UnsubscribeLinkRoutingTest}, which
 * reads {@code frontend/app}. A missing or unparseable file FAILS rather than skipping: "could not
 * look" is never "they agree".
 */
class KitchenStatusesParityTest {

    private static final Pattern TS_CONSTANT = Pattern.compile(
            "export\\s+const\\s+KITCHEN_STATUSES\\s*:\\s*OrderStatus\\[\\]\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern TS_STRING = Pattern.compile("\"([A-Z_]+)\"");

    @Test
    @DisplayName("frontend lib/kitchen-orders-api.ts KITCHEN_STATUSES equals OrderService.KITCHEN_STATUSES, in order")
    void frontendAndServerKitchenStatusesAgree() {
        List<String> server = OrderService.KITCHEN_STATUSES.stream().map(Enum::name).toList();
        List<String> frontend = frontendKitchenStatuses();

        assertThat(frontend)
                .as("frontend/lib/kitchen-orders-api.ts KITCHEN_STATUSES must equal OrderService.KITCHEN_STATUSES")
                .containsExactlyElementsOf(server);
    }

    @Test
    @DisplayName("PENDING is a kitchen status on both sides (D-11: new orders reach the kitchen's New lane)")
    void pendingIsAKitchenStatus() {
        assertThat(OrderService.KITCHEN_STATUSES)
                .as("the server's kitchen read must return PENDING orders, or the New lane is always empty")
                .containsExactly(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);
        assertThat(frontendKitchenStatuses())
                .as("the frontend must accept a PENDING frame off the socket, or a new order waits for the next poll")
                .contains("PENDING");
    }

    private static List<String> frontendKitchenStatuses() {
        Path file = repoRoot().resolve("frontend/lib/kitchen-orders-api.ts");
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            fail("could not read %s: %s", file, e.getMessage());
            return List.of();
        }
        Matcher constant = TS_CONSTANT.matcher(source);
        if (!constant.find()) {
            fail("no `export const KITCHEN_STATUSES: OrderStatus[] = [...]` in %s — the parser, not the parity, is broken", file);
        }
        List<String> statuses = new ArrayList<>();
        Matcher literal = TS_STRING.matcher(constant.group(1));
        while (literal.find()) {
            statuses.add(literal.group(1));
        }
        if (statuses.isEmpty()) {
            fail("KITCHEN_STATUSES in %s parsed as empty — refusing to call an empty list 'in agreement'", file);
        }
        return statuses;
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("frontend/lib/kitchen-orders-api.ts"))) {
                return p;
            }
        }
        fail("could not locate the repository root (no frontend/lib/kitchen-orders-api.ts above %s)", dir);
        return dir;
    }
}
