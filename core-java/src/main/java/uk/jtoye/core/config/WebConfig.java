package uk.jtoye.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;
import uk.jtoye.core.security.RateLimitInterceptor;
import uk.jtoye.core.security.TenantStatusInterceptor;

/**
 * Web MVC configuration for registering interceptors.
 * Registers the RateLimitInterceptor to enforce tenant-aware rate limiting.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Versioned API prefix invisibly prepended to every {@code @RestController}
     * in {@link #API_V1_PACKAGES} (issue #97 [P2-6]). A controller annotated
     * {@code @RequestMapping("/shops")} in one of those packages is actually
     * served at {@code /api/v1/shops}.
     *
     * <p><b>Consequence — never hand-build response paths in those packages.</b>
     * {@code URI.create("/shops/" + id)} produces a Location header that 404s
     * because the real resource lives under the prefix. Build Location (and any
     * self-referencing URI) with
     * {@code ServletUriComponentsBuilder.fromCurrentRequest()}, which inherits
     * the real, prefixed request path. This convention is enforced by
     * {@code ApiPrefixConventionTest}; Location dereferencability is proven by
     * {@code LocationHeaderContractTest}.
     */
    public static final String API_V1_PREFIX = "/api/v1";

    /**
     * Controller packages served under {@link #API_V1_PREFIX}. Packages NOT
     * listed here (e.g. {@code uk.jtoye.core.storefront} and
     * {@code uk.jtoye.core.payment}) keep their literal mappings — the public
     * storefront and the Stripe webhook depend on their legacy paths (see
     * {@code RefundController}'s javadoc for why payment is excluded).
     */
    public static final String[] API_V1_PACKAGES = {
            "uk.jtoye.core.shop",
            "uk.jtoye.core.product",
            "uk.jtoye.core.order",
            "uk.jtoye.core.customer",
            "uk.jtoye.core.finance",
            "uk.jtoye.core.gdpr",
            "uk.jtoye.core.sync",
            "uk.jtoye.core.onboarding"
    };

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Autowired
    private TenantStatusInterceptor tenantStatusInterceptor;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_V1_PREFIX,
            HandlerTypePredicate.forBasePackage(API_V1_PACKAGES)
        );
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Register rate limit interceptor for all paths
        // Excluded paths are handled within the interceptor itself
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/actuator/**",
                    "/health",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                );
        // issue #102: tenant lifecycle enforcement — rejects SUSPENDED/OFFBOARDED
        // tenants' traffic with 403. Registered AFTER the rate limiter so 429
        // takes precedence and the (cached) status lookup sits behind throttling.
        // Fine-grained exemptions (public storefront, the admin-tenants surface
        // itself) live inside the interceptor.
        registry.addInterceptor(tenantStatusInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                    "/actuator/**",
                    "/health",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**"
                );
    }
}
