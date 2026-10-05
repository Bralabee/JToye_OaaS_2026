package uk.jtoye.core.boot4;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads the 38-01 {@code jackson2-golden/amqp} and {@code jackson2-golden/outbox} fixtures and
 * compares events read from them (Phase 38, plan 38-08).
 *
 * <p>Those fixtures are what a Boot-3.5 pod left in flight at deploy time: message bodies on the
 * broker with their properties, and PENDING outbox payload rows in Postgres. A missing fixture
 * FAILS the calling test. It never skips, because a skipped compatibility case is the silent
 * dead-letter this plan exists to rule out.
 *
 * <p>No import from either Jackson line: the helper only moves bytes and compares values.
 */
final class InFlightFixtures {

    private static final String ROOT = "jackson2-golden/";

    private InFlightFixtures() {
    }

    /** The bytes of a fixture, by path relative to {@code jackson2-golden/}. A missing file fails. */
    static byte[] bytes(String relativePath) {
        try (InputStream in = InFlightFixtures.class.getClassLoader().getResourceAsStream(ROOT + relativePath)) {
            if (in == null) {
                fail("fixture " + ROOT + relativePath + " is missing from the test classpath — a missing "
                        + "in-flight fixture fails, it never skips");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + relativePath, e);
        }
    }

    /** The outbox payload row a Boot-3.5 publisher wrote for {@code name}. */
    static String outboxPayload(String name) {
        return new String(bytes("outbox/" + name + ".json"), StandardCharsets.UTF_8);
    }

    /**
     * The AMQP message a Boot-3.5 converter wrote for {@code name}: the body bytes verbatim, the
     * {@code contentType} and {@code contentEncoding} properties, and EVERY other row of
     * {@code .headers.tsv} as a header (that is where {@code __TypeId__} travels).
     */
    static Message amqpMessage(String name) {
        return new Message(bytes("amqp/" + name + ".body"), amqpProperties(name));
    }

    /** The properties half of {@link #amqpMessage(String)}, so a test can alter a copy. */
    static MessageProperties amqpProperties(String name) {
        MessageProperties props = new MessageProperties();
        List<String> seen = new ArrayList<>();
        for (String line : new String(bytes("amqp/" + name + ".headers.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length != 2) {
                fail("malformed header row in amqp/" + name + ".headers.tsv: " + line);
            }
            seen.add(cols[0]);
            switch (cols[0]) {
                case "contentType" -> props.setContentType(cols[1]);
                case "contentEncoding" -> props.setContentEncoding(cols[1]);
                default -> props.setHeader(cols[0], cols[1]);
            }
        }
        assertTrue(seen.contains("__TypeId__"),
                "amqp/" + name + ".headers.tsv carries no __TypeId__ row, rows were " + seen);
        return props;
    }

    /**
     * Asserts {@code actual} is the same event as {@code expected}: same record class, every
     * component equal, except that {@link OffsetDateTime} components are compared BY INSTANT.
     *
     * <p>Instant equality is the contract, not a loosening. The Boot-3.5 AMQP converter wrote dates
     * as epoch decimals and dropped the offset, so a message written at +01:00 reads back at UTC.
     * The instant, to the nanosecond, must survive; a one-nanosecond shift fails (see the negative
     * controls in {@link AmqpJackson2CompatibilityTest}).
     */
    static void assertSameEvent(Object expected, Object actual) {
        assertNotNull(actual, "no event was produced");
        assertEquals(expected.getClass(), actual.getClass(), "event type");
        RecordComponent[] components = expected.getClass().getRecordComponents();
        assertNotNull(components, expected.getClass() + " is not a record — compare it explicitly");
        for (RecordComponent component : components) {
            Object e = value(component, expected);
            Object a = value(component, actual);
            if (e instanceof OffsetDateTime et && a instanceof OffsetDateTime at) {
                assertTrue(et.isEqual(at), component.getName() + ": expected instant " + et + " but was " + at);
            } else {
                assertEquals(e, a, component.getName());
            }
        }
    }

    private static Object value(RecordComponent component, Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + component.getName(), e);
        }
    }
}
