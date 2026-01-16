package uk.jtoye.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
        // Create RestOperations with timeouts
        RestOperations restOperations = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();

        // Build JwtDecoder with custom RestOperations for JWKS fetching
        return NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                .restOperations(restOperations)
                .build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter, TenantFilter tenantFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults()) // Enable CORS with default configuration
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/health", "/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
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
