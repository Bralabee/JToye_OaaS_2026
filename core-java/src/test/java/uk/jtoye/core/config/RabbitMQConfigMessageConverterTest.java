package uk.jtoye.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import uk.jtoye.core.order.OrderStateChangeEvent;
import uk.jtoye.fixture.UntrustedFixtureListener;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the trusted-package allowlist on {@link RabbitMQConfig#jsonMessageConverter()} (27-05).
 *
 * <p><b>Why this exists.</b> Outbound webhook fan-out was dead from the day it shipped: the bean
 * was {@code new Jackson2JsonMessageConverter()}, whose type mapper trusts only
 * {@code [java.util, java.lang]}, so every message routed to {@code webhook.deliveries} failed
 * {@code __TypeId__} resolution and dead-lettered. Phase 22's suite was green throughout, because
 * it invoked {@code WebhookFanoutListener} directly rather than through the converter.
 *
 * <p><b>The failure is silent and shape-dependent.</b> A single-method {@code @RabbitListener} with
 * a typed parameter never consults the type mapper — Spring infers the target type from the method
 * signature — so KDS, media processing and notifications all worked. Only a class-level
 * {@code @RabbitListener} + {@code @RabbitHandler} listener must resolve the type to pick a handler.
 * {@link #everyMultiHandlerPayloadTypeIsTrusted()} is therefore the load-bearing test: it fails the
 * build when a new multi-handler payload package is introduced without being allowlisted, rather
 * than letting it surface as production dead letters weeks later.
 */
class RabbitMQConfigMessageConverterTest {

    private static final String ROOT_PACKAGE = "uk.jtoye.core";
    private static final String UNTRUSTED_MARKER = "not in the trusted packages";

    private final MessageConverter converter = new RabbitMQConfig().jsonMessageConverter();

    // ---------------------------------------------------------------- helpers

    /** Outcome of asking the configured converter to resolve a {@code __TypeId__} to a class. */
    private enum Trust {
        /** The type mapper accepted the class (deserialization may still fail for other reasons). */
        TRUSTED,
        /** The type mapper refused: the class's package is not on the allowlist. */
        UNTRUSTED
    }

    /**
     * Ask {@code converter} to resolve {@code className} from a {@code __TypeId__} header.
     *
     * <p>The body is {@code {}} on purpose. We are asserting the <em>trust</em> decision, not that
     * the payload is well-formed, so anything that is not a trusted-packages rejection counts as
     * {@link Trust#TRUSTED} — that keeps this test from coupling to any event's field shape.
     */
    private static Trust resolve(MessageConverter converter, String className) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__", className);
        Message message = new Message("{}".getBytes(StandardCharsets.UTF_8), props);
        try {
            converter.fromMessage(message);
            return Trust.TRUSTED;
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (String.valueOf(t.getMessage()).contains(UNTRUSTED_MARKER)) {
                    return Trust.UNTRUSTED;
                }
            }
            return Trust.TRUSTED;
        }
    }

    /** Every payload package reachable through a class-level {@code @RabbitListener} in the codebase. */
    private static Set<String> discoverMultiHandlerPayloadPackages() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RabbitListener.class));

        Set<String> packages = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ROOT_PACKAGE)) {
            Class<?> listener = ClassUtils.resolveClassName(
                    definition.getBeanClassName(), RabbitMQConfigMessageConverterTest.class.getClassLoader());
            packages.addAll(handlerPayloadPackagesOf(listener));
        }
        return packages;
    }

    /**
     * Payload packages of {@code listener}'s {@code @RabbitHandler} methods.
     *
     * <p>Returns empty for a single-method listener (no {@code @RabbitHandler}), which is correct:
     * those never consult the type mapper. The {@code isDefault} catch-all is skipped — its
     * {@code Object} parameter is in {@code java.lang}, which is trusted by default.
     */
    private static Set<String> handlerPayloadPackagesOf(Class<?> listener) {
        Set<String> packages = new LinkedHashSet<>();
        for (Method method : listener.getDeclaredMethods()) {
            RabbitHandler handler = method.getAnnotation(RabbitHandler.class);
            if (handler == null || handler.isDefault() || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> payload = method.getParameterTypes()[0];
            if (payload != Object.class) {
                packages.add(payload.getPackageName());
            }
        }
        return packages;
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("converter resolves an application event from __TypeId__ (the fix)")
    void resolvesApplicationEventType() {
        assertEquals(Trust.TRUSTED, resolve(converter, OrderStateChangeEvent.class.getName()),
                "OrderStateChangeEvent must resolve — every webhook dead letter was this exact rejection");
    }

    @Test
    @DisplayName("converter still REJECTS a class outside the allowlist (the allowlist is not '*')")
    void rejectsClassOutsideAllowlist() {
        assertEquals(Trust.UNTRUSTED, resolve(converter, "javax.naming.ldap.Rdn"),
                "a class outside the allowlist must stay untrusted — trust-all is a gadget surface");
        assertEquals(Trust.UNTRUSTED, resolve(converter, "uk.jtoye.core.webhook.WebhookDelivery"),
                "even in-project packages are untrusted unless they carry a broker payload type");
    }

    @Test
    @DisplayName("allowlist is scoped, never trust-all — '*' clears the allowlist entirely")
    void allowlistIsNotTrustAll() {
        for (String pkg : RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES) {
            assertFalse("*".equals(pkg),
                    "'*' clears DefaultJackson2JavaTypeMapper's allowlist and trusts every class on the classpath");
            assertTrue(pkg.startsWith(ROOT_PACKAGE + "."),
                    "trusted package must be inside " + ROOT_PACKAGE + ", was: " + pkg);
        }
    }

    /**
     * D-03: the defect class cannot return silently.
     *
     * <p>Fails when a class-level {@code @RabbitListener} gains a {@code @RabbitHandler} whose
     * payload package is not allowlisted — the exact change that would silently resume
     * dead-lettering in production.
     */
    @Test
    @DisplayName("D-03 guard: every multi-handler payload package is on the allowlist")
    void everyMultiHandlerPayloadTypeIsTrusted() {
        Set<String> discovered = discoverMultiHandlerPayloadPackages();

        assertFalse(discovered.isEmpty(),
                "VOID: scanned " + ROOT_PACKAGE + " and found no @RabbitHandler payload types. "
                        + "An empty discovery is never a pass — the scan itself is broken.");

        Set<String> allowlist = Set.of(RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES);
        for (String pkg : discovered) {
            assertTrue(allowlist.contains(pkg),
                    "@RabbitHandler payload package '" + pkg + "' is NOT in "
                            + "RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES. Messages of this type will fail "
                            + "__TypeId__ resolution and dead-letter silently. Add it to the constant.");
        }
    }

    /**
     * Positive control for the D-03 guard — proves it can actually fail.
     *
     * <p>Without this, {@link #everyMultiHandlerPayloadTypeIsTrusted()} might be passing only
     * because the scan finds nothing or the assertion cannot fire. Here the same two mechanisms are
     * run against a listener whose payload is deliberately outside the allowlist.
     */
    @Test
    @DisplayName("D-03 guard fails on an untrusted payload type (positive control)")
    void guardDetectsUntrustedPayloadType() {
        Set<String> packages = handlerPayloadPackagesOf(UntrustedFixtureListener.class);

        assertEquals(Set.of("java.time"), packages,
                "the fixture's handler payload package must be discovered by the same helper the guard uses");
        assertFalse(Set.of(RabbitMQConfig.TRUSTED_PAYLOAD_PACKAGES).containsAll(packages),
                "the guard's allowlist check must reject this fixture — otherwise the guard cannot fail");
        assertEquals(Trust.UNTRUSTED, resolve(converter, "java.time.Year"),
                "and the converter must genuinely refuse it at runtime, not just fail a static check");
    }

    /**
     * The scan must not see its own control fixture.
     *
     * <p>{@link UntrustedFixtureListener} originally sat beside this test in {@code uk.jtoye.core},
     * and {@link #everyMultiHandlerPayloadTypeIsTrusted()} duly failed on it — a rule firing on its
     * own definition. Moving it to {@code uk.jtoye.fixture} fixed that, and this test pins the
     * arrangement so it cannot silently drift back.
     */
    @Test
    @DisplayName("the D-03 scan root excludes the control fixture (no self-trip)")
    void scanRootExcludesTheControlFixture() {
        assertFalse(UntrustedFixtureListener.class.getPackageName().startsWith(ROOT_PACKAGE),
                "the control fixture must live outside the scanned root, else the guard fails on itself");
        assertFalse(discoverMultiHandlerPayloadPackages().contains("java.time"),
                "the production scan must not pick up the fixture's payload package");
    }
}
