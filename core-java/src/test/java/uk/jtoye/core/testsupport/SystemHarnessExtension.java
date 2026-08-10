package uk.jtoye.core.testsupport;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import uk.jtoye.core.security.access.SystemPrincipal;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs each lifecycle method and each test of an {@link AsSystemHarness} class inside a declared
 * {@link SystemPrincipal#asSystem} scope. See {@link AsSystemHarness} for the reasoning and the
 * rules for applying it — this class is only the mechanism.
 *
 * <p><strong>Why an interceptor rather than a {@code @BeforeEach} set / {@code @AfterEach}
 * clear.</strong> {@link SystemPrincipal} deliberately exposes no unbalanced {@code begin()} /
 * {@code end()} pair — an API that can leave a marker set is precisely how a declaration outlives
 * its scope and becomes the permanent bypass #283 removed. {@link InvocationInterceptor} wraps
 * the invocation, so the production API stays scoped-only and the test harness gets the same
 * save/restore guarantee every other caller gets.
 *
 * <p><strong>Throwables are rethrown unchanged.</strong> The body cannot throw a checked exception
 * through {@link SystemPrincipal#asSystem}, so it is captured and rethrown outside the scope
 * rather than wrapped. Wrapping would turn every {@code AssertionError} into a nested
 * {@code RuntimeException} and destroy the failure messages this suite is read through.
 */
public class SystemHarnessExtension implements InvocationInterceptor {

    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        proceedAsSystem(invocation);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        proceedAsSystem(invocation);
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        proceedAsSystem(invocation);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        proceedAsSystem(invocation);
    }

    /**
     * Proceeds EXACTLY ONCE inside the declared scope (JUnit fails the run if an invocation is
     * skipped or proceeded twice), capturing any {@link Throwable} so it can be rethrown intact
     * once the scope has unwound.
     */
    private static void proceedAsSystem(Invocation<Void> invocation) throws Throwable {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        SystemPrincipal.asSystem(() -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        Throwable t = thrown.get();
        if (t != null) {
            throw t;
        }
    }
}
