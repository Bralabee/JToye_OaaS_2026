package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle semantics of the {@link SystemPrincipal} declaration marker (#283).
 *
 * <p>Deliberately separate from {@code SystemPrincipalGuardTest}: this class asserts the
 * marker's OWN contract — scoping, nesting, restore-on-throw, pooled-thread hygiene and
 * non-inheritance — none of which needs a database, a Spring context or the gate. The gate
 * behaviour it enables (deny undeclared, allow declared, and the #284 background guard) is
 * proven end-to-end over real Postgres in {@code SystemPrincipalGuardTest}.
 *
 * <p>Each property here is one of the ways a "declared system scope" silently becomes a
 * permanent bypass, which is exactly what #283 exists to stop.
 */
class SystemPrincipalTest {

    @AfterEach
    void ensureNoLeakBetweenTests() {
        assertThat(SystemPrincipal.isSystem())
                .as("a test must never leave a system declaration behind for the next one")
                .isFalse();
    }

    @Test
    void anUndeclaredThreadIsNotSystem() {
        assertThat(SystemPrincipal.isSystem())
                .as("the default posture is UNDECLARED — trust is never the resting state")
                .isFalse();
    }

    @Test
    void theDeclarationCoversTheBodyAndNothingAfterIt() {
        assertThat(SystemPrincipal.isSystem()).isFalse();

        SystemPrincipal.asSystem(() -> {
            assertThat(SystemPrincipal.isSystem())
                    .as("inside asSystem the thread is declared system")
                    .isTrue();
        });

        assertThat(SystemPrincipal.isSystem())
                .as("the declaration ends with the body — a scope that outlives its work is a bypass")
                .isFalse();
    }

    /**
     * The nesting arm the plan calls out: an INNER {@code asSystem} returning must not drop
     * the OUTER declaration. An unconditional clear in the {@code finally} would pass every
     * other test in this class and fail only this one.
     */
    @Test
    void anInnerScopeReturningLeavesTheOuterDeclarationIntact() {
        SystemPrincipal.asSystem(() -> {
            assertThat(SystemPrincipal.isSystem()).isTrue();

            SystemPrincipal.asSystem(() -> assertThat(SystemPrincipal.isSystem()).isTrue());

            assertThat(SystemPrincipal.isSystem())
                    .as("the inner scope restored the PRIOR value, so the outer declaration survives")
                    .isTrue();
        });

        assertThat(SystemPrincipal.isSystem())
                .as("the outermost scope still ends the declaration")
                .isFalse();
    }

    @Test
    void theDeclarationIsRestoredWhenTheBodyThrows() {
        assertThatThrownBy(() -> SystemPrincipal.asSystem(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(SystemPrincipal.isSystem())
                .as("an exception escaping the body must not strand the declaration on the thread")
                .isFalse();
    }

    @Test
    void anInnerScopeThrowingLeavesTheOuterDeclarationIntact() {
        SystemPrincipal.asSystem(() -> {
            assertThatThrownBy(() -> SystemPrincipal.asSystem(() -> {
                throw new IllegalStateException("inner boom");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(SystemPrincipal.isSystem())
                    .as("an inner failure must not revoke the outer scope's declaration mid-body")
                    .isTrue();
        });
    }

    @Test
    void theSupplierOverloadReturnsTheBodysValue() {
        String result = SystemPrincipal.asSystem(() -> SystemPrincipal.isSystem() ? "declared" : "undeclared");

        assertThat(result).isEqualTo("declared");
        assertThat(SystemPrincipal.isSystem()).isFalse();
    }

    /**
     * The outermost exit {@link ThreadLocal#remove()}s the entry rather than setting it to
     * {@code FALSE}. Both read identically through {@link SystemPrincipal#isSystem()}, so the
     * distinction is asserted directly on the ThreadLocal: a pooled thread must be left
     * carrying no entry at all, not a lingering {@code FALSE} that pins a map entry for the
     * life of the pool.
     */
    @Test
    void theOutermostScopeRemovesTheEntryRatherThanSettingItFalse() {
        SystemPrincipal.asSystem(() -> { /* declared, then unwound */ });

        @SuppressWarnings("unchecked")
        ThreadLocal<Boolean> marker =
                (ThreadLocal<Boolean>) ReflectionTestUtils.getField(SystemPrincipal.class, "SYSTEM");

        assertThat(marker).isNotNull();
        assertThat(marker.get())
                .as("the entry is removed on the outermost exit, so a pooled thread is left as it was found")
                .isNull();
    }

    /**
     * The marker is a plain {@link ThreadLocal}, NOT an {@code InheritableThreadLocal}. A
     * thread spawned from inside a declared scope must start UNDECLARED — otherwise one
     * legitimate {@code asSystem} would silently hand the bypass to every thread it forked,
     * which is the same "trust by inheritance" mistake #283 removes in a different shape.
     */
    @Test
    void aSpawnedThreadDoesNotInheritTheDeclaration() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> childSawDeclaration =
                    SystemPrincipal.asSystem(() -> pool.submit(SystemPrincipal::isSystem));

            assertThat(childSawDeclaration.get(10, TimeUnit.SECONDS))
                    .as("a child thread must NOT inherit the parent's system declaration")
                    .isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
