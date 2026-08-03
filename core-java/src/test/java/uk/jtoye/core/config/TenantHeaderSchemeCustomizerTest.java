package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import uk.jtoye.core.security.TenantFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #440 — the published document must not describe the request-header tenant mechanism in a
 * context where the filter that honours it is absent.
 *
 * <p><strong>Why this is a unit test and not an assertion on the served document.</strong> The
 * issue asks for the check to run against what the service renders rather than against source
 * text, and this does: it asserts on the {@link OpenAPI} model object after the customizer has run,
 * which is the object springdoc serialises. What it deliberately does NOT do is assert through
 * {@code /v3/api-docs}, because the only profile the snapshot test can boot is {@code test} — one
 * of the three profiles where {@link TenantFilter} IS present and the advertisement is therefore
 * CORRECT. Booting the deployed profile whose behaviour is at issue ({@code staging}) would require
 * that profile's real infrastructure. Driving the customizer with the filter present and absent
 * covers both states directly, and is the strictly stronger check: it fails for either mistake,
 * where a single-profile HTTP assertion could only ever observe one.
 *
 * <p><strong>Falsifiability.</strong> Both directions are asserted here, so neither arm can be
 * vacuous: with the filter absent the scheme must be gone, and with the filter present it must
 * remain. Deleting the {@code getIfAvailable() != null} guard fails the second; deleting the body
 * of {@code customise} fails the first.
 */
class TenantHeaderSchemeCustomizerTest {

    private static final String SCHEME = TenantHeaderSchemeCustomizer.SCHEME_NAME;

    @Test
    @DisplayName("filter ABSENT: the scheme, both requirement levels and the prose all go")
    void stripsEverythingWhenFilterAbsent() {
        OpenAPI api = documentAdvertisingTheHeader();

        customizer(null).customise(api);

        assertThat(api.getComponents().getSecuritySchemes()).doesNotContainKey(SCHEME);
        assertThat(api.getSecurity())
                .as("the global requirement keeps bearer-jwt and loses only the header")
                .flatExtracting(SecurityRequirement::keySet)
                .containsExactly("bearer-jwt");
        assertThat(api.getInfo().getDescription())
                .as("the prose that explains how to use the header must go with it")
                .doesNotContain(TenantFilter.TENANT_HEADER);
    }

    @Test
    @DisplayName("filter ABSENT: an operation left with no other scheme gets null, never an empty list")
    void neverLeavesAnEmptySecurityList() {
        OpenAPI api = documentAdvertisingTheHeader();

        customizer(null).customise(api);

        Operation headerOnly = api.getPaths().get("/header-only").getGet();
        assertThat(headerOnly.getSecurity())
                .as("an empty security list positively asserts 'no auth required' in OpenAPI and "
                        + "would publish an authenticated operation as anonymous — strictly worse "
                        + "than the defect being fixed")
                .isNull();

        Operation both = api.getPaths().get("/both").getGet();
        assertThat(both.getSecurity())
                .flatExtracting(SecurityRequirement::keySet)
                .containsExactly("bearer-jwt");
    }

    @Test
    @DisplayName("filter PRESENT: the document is accurate, so nothing is touched")
    void leavesDocumentAloneWhenFilterPresent() {
        OpenAPI api = documentAdvertisingTheHeader();

        customizer(new TenantFilter()).customise(api);

        assertThat(api.getComponents().getSecuritySchemes()).containsKey(SCHEME);
        assertThat(api.getSecurity())
                .flatExtracting(SecurityRequirement::keySet)
                .containsExactlyInAnyOrder("bearer-jwt", SCHEME);
        assertThat(api.getInfo().getDescription()).contains(TenantFilter.TENANT_HEADER);
        assertThat(api.getPaths().get("/header-only").getGet().getSecurity()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private TenantHeaderSchemeCustomizer customizer(TenantFilter filter) {
        ObjectProvider<TenantFilter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(filter);
        return new TenantHeaderSchemeCustomizer(provider);
    }

    /** Mirrors the real document's shape: scheme + global requirement + per-operation requirements. */
    private OpenAPI documentAdvertisingTheHeader() {
        Operation headerOnly = new Operation()
                .security(List.of(new SecurityRequirement().addList(SCHEME)));
        Operation both = new Operation().security(List.of(
                new SecurityRequirement().addList("bearer-jwt"),
                new SecurityRequirement().addList(SCHEME)));

        Paths paths = new Paths();
        paths.addPathItem("/header-only", new PathItem().get(headerOnly));
        paths.addPathItem("/both", new PathItem().get(both));

        return new OpenAPI()
                .info(new Info().description(
                        "- JWT must contain a tenant claim\n"
                                + "- Dev fallback: Use `" + TenantFilter.TENANT_HEADER
                                + "` header when JWT lacks tenant claim\n"
                                + "### Pagination"))
                .security(List.of(
                        new SecurityRequirement().addList("bearer-jwt"),
                        new SecurityRequirement().addList(SCHEME)))
                .paths(paths)
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer"))
                        .addSecuritySchemes(SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TenantFilter.TENANT_HEADER)));
    }
}
