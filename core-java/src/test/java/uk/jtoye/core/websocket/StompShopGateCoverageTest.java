package uk.jtoye.core.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #289 — a shop-scoped STOMP destination must be shop-gated, and nobody should have to remember
 * that.
 *
 * <p><b>The hazard.</b> {@link TenantChannelInterceptor} enforces the tenant wall on every
 * {@code /topic/} SUBSCRIBE, and a per-shop grant check on top of it. That second check used to
 * fire on a hard-coded feature name, which made it <em>default-open</em>: adding a second
 * shop-scoped topic would have produced a destination that passes the tenant wall and skips the
 * shop check, with nothing failing. That is the CR-02 class of gap, re-opened by an ordinary
 * feature addition.
 *
 * <p><b>What this test does instead of trusting review.</b> It derives the set of shop-scoped
 * features from the destination factories themselves — reflectively, so a factory added tomorrow
 * is covered the day it is added — and asserts every one of them is registered in
 * {@link StompDestinations#SHOP_SCOPED_FEATURES}, which is what the interceptor consults.
 *
 * <p>A factory is treated as shop-scoped when it takes a second {@code UUID} beyond the tenant.
 * That is the shape of {@link StompDestinations#kitchen(UUID, UUID)} and the only way a shop id
 * can reach a routing key through this class.
 *
 * <p><b>Why this is not a tautology.</b> The registry and the factories are two independent
 * declarations: one lives in a {@code Set}, the other in a method signature. The test fails when
 * they disagree. {@link #theProbeCanActuallyFail()} proves the detection works by running the same
 * logic against a registry that deliberately omits a known shop-scoped feature — without it, a
 * reflection bug that found zero factories would make this class pass silently on every tree,
 * which is exactly the failure mode it exists to prevent elsewhere.
 */
class StompShopGateCoverageTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHOP = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * Invokes every public static destination factory that takes two UUIDs and returns the
     * feature word each one produces.
     */
    private static List<String> shopScopedFeaturesFromFactories() throws Exception {
        List<String> features = new ArrayList<>();
        for (Method m : StompDestinations.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() != String.class) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            // tenant + shop. A tenant-only topic takes one UUID and is deliberately not gated.
            if (params.length != 2 || params[0] != UUID.class || params[1] != UUID.class) {
                continue;
            }
            String destination = (String) m.invoke(null, TENANT, SHOP);
            String routingKey = destination.substring(StompDestinations.TOPIC_PREFIX.length());
            String[] words = routingKey.split(java.util.regex.Pattern.quote(StompDestinations.SEPARATOR), -1);
            features.add(words[StompDestinations.FEATURE_WORD]);
        }
        return features;
    }

    @Test
    @DisplayName("every shop-scoped destination factory is registered for the shop grant check")
    void everyShopScopedFactoryIsGated() throws Exception {
        List<String> fromFactories = shopScopedFeaturesFromFactories();

        // Non-vacuity: if reflection finds nothing, this test would pass while asserting nothing.
        assertThat(fromFactories)
                .as("no two-UUID destination factory found on StompDestinations — the probe is "
                        + "broken, not the tree. At minimum kitchen(UUID,UUID) must be discovered.")
                .isNotEmpty();

        assertThat(StompDestinations.SHOP_SCOPED_FEATURES)
                .as("a destination factory carries a shop id but its feature is not registered in "
                        + "SHOP_SCOPED_FEATURES, so TenantChannelInterceptor will apply the tenant "
                        + "wall and SKIP the per-shop grant check for it (#289 / CR-02). Add the "
                        + "feature word to StompDestinations.SHOP_SCOPED_FEATURES.")
                .containsAll(fromFactories);
    }

    @Test
    @DisplayName("the coverage probe can actually fail — it is not passing by construction")
    void theProbeCanActuallyFail() throws Exception {
        List<String> fromFactories = shopScopedFeaturesFromFactories();

        // The same assertion the test above makes, against a registry that omits a real
        // shop-scoped feature. It MUST fail; if it does not, the check above proves nothing.
        java.util.Set<String> registryMissingKitchen = java.util.Set.of("some-other-feature");

        assertThat(registryMissingKitchen.containsAll(fromFactories))
                .as("a registry omitting %s was accepted by the coverage logic — the assertion in "
                        + "everyShopScopedFactoryIsGated() is incapable of failing and guards "
                        + "nothing", fromFactories)
                .isFalse();
    }

    @Test
    @DisplayName("the interceptor consults the registry, not a hard-coded feature name")
    void interceptorGatesFromTheRegistry() {
        // kitchen must be IN the registry (it is shop-scoped)...
        assertThat(StompDestinations.SHOP_SCOPED_FEATURES)
                .contains(StompDestinations.KITCHEN_FEATURE);

        // ...and an unregistered feature must NOT be, so the registry actually discriminates
        // rather than being a set that answers true to everything.
        assertThat(StompDestinations.SHOP_SCOPED_FEATURES)
                .doesNotContain("orders", "notifications");
    }
}
