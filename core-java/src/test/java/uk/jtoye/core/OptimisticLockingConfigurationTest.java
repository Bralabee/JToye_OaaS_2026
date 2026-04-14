package uk.jtoye.core;

import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.shop.Shop;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection-level guard for Fix #7 (optimistic locking).
 *
 * <p>A full concurrency test needs a real database (see
 * {@code @DataJpaTest} integration tests under the {@code testcontainers}
 * tag). This unit-level test is a cheap regression fence: it fails loudly
 * if anyone removes the {@code @Version} field from {@link Order} or
 * {@link Shop}, which would silently reintroduce last-writer-wins on
 * stock adjustments and shop configuration updates.
 */
class OptimisticLockingConfigurationTest {

    @Test
    @DisplayName("Order entity carries @Version on a Long version field")
    void orderHasVersionField() throws NoSuchFieldException {
        Field field = Order.class.getDeclaredField("version");
        assertNotNull(field.getAnnotation(Version.class),
                "Order.version must be annotated @jakarta.persistence.Version");
        assertEquals(Long.class, field.getType(),
                "Order.version should be Long (JPA's preferred type) — got " + field.getType());
    }

    @Test
    @DisplayName("Shop entity carries @Version on a Long version field")
    void shopHasVersionField() throws NoSuchFieldException {
        Field field = Shop.class.getDeclaredField("version");
        assertNotNull(field.getAnnotation(Version.class),
                "Shop.version must be annotated @jakarta.persistence.Version");
        assertEquals(Long.class, field.getType(),
                "Shop.version should be Long (JPA's preferred type) — got " + field.getType());
    }

    @Test
    @DisplayName("Order.getVersion() exposes a read accessor without a setter")
    void orderVersionHasGetterNoSetter() throws NoSuchMethodException {
        assertNotNull(Order.class.getMethod("getVersion"));
        // No setter should exist — version is JPA-managed
        boolean hasSetter = false;
        try {
            Order.class.getMethod("setVersion", Long.class);
            hasSetter = true;
        } catch (NoSuchMethodException ignored) {
            // expected
        }
        assertTrue(!hasSetter, "Order must not expose setVersion — JPA owns this field");
    }

    @Test
    @DisplayName("Shop.getVersion() exposes a read accessor without a setter")
    void shopVersionHasGetterNoSetter() throws NoSuchMethodException {
        assertNotNull(Shop.class.getMethod("getVersion"));
        boolean hasSetter = false;
        try {
            Shop.class.getMethod("setVersion", Long.class);
            hasSetter = true;
        } catch (NoSuchMethodException ignored) {
            // expected
        }
        assertTrue(!hasSetter, "Shop must not expose setVersion — JPA owns this field");
    }
}
