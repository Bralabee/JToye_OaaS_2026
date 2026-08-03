package uk.jtoye.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.TenantFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Issue #440 (QA council {@code disc-20260802-121732}, F-H2) — keeps the published
 * OpenAPI document from describing a request-header tenant mechanism in profiles
 * where that mechanism does not exist.
 *
 * <p><b>The split this class closes.</b> {@link TenantFilter} — the only thing that
 * honours the header — is {@code @Profile({"dev","local","test"})}. The spec that
 * advertises it was not gated the same way: {@link OpenApiConfig} registers the
 * security scheme, a global security requirement names it, and thirteen controllers
 * carry a class-level {@code @SecurityRequirement} for it, which springdoc expands
 * onto every operation. Nothing tied any of that to whether the filter was actually
 * in the context, so the document described a capability the running service did not
 * have wherever the profiles disagreed.
 *
 * <p><b>Where that actually bit.</b> Not production: {@code OpenApiConfig} is
 * {@code @Profile("!prod")} and {@code springdoc.api-docs.enabled} defaults to false
 * under {@code application-prod.yml}, so prod publishes no such document at all.
 * The gap is {@code staging}, which is neither {@code prod} (so the config loads)
 * nor one of the filter's profiles (so the filter does not), and which explicitly
 * turns springdoc on. There, the contract described something inert.
 *
 * <p><b>Why derive from the bean rather than repeat the profile list.</b> A second
 * copy of {@code {"dev","local","test"}} here would be one refactor away from
 * disagreeing with the first, which is precisely the failure being fixed. Asking the
 * context whether the filter exists cannot drift: if the filter's gating changes, the
 * advertisement follows it automatically. {@link ObjectProvider} is used rather than a
 * bean condition so the answer is read when the document is built, with no
 * configuration-ordering assumption.
 *
 * <p><b>Scope.</b> This changes documentation only. The filter's own profile gating is
 * deliberate for local development and is untouched — removing it is explicitly out of
 * scope for the issue.
 */
@Component
public class TenantHeaderSchemeCustomizer implements OpenApiCustomizer {

    /**
     * Scheme key as registered in {@link OpenApiConfig} and referenced by the
     * class-level {@code @SecurityRequirement} on the controllers.
     */
    static final String SCHEME_NAME = "tenant-header";

    private final ObjectProvider<TenantFilter> tenantFilterProvider;

    public TenantHeaderSchemeCustomizer(ObjectProvider<TenantFilter> tenantFilterProvider) {
        this.tenantFilterProvider = tenantFilterProvider;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi == null || tenantFilterProvider.getIfAvailable() != null) {
            // The filter is in the context, so the document is accurate: leave it alone.
            return;
        }
        removeScheme(openApi);
        removeGlobalRequirement(openApi);
        removeOperationRequirements(openApi);
        removeDescriptionMention(openApi);
    }

    private void removeScheme(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components != null && components.getSecuritySchemes() != null) {
            components.getSecuritySchemes().remove(SCHEME_NAME);
        }
    }

    private void removeGlobalRequirement(OpenAPI openApi) {
        List<SecurityRequirement> pruned = prune(openApi.getSecurity());
        openApi.setSecurity(pruned);
    }

    private void removeOperationRequirements(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem pathItem : openApi.getPaths().values()) {
            if (pathItem == null) {
                continue;
            }
            for (Operation operation : pathItem.readOperations()) {
                if (operation != null && operation.getSecurity() != null) {
                    operation.setSecurity(prune(operation.getSecurity()));
                }
            }
        }
    }

    /**
     * Drops the scheme from a security list, returning {@code null} rather than an
     * empty list when nothing survives.
     *
     * <p>That distinction is load-bearing and is the one genuinely dangerous edge in
     * this class: in OpenAPI an <em>empty</em> {@code security: []} does not mean
     * "unspecified", it positively asserts "no authentication required" and overrides
     * the document-level requirement. Pruning the last entry to {@code []} would
     * therefore publish an authenticated endpoint as anonymous — a strictly worse
     * defect than the one being fixed. {@code null} removes the key entirely, so the
     * operation correctly inherits the global requirement.
     */
    private List<SecurityRequirement> prune(List<SecurityRequirement> requirements) {
        if (requirements == null) {
            return null;
        }
        List<SecurityRequirement> kept = new ArrayList<>(requirements);
        for (Iterator<SecurityRequirement> it = kept.iterator(); it.hasNext(); ) {
            SecurityRequirement requirement = it.next();
            if (requirement == null) {
                it.remove();
                continue;
            }
            requirement.remove(SCHEME_NAME);
            if (requirement.isEmpty()) {
                it.remove();
            }
        }
        return kept.isEmpty() ? null : kept;
    }

    /**
     * Strips the prose bullet that names the header from the API description.
     *
     * <p>Removing the scheme while leaving the description explaining how to use the
     * header would defeat the whole change — the document would still name it, just in
     * a place a structural assertion would not look. Matched on
     * {@link TenantFilter#TENANT_HEADER} rather than a copied literal so the two cannot
     * fall out of step.
     */
    private void removeDescriptionMention(OpenAPI openApi) {
        Info info = openApi.getInfo();
        if (info == null || info.getDescription() == null) {
            return;
        }
        String description = info.getDescription();
        if (!description.contains(TenantFilter.TENANT_HEADER)) {
            return;
        }
        String filtered = Arrays.stream(description.split("\n", -1))
                .filter(line -> !line.contains(TenantFilter.TENANT_HEADER))
                .collect(Collectors.joining("\n"));
        info.setDescription(filtered);
    }

    /** Exposed for the unit test's readability only. */
    static boolean advertises(OpenAPI openApi) {
        Components components = openApi.getComponents();
        Map<String, ?> schemes = components == null ? null : components.getSecuritySchemes();
        return schemes != null && schemes.containsKey(SCHEME_NAME);
    }
}
