package uk.jtoye.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.security.TenantFilter;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 28 / SEC-03 — the document springdoc actually SERVES must omit the dev-only
 * {@code X-Tenant-Id} override on a profile where {@link TenantFilter} is absent.
 *
 * <p><b>What this adds beyond {@link TenantHeaderSchemeCustomizerTest}.</b> That class already
 * proves the strip in both directions, but it asserts on the {@link io.swagger.v3.oas.models.OpenAPI}
 * <em>model object</em> after driving {@code customise()} by hand, and its own javadoc records that
 * it deliberately does not go through {@code /v3/api-docs}. So the mechanism is proven and one link
 * is not: that springdoc actually applies the customizer to what it serves. Closing exactly that
 * link is this class's whole job — hence every assertion below is on the served response STRING,
 * never on the model.
 *
 * <p><b>Why remove the bean definition rather than switch profile.</b>
 * {@link TenantHeaderSchemeCustomizer} keys off {@code ObjectProvider<TenantFilter>.getIfAvailable()},
 * so removing the definition reproduces the deployed-profile condition exactly — filter absent,
 * while {@link OpenApiConfig} stays loaded because it is {@code @Profile("!prod")} and we are still
 * under {@code test}. Booting {@code staging} itself would drag in that profile's real
 * infrastructure for no extra assurance. This is the recipe RESEARCH Pattern 2 prescribes.
 *
 * <p><b>THE LINES THAT MAKE THIS CLASS CAPABLE OF FAILING.</b> Two, and they are different in kind:
 * <ul>
 *   <li>{@code springdoc.api-docs.enabled=true} in {@link #configureProperties}. It already resolves
 *       true under {@code test} (nothing overrides the springdoc block in {@code application.yml}),
 *       so today it changes nothing — it is pinned because if a future profile edit switched
 *       springdoc off, {@code /v3/api-docs} would 404 and arm 1's "contains neither string" would
 *       become true for the most uninteresting possible reason. This is the same trap
 *       {@code OpenApiProdProfileGatingTest:81-96} records paying for once already.</li>
 *   <li>Arm 2, the filter-PRESENT control, is what catches RESEARCH assumption A4 — that removing
 *       the bean definition faithfully reproduces the staging document shape. If springdoc built and
 *       cached the document before the post-processor ran, or if the customizer were never wired
 *       into the served pipeline at all, arm 1 would still pass (nothing to strip, nothing served)
 *       and <b>arm 2 is the arm that goes red</b>. Arm 1 alone cannot distinguish "the strip works"
 *       from "the document was never built".</li>
 * </ul>
 *
 * <p><b>Arm 3 is the denominator.</b> An empty document satisfies arm 1 vacuously, so the served
 * {@code paths} object must be non-empty — the same rule {@code check-openapi-snapshot-fresh.sh}
 * encodes as its A-2 assertion.
 *
 * <p><b>Deliberately not a {@code scripts/check-*.sh}.</b> It needs no running stack and belongs in
 * {@code integrationTest}, which runs in CI on every PR — so this class IS the CI gate SC-3 asks
 * for. A shell gate would additionally owe {@code scripts/gates/gate-enforcement.conf} an entry
 * whose stated bar ("a hosted runner does not have the thing this inspects") is simply false here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("testcontainers")
class TenantHeaderAbsentDocumentTest {

    /**
     * The bean name Spring derives for {@code @Component public class TenantFilter} — the
     * decapitalised simple name. Hardcoded deliberately (the harness must break loudly if the
     * declaration is renamed rather than silently stop removing anything), and cross-checked
     * against the bean TYPE in {@link TenantFilterBeanRemover} so a rename cannot pass unnoticed.
     */
    private static final String TENANT_FILTER_BEAN = "tenantFilter";

    /** Floor for arm 3. Far below the observed path count, so it fails on collapse, not on drift. */
    private static final int MINIMUM_PATHS = 50;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
        // See "THE LINES THAT MAKE THIS CLASS CAPABLE OF FAILING" in the class javadoc.
        registry.add("springdoc.api-docs.enabled", () -> "true");
    }

    /**
     * Fetches the document springdoc serves. Asserting 200 here is load-bearing: a 404 or 500 body
     * would contain neither string and would satisfy arm 1 for the wrong reason.
     */
    private static String servedDocument(MockMvc mockMvc) throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Removes the {@link TenantFilter} bean definition, reproducing a deployed profile's context
     * shape without booting a deployed profile.
     *
     * <p><b>Why a plain {@link BeanFactoryPostProcessor} and not a
     * {@code BeanDefinitionRegistryPostProcessor}.</b> Post-processors registered programmatically
     * from an {@link ApplicationContextInitializer} have their registry callback invoked BEFORE
     * {@code ConfigurationClassPostProcessor} performs component scanning — at which point
     * {@code tenantFilter} does not exist yet. A plain {@code BeanFactoryPostProcessor} is deferred
     * to {@code invokeBeanFactoryPostProcessors(regularPostProcessors, ...)}, which runs after all
     * registry work is complete, so the definition is present and removable. Getting this backwards
     * is not a loud failure by default — it is a removal that finds nothing — which is precisely
     * why the existence check below throws.
     */
    static class TenantFilterBeanRemover
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            applicationContext.addBeanFactoryPostProcessor(new BeanFactoryPostProcessor() {
                @Override
                public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                    BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

                    // Fail loudly rather than no-op: a silent removal of nothing would leave arm 1
                    // asserting against an unmodified, filter-PRESENT context, where it would fail
                    // confusingly — or, if the strip regressed to always-on, pass while proving
                    // nothing about the filter-absent condition.
                    if (!registry.containsBeanDefinition(TENANT_FILTER_BEAN)) {
                        throw new IllegalStateException(
                                "Harness broken: no bean definition named '" + TENANT_FILTER_BEAN
                                        + "' to remove. TenantFilter is @Profile({\"dev\",\"local\","
                                        + "\"test\"}) and this class runs under 'test', so it must be "
                                        + "registered. If TenantFilter was renamed, update "
                                        + "TENANT_FILTER_BEAN. Definitions of that TYPE present: "
                                        + Arrays.toString(tenantFilterBeanNames(beanFactory)));
                    }

                    String[] byType = tenantFilterBeanNames(beanFactory);
                    if (byType.length != 1 || !TENANT_FILTER_BEAN.equals(byType[0])) {
                        throw new IllegalStateException(
                                "Harness broken: expected exactly one TenantFilter-typed bean named '"
                                        + TENANT_FILTER_BEAN + "', found "
                                        + Arrays.toString(byType)
                                        + ". Removing by name would leave a TenantFilter in the context "
                                        + "and the customizer would correctly leave the document alone.");
                    }

                    registry.removeBeanDefinition(TENANT_FILTER_BEAN);

                    // Post-condition: the customizer asks the context by TYPE, so "no definition of
                    // that type survives" is the property that actually matters to it.
                    String[] remaining = tenantFilterBeanNames(beanFactory);
                    if (remaining.length != 0) {
                        throw new IllegalStateException(
                                "Harness broken: TenantFilter beans still registered after removal: "
                                        + Arrays.toString(remaining));
                    }
                }

                /** {@code allowEagerInit=false} — resolve types without instantiating anything. */
                private String[] tenantFilterBeanNames(ConfigurableListableBeanFactory beanFactory) {
                    return beanFactory.getBeanNamesForType(TenantFilter.class, true, false);
                }
            });
        }
    }

    // ------------------------------------------------------------------
    // Arm 1 + arm 3 — the claim, and its denominator
    // ------------------------------------------------------------------

    @Nested
    @ContextConfiguration(initializers = TenantFilterBeanRemover.class)
    @org.junit.jupiter.api.Tag("testcontainers")
    @DisplayName("TenantFilter ABSENT")
    class FilterAbsent {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("arm 1: the SERVED document advertises neither the header nor the scheme")
        void servedDocumentOmitsTheTenantOverrideHeader() throws Exception {
            String served = servedDocument(mockMvc);

            // TenantFilter.TENANT_HEADER, never a copied literal — the customizer itself matches on
            // the constant (TenantHeaderSchemeCustomizer:155,159) so the two cannot fall out of step.
            assertThat(served)
                    .as("the served /v3/api-docs document still names the %s override header, but "
                            + "TenantFilter is absent from this context — the document is advertising "
                            + "a tenant mechanism the running service does not honour",
                            TenantFilter.TENANT_HEADER)
                    .doesNotContain(TenantFilter.TENANT_HEADER);

            assertThat(served)
                    .as("the served document still carries the '%s' security scheme key",
                            TenantHeaderSchemeCustomizer.SCHEME_NAME)
                    .doesNotContain(TenantHeaderSchemeCustomizer.SCHEME_NAME);
        }

        @Test
        @DisplayName("arm 3: the served document is non-empty, so arm 1 cannot pass vacuously")
        void servedDocumentIsNotEmpty() throws Exception {
            String served = servedDocument(mockMvc);

            JsonNode paths = new ObjectMapper().readTree(served).path("paths");
            System.out.println("[TenantHeaderAbsentDocumentTest] observed served paths count = "
                    + paths.size());

            assertThat(paths.isObject())
                    .as("served document has no 'paths' object at all — arm 1 would be vacuous")
                    .isTrue();
            assertThat(paths.size())
                    .as("served document declares only %s paths (floor %s). An empty or collapsed "
                            + "document contains neither header string and would satisfy arm 1 while "
                            + "proving nothing.", paths.size(), MINIMUM_PATHS)
                    .isGreaterThanOrEqualTo(MINIMUM_PATHS);
        }
    }

    // ------------------------------------------------------------------
    // Arm 2 — the control that makes arm 1's absence mean something
    // ------------------------------------------------------------------

    @Nested
    @org.junit.jupiter.api.Tag("testcontainers")
    @DisplayName("TenantFilter PRESENT")
    class FilterPresent {

        @Autowired
        private MockMvc mockMvc;

        /**
         * Stock {@code test} context: {@link TenantFilter} is registered, the header IS honoured,
         * and the document is therefore CORRECT to advertise it. Without this arm, arm 1's absence
         * is indistinguishable from a document that could not be built, was never customized, or
         * was served empty.
         */
        @Test
        @DisplayName("arm 2: with the filter present the SERVED document retains both strings")
        void servedDocumentStillAdvertisesTheHeaderWhenTheFilterIsPresent() throws Exception {
            String served = servedDocument(mockMvc);

            assertThat(served)
                    .as("the served document must still advertise %s while TenantFilter is in the "
                            + "context — if this is absent the strip is unconditional and arm 1 is "
                            + "measuring nothing", TenantFilter.TENANT_HEADER)
                    .contains(TenantFilter.TENANT_HEADER);

            assertThat(served)
                    .as("the served document must still carry the '%s' security scheme key",
                            TenantHeaderSchemeCustomizer.SCHEME_NAME)
                    .contains(TenantHeaderSchemeCustomizer.SCHEME_NAME);
        }
    }
}
