package uk.jtoye.core.testsupport;

import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.security.access.ShopAccessService;

/**
 * Capture / restore / set for {@code ShopAccessService.strictScoping} in tests (Phase 37-02, RWO-004).
 *
 * <p><strong>Why this exists.</strong> Eleven test classes flipped the strict-scoping flag on the
 * context's {@code ShopAccessService} singleton by reflection and then reset it in teardown to a
 * LITERAL {@code false}. That was harmless while {@code false} was also the configured default, but
 * D-06 flips the default to {@code true}: a literal reset would silently re-arm OFF on a bean that
 * booted ON, and every later test sharing that bean would run under the old posture while the suite
 * stayed green — proving nothing about the new default (37-RESEARCH Pitfall 1). The fix is to
 * restore the value the bean was BOOTED with, never a value the test happens to believe is the
 * default.
 *
 * <p>Usage in a test class:
 * <pre>{@code
 * private Object bootedStrictScoping;
 *
 * @BeforeEach void captureStrictScoping() { bootedStrictScoping = StrictScopingGuard.capture(shopAccessService); }
 * @AfterEach  void restoreStrictScoping() { StrictScopingGuard.restore(shopAccessService, bootedStrictScoping); }
 * }</pre>
 * and a test that exercises a specific posture states it at its start with
 * {@link #set(ShopAccessService, boolean)} rather than depending on the default.
 *
 * <p>Every method unwraps the Spring proxy first ({@code ShopAccessService} carries
 * {@code @Cacheable}/{@code @Transactional}, so the injected reference is a CGLIB proxy whose own
 * copy of the field is NOT the one the target reads). A plain, non-proxied instance (a unit test's
 * {@code new ShopAccessService(...)}) is returned unchanged by the unwrap.
 */
public final class StrictScopingGuard {

    private static final String FIELD = "strictScoping";

    private StrictScopingGuard() {
    }

    /**
     * The value the bean currently holds — called from {@code @BeforeEach}, before any test body has
     * touched it, this is the value the context booted with. Returned as {@code Object} so a caller
     * cannot accidentally substitute a literal for it.
     */
    public static Object capture(ShopAccessService service) {
        Object value = ReflectionTestUtils.getField(target(service), FIELD);
        if (!(value instanceof Boolean)) {
            throw new IllegalStateException("ShopAccessService." + FIELD + " is not a boolean (got "
                    + value + ") — the field was renamed or retyped; this guard must follow it");
        }
        return value;
    }

    /**
     * Put back exactly what {@link #capture} returned. Fails loudly when nothing was captured (a
     * missing {@code @BeforeEach}), because silently skipping the restore is the very leak this
     * class exists to close.
     */
    public static void restore(ShopAccessService service, Object captured) {
        if (!(captured instanceof Boolean)) {
            throw new IllegalStateException("StrictScopingGuard.restore called without a captured value ("
                    + captured + ") — capture the booted value in @BeforeEach");
        }
        ReflectionTestUtils.setField(target(service), FIELD, captured);
    }

    /** Set an explicit posture for the test that is exercising it (restored by {@link #restore}). */
    public static void set(ShopAccessService service, boolean value) {
        ReflectionTestUtils.setField(target(service), FIELD, value);
    }

    /** The posture currently in force on the bean's target. */
    public static boolean current(ShopAccessService service) {
        return (Boolean) capture(service);
    }

    private static ShopAccessService target(ShopAccessService service) {
        if (service == null) {
            throw new IllegalStateException("ShopAccessService is null — not injected yet?");
        }
        return AopTestUtils.getUltimateTargetObject(service);
    }
}
