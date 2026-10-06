package uk.jtoye.core.boot4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT4-10 (D-03): every {@code org.springframework.security.access} class that
 * spring-statemachine-core 4.0.2 references resolves on the PRODUCTION runtime classpath.
 *
 * <p><b>Why.</b> Spring Security 7 moved {@code AccessDecisionManager}, the voters and
 * {@code ConfigAttribute} out of spring-security-core into a separate {@code spring-security-access}
 * artifact. statemachine-core 4.0.2 was built against Security 6 and still references them from
 * {@code AbstractStateMachineFactory}, {@code StateMachineConfigurationBuilder},
 * {@code ConfigurationData} and its security package. They load lazily, so our machines run
 * without them; but {@code withSecurity()} or event/transition security would throw
 * {@code NoClassDefFoundError}. D-03 keeps the library and declares {@code spring-security-access};
 * this test is what notices if that declaration is ever dropped.
 *
 * <p><b>Why a separate class loader.</b> The test classpath is not what ships. The loader below is
 * built ONLY from {@code -Djtoye.productionRuntimeClasspath}, which {@code tasks.test} in
 * core-java/build.gradle.kts sets to the resolved {@code runtimeClasspath}, over the platform
 * loader. Without the property the class fails closed. The same pattern is used by
 * {@code WorkloadIdentityCredentialBuildTest}.
 *
 * <p><b>Where the list comes from.</b> {@link #REFERENCED} is the set of distinct targets of this
 * jdeps run (38-04, raw output in {@code evidence/38-04-suite-and-liveness.txt}):
 * <pre>
 * jdeps --multi-release 25 -verbose:class -cp "$PRODUCTION_RUNTIME_CLASSPATH" \
 *       spring-statemachine-core-4.0.2.jar | grep 'org.springframework.security.access'
 * </pre>
 * 37 reference lines, 16 distinct classes: 9 provided by spring-security-access-7.1.1.jar and 7 by
 * spring-security-core-7.1.1.jar. If the statemachine version changes, re-run the command and
 * replace the list.
 *
 * <p><b>It can fail:</b> with spring-security-access excluded from runtimeClasspath, the 9 classes
 * it provides fail to load and the test names them (38-04 evidence, arm "access").
 */
class StatemachineSecurityAccessTest {

    private static final String PRODUCTION_CLASSPATH_PROPERTY = "jtoye.productionRuntimeClasspath";

    /** Distinct jdeps targets, see the class Javadoc. Providing jar noted per group. */
    static final List<String> REFERENCED = List.of(
            // spring-security-access-7.1.1.jar
            "org.springframework.security.access.AccessDecisionManager",
            "org.springframework.security.access.AccessDecisionVoter",
            "org.springframework.security.access.ConfigAttribute",
            "org.springframework.security.access.SecurityConfig",
            "org.springframework.security.access.vote.AbstractAccessDecisionManager",
            "org.springframework.security.access.vote.AffirmativeBased",
            "org.springframework.security.access.vote.ConsensusBased",
            "org.springframework.security.access.vote.RoleVoter",
            "org.springframework.security.access.vote.UnanimousBased",
            // spring-security-core-7.1.1.jar
            "org.springframework.security.access.PermissionEvaluator",
            "org.springframework.security.access.expression.AbstractSecurityExpressionHandler",
            "org.springframework.security.access.expression.ExpressionUtils",
            "org.springframework.security.access.expression.SecurityExpressionHandler",
            "org.springframework.security.access.expression.SecurityExpressionOperations",
            "org.springframework.security.access.expression.SecurityExpressionRoot",
            "org.springframework.security.access.hierarchicalroles.RoleHierarchy");

    private static URLClassLoader production;

    @BeforeAll
    static void productionRuntimeLoader() throws MalformedURLException {
        String classpath = System.getProperty(PRODUCTION_CLASSPATH_PROPERTY);
        // Fail closed: without the property this class could only test the test classpath.
        assertThat(classpath).as("-D" + PRODUCTION_CLASSPATH_PROPERTY
                + " is set by core-java/build.gradle.kts tasks.test; run this class through Gradle").isNotBlank();
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        production = new URLClassLoader("production-runtime", urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }

    @AfterAll
    static void close() throws Exception {
        if (production != null) {
            production.close();
        }
    }

    @Test
    @DisplayName("positive control: the production loader holds spring-statemachine itself")
    void theProductionLoaderHoldsTheStatemachine() throws Exception {
        assertThat(Class.forName("org.springframework.statemachine.StateMachine", false, production).getClassLoader())
                .as("the loader must hold the production jars, or any absence below proves nothing")
                .isSameAs(production);
    }

    @Test
    @DisplayName("every org.springframework.security.access class statemachine-core 4.0.2 references loads from the production classpath")
    void everyReferencedSecurityAccessClassLoads() throws Exception {
        // Positive control first, in this method too, so a failure here can never be an empty loader.
        theProductionLoaderHoldsTheStatemachine();
        assertThat(REFERENCED).hasSize(16).allMatch(name -> name.startsWith("org.springframework.security.access."));

        Map<String, String> missing = new LinkedHashMap<>();
        for (String name : REFERENCED) {
            try {
                Class<?> type = Class.forName(name, false, production);
                if (type.getClassLoader() != production) {
                    missing.put(name, "loaded by " + type.getClassLoader() + ", not the production loader");
                }
            } catch (ClassNotFoundException | LinkageError e) {
                missing.put(name, e.getClass().getSimpleName());
            }
        }
        assertThat(missing)
                .as("spring-statemachine-core 4.0.2 references these org.springframework.security.access classes, "
                        + "which are absent from the production runtime classpath; Security 7 ships them in "
                        + "org.springframework.security:spring-security-access (D-03)")
                .isEmpty();
    }
}
