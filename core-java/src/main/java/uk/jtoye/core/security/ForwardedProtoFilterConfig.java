package uk.jtoye.core.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link TrustedProxyForwardedProtoFilter} ahead of every other filter (Spring Security's
 * chain included, so its HSTS writer sees the restored scheme). A {@code @Configuration} rather than
 * a {@code @Component} filter so {@code @WebMvcTest} slices, which do not load
 * {@link ClientIpResolver}, are not handed a filter they cannot build.
 */
@Configuration(proxyBeanMethods = false)
public class ForwardedProtoFilterConfig {

    @Bean
    public FilterRegistrationBean<TrustedProxyForwardedProtoFilter> trustedProxyForwardedProtoFilter(
            ClientIpResolver clientIpResolver) {
        FilterRegistrationBean<TrustedProxyForwardedProtoFilter> registration =
                new FilterRegistrationBean<>(new TrustedProxyForwardedProtoFilter(clientIpResolver));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("trustedProxyForwardedProtoFilter");
        return registration;
    }
}
