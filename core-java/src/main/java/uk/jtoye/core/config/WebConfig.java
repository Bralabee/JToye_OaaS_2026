package uk.jtoye.core.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;
import uk.jtoye.core.security.RateLimitInterceptor;

/**
 * Web MVC configuration for registering interceptors.
 * Registers the RateLimitInterceptor to enforce tenant-aware rate limiting.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v1",
            HandlerTypePredicate.forBasePackage(
                "uk.jtoye.core.shop",
                "uk.jtoye.core.product",
                "uk.jtoye.core.order",
                "uk.jtoye.core.customer",
                "uk.jtoye.core.finance",
                "uk.jtoye.core.gdpr",
                "uk.jtoye.core.sync"
            )
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
    }
}
