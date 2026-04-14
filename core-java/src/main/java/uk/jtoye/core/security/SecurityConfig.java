package uk.jtoye.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.client.RestOperations;

import java.time.Duration;

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
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter, TenantFilter tenantFilter) throws Exception {
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

        // Ensure dev header-based tenant mapping runs early (before auth)
        http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);
        // Ensure that after JWT authentication, we map tenant from token into TenantContext
        // IMPORTANT: Must run AFTER BearerTokenAuthenticationFilter (which validates JWT)
        http.addFilterAfter(jwtTenantFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
