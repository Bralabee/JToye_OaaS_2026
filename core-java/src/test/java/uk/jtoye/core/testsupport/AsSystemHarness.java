package uk.jtoye.core.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that this test class drives the application <strong>as the system</strong>: it calls
 * service methods directly, on a thread with no HTTP request and no {@code Authentication},
 * which is the internal-caller shape — so it says so, exactly as a background entry point would
 * have to (#283).
 *
 * <h2>Why this annotation exists rather than a change to the gate</h2>
 *
 * Before Phase 28, {@code ShopAccessService.isInternalCaller()} granted trust to ANY thread with
 * no {@code Authentication}, so these tests passed by inference. #283 removed that inference.
 * Every class carrying this annotation was failing for exactly one reason — it relied on the
 * removed bypass — and each was checked individually before it was added:
 *
 * <ol>
 *   <li>the gated call is the test's <em>scaffolding</em> (seeding data, or driving a service to
 *       reach the behaviour actually under test), never its <em>subject</em>. None of these
 *       classes asserts an authorization outcome;</li>
 *   <li>none of them asserts a denial — verified before this annotation was applied, so a
 *       class-wide declaration cannot turn a deny-assertion green. The classes that DO assert
 *       denials ({@code ShopAccessFailClosedIntegrationTest},
 *       {@code SystemPrincipalGuardTest}, {@code ShopAccessEnforcementIntegrationTest},
 *       {@code CrossTenantAuthzIntegrationTest}) deliberately do NOT carry it;</li>
 *   <li>the declaration is inert for any test that never reaches the gate — the marker is read
 *       only by {@code isInternalCaller()} — so applying it per class rather than per call site
 *       changes nothing else.</li>
 * </ol>
 *
 * <p><strong>This is a declaration, not a reinstated bypass (T-28-27).</strong> The distinction
 * is that it is visible, enumerable and justified: {@code rg AsSystemHarness core-java/src/test}
 * lists every test that claims system status, which the old implicit rule made impossible to
 * ask. Adding it to a NEW test is a claim that the test represents internal system work — if the
 * test is really about a user's access, give it a principal instead (see
 * {@code ShopAccessFailClosedIntegrationTest#authenticate}).
 *
 * <p><strong>Why not a realm-admin principal instead?</strong> Considered and rejected for these
 * classes: entering the gate with a JWT principal also runs
 * {@code ShopAccessService.onRequest()}, whose throttled {@code user_directory} upsert is a
 * WRITE. That would add rows several of these tests do not expect and would change what they
 * observe — a fix that alters the thing under test. An undeclared-to-declared change leaves the
 * side effects exactly as they were (no principal, so {@code onRequest} still returns early),
 * which is why it is the smaller and more honest edit here.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@ExtendWith(SystemHarnessExtension.class)
public @interface AsSystemHarness {
}
