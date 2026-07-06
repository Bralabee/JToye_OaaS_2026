package uk.jtoye.core.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.client.RestOperations;

import java.time.Duration;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    /**
     * Configure JwtDecoder with timeouts to prevent hanging during JWKS fetch.
     * Uses RestTemplate with connection and read timeouts.
     */
    @Bean
    public JwtDecoder jwtDecoder(RestTemplateBuilder restTemplateBuilder) {
        // Create a custom ClientHttpRequestFactory with timeouts
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());

        // Create RestOperations with the custom request factory
        RestOperations restOperations = restTemplateBuilder
                .requestFactory(() -> requestFactory)
                .build();

        // Build JwtDecoder with custom RestOperations for JWKS fetching
        return NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                .restOperations(restOperations)
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTenantFilter jwtTenantFilter,
                                                   ObjectProvider<TenantFilter> tenantFilterProvider,
                                                   Environment env) throws Exception {
        http
            // CSRF protection disabled: this is a stateless JWT bearer-token API.
            // All authenticated requests must carry an Authorization: Bearer <jwt>
            // header (see JwtTenantFilter + oauth2ResourceServer below) — no
            // session cookies are issued, so there is no ambient credential a
            // malicious cross-origin form submission could ride. A browser
            // cannot synthesise the Bearer header from a <form action=...> POST,
            // and CORS already restricts which origins may read responses.
            // This is the standard stateless-API posture; see ADR-001.
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults()) // Enable CORS with default configuration
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/health", "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/public/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        // Browser security headers per ASVS 14.4.1-14.4.7 (SEC-03).
        // HSTS gated by prod profile at runtime — dev HTTP traffic never sees it.
        // Pattern: 12-RESEARCH.md §4.2 Pattern A (single bean + runtime env check).
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");
        http.headers(headers -> {
            headers.frameOptions(frame -> frame.deny())
                   .contentTypeOptions(Customizer.withDefaults())
                   .referrerPolicy(r -> r.policy(
                       ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
            if (isProd) {
                headers.httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000L));
            } else {
                headers.httpStrictTransportSecurity(hsts -> hsts.disable());
            }
        });

        // Ensure dev/test header-based tenant mapping runs early (before auth).
        // TenantFilter is @Profile-gated to non-prod, so in production this bean
        // is absent and the tenant is derived solely from the JWT (JwtTenantFilter);
        // a spoofed X-Tenant-Id header has no effect.
        TenantFilter tenantFilter = tenantFilterProvider.getIfAvailable();
        if (tenantFilter != null) {
            http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);
        }
        // Ensure that after JWT authentication, we map tenant from token into TenantContext
        // IMPORTANT: Must run AFTER BearerTokenAuthenticationFilter (which validates JWT)
        http.addFilterAfter(jwtTenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
