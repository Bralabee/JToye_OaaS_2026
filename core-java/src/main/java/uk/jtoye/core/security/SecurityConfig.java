package uk.jtoye.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter, TenantFilter tenantFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/actuator/health", "/actuator/info").permitAll()
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
