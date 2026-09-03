package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COR-5 (QA-council 20260902-134741) — the ONE order-number generator, and its fault-injection
 * seam.
 *
 * <p><b>What this file is for, stated plainly.</b> Before the extraction the generator was a
 * byte-identical private method in two services and there was no way to make it collide on
 * purpose. The report's collision claim ("1000 orders generated, all unique") was therefore a
 * proof with no power: {@code probes/oracle-ordernumber.sh} measured that a 1,000-sample uniqueness
 * test detects 0/400 injected collisions at an 8-hex suffix and 400/400 at 4 hex, so the test
 * cannot fail at the suffix width actually shipped. The suffix supplier below is the seam that
 * replaces luck with determinism.
 *
 * <p><b>What the collision arm asserts, and what it deliberately does not.</b> Per adjudication
 * A10 the generator does NOT pre-check {@code existsByOrderNumber} (RLS-blind under the DML-only
 * runtime role, both dev tenants share the prefix {@code 00000000}, and it is a TOCTOU race
 * regardless) and does NOT retry (that needs {@code REQUIRES_NEW}, which would move the
 * transaction boundary of the whole money path). So the arm asserts the true current contract:
 * a pinned suffix produces a genuinely COLLIDING number, and the database's
 * {@code uk_orders_order_number} unique constraint is the sole defence — which the handler chain
 * turns into HTTP 409, self-recovering on retry, not a 500 with a lost order.
 */
class OrderNumberGeneratorTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TENANT_B = UUID.fromString("99999999-8888-7777-6666-555555555555");

    /** The format every consumer parses: emails, the tracking deep-link, the public endpoint. */
    private static final Pattern FORMAT =
            Pattern.compile("^ORD-[0-9A-F]{8}-[0-9]{8}-[0-9A-F]{8}$");

    @Test
    @DisplayName("COR-5: a PINNED suffix collides deterministically — no luck, no 1-in-4.3-billion wait")
    void pinnedSuffixCollidesDeterministically() {
        OrderNumberGenerator pinned = new OrderNumberGenerator(() -> "DEADBEEF");

        String first = pinned.generate(TENANT_A);
        String second = pinned.generate(TENANT_A);

        assertEquals(first, second,
                "COR-5: the seam must be able to force a collision, or the collision path is untestable");
        assertTrue(FORMAT.matcher(first).matches(),
                "a collision must still be a well-formed order number — the DB constraint is what rejects it");
    }

    @Test
    @DisplayName("COR-5: the generator draws EXACTLY ONE suffix per order — no hidden retry today (A10)")
    void drawsExactlyOneSuffixPerOrder() {
        AtomicInteger draws = new AtomicInteger();
        OrderNumberGenerator counted = new OrderNumberGenerator(() -> {
            draws.incrementAndGet();
            return "DEADBEEF";
        });

        counted.generate(TENANT_A);

        // This is the falsifiable form of "the retry is DEFERRED". If someone lands a bounded
        // retry loop without revisiting the transaction-boundary reasoning in the class javadoc,
        // this number moves and the test says so.
        assertEquals(1, draws.get(),
                "A10: no retry is implemented — a second draw here means a retry landed without the "
                        + "REQUIRES_NEW analysis it requires");
    }

    @Test
    @DisplayName("COR-5: the extracted generator preserves the shipped format exactly")
    void preservesTheShippedFormat() {
        String number = new OrderNumberGenerator().generate(TENANT_A);

        assertTrue(FORMAT.matcher(number).matches(), "format regression: " + number);
        assertTrue(number.startsWith("ORD-11111111-"),
                "the tenant prefix is the first 8 hex of the tenant UUID, upper-case: " + number);
        assertTrue(number.contains("-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-"),
                "the date part is today in YYYYMMDD: " + number);
    }

    @Test
    @DisplayName("COR-5: different tenants get different prefixes from the SAME generator instance")
    void differentTenantsGetDifferentPrefixes() {
        OrderNumberGenerator generator = new OrderNumberGenerator();

        String a = generator.generate(TENANT_A);
        String b = generator.generate(TENANT_B);

        assertTrue(a.startsWith("ORD-11111111-"));
        assertTrue(b.startsWith("ORD-99999999-"));
        assertNotEquals(a.substring(0, 12), b.substring(0, 12));
    }

    /**
     * The default supplier still produces distinct suffixes. Kept SMALL on purpose: this arm is a
     * smoke check, not the collision proof — the collision proof is the pinned-supplier arm above.
     * A large-N uniqueness loop here would recreate exactly the powerless test the report shipped.
     */
    @Test
    @DisplayName("COR-5: the default supplier is not constant (smoke — the collision proof is the pinned arm)")
    void defaultSupplierIsNotConstant() {
        OrderNumberGenerator generator = new OrderNumberGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(generator.generate(TENANT_A));
        }
        assertEquals(50, seen.size(), "the default suffix source must vary per call");
    }
}
