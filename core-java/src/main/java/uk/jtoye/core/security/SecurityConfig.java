package uk.jtoye.core.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.client.RestOperations;

import java.time.Duration;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // issue #83 P1-1: activate @PreAuthorize gates on sensitive controllers
public class SecurityConfig {

    /**
     * Translate Keycloak token claims into Spring authorities: {@code realm_access.roles}
     * into {@code ROLE_*} (issue #83 P1-1) AND the OAuth2 {@code scope} claim into
     * {@code SCOPE_*} (issue #206 [AI-4] scoped machine credentials). The combined
     * {@link JwtRolesAndScopesConverter} is additive — it keeps every
     * {@code @PreAuthorize("hasRole('admin')")} gate working while enabling the new
     * {@code hasAuthority('SCOPE_catalog:write')} gate on the product write surface.
     * Kept as a private helper so the same converter instance is wired into the resource
     * server below and exercised verbatim by {@code JwtRolesAndScopesConverterTest} /
     * {@code ScopedCatalogAccessIntegrationTest} / {@code RoleBasedAccessIntegrationTest}.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new JwtRolesAndScopesConverter());
        return converter;
    }

    // Internal issuer-uri: used to LOCATE the JWKS endpoint (must be reachable
    // from this container, e.g. http://keycloak:8080/...). It is NOT reused as
    // the expected 'iss' claim — see expectedIssuer below (issue #87 split-horizon fix).
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    // issue #87 follow-up (split-horizon issuer fix): the value required in the
    // token 'iss' claim. Keycloak stamps its PUBLIC frontend issuer (KC_HOSTNAME,
    // e.g. http://localhost:8085/...), which differs from the INTERNAL JWKS host
    // (issuerUri) in a containerised topology. Validating 'iss' against issuerUri
    // rejected every real token ("The iss claim is not valid"); this decouples the
    // two. Defaults to issuerUri (application.yml) so single-host/Testcontainers
    // setups are unaffected. Env-overridable via JWT_EXPECTED_ISSUER.
    @Value("${jtoye.security.jwt.expected-issuer}")
    private String expectedIssuer;

    // issue #87 P1-5: expected 'aud' claim on inbound access tokens. Env-overridable
    // (JWT_EXPECTED_AUDIENCE), never hardcoded — see application.yml. AudienceValidator
    // throws at construction on a blank value so enforcement can never silently no-op.
    @Value("${jtoye.security.jwt.expected-audience}")
    private String expectedAudience;

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
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                        .restOperations(restOperations)
                        .build();

        // issue #87 P1-5 (threats T-bl2-01, T-bl2-03): enforce audience ADDITIVELY.
        // withJwkSetUri gives a decoder whose default validator is TIMESTAMP ONLY;
        // createDefaultWithIssuer STRENGTHENS this by adding issuer validation, and
        // AudienceValidator rejects tokens minted for another client in the same realm.
        // Issuer is validated against expectedIssuer (the PUBLIC issuer Keycloak stamps),
        // NOT issuerUri (the INTERNAL JWKS host) — see split-horizon note on the field.
        // This does NOT touch the #83 jwtAuthenticationConverter (role mapping) wired
        // on the resource server below.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(expectedIssuer),
                new AudienceValidator(expectedAudience)));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTenantFilter jwtTenantFilter,
                                                   ObjectProvider<TenantFilter> tenantFilterProvider,
                                                   Environment env) throws Exception {
        // Runtime prod check shared by the actuator-scrape matcher and the HSTS
        // block below (12-RESEARCH.md §4.2 Pattern A: single bean + env check).
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains("prod");

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
            .authorizeHttpRequests(auth -> {
                // issue #99 do-now (probe-401 fix): "/actuator/health/**" MUST be a
                // subpath match, not just the exact "/actuator/health". kubelet hits
                // /actuator/health/liveness and /actuator/health/readiness with an
                // UNAUTHENTICATED probe (k8s/base/core-java-deployment.yaml:181-198),
                // and the deploy smoke tests assert the same paths. Without subpath
                // matching every probe 401s → no pod ever goes Ready → every rollout
                // fails. Health-group endpoints expose only aggregate status
                // (show-details=when-authorized), so anonymous access leaks nothing.
                auth.requestMatchers("/", "/health", "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // issue #97 [P2-6]: /api/v1/public/** is the canonical versioned
                    // alias of the legacy /public/** surface — both must stay public.
                    .requestMatchers("/public/**", "/api/v1/public/**").permitAll()
                    .requestMatchers("/ws/**").permitAll();
                // issue #98 [P2-7] item 4: /actuator/prometheus permitAll is now
                // UNCONDITIONAL. In prod the actuator endpoints are served ONLY on
                // the internal management port (management.server.port, default
                // 9091 — not published via Service/Ingress), and the public app
                // port (9090) serves no actuator at all, so there is nothing to
                // expose publicly. Permitting the matcher unconditionally lets the
                // cluster-internal Prometheus scrape + kubelet reach the metrics
                // endpoint on the management port without weakening the app-port
                // chain (anyRequest().authenticated() below is untouched). Proven
                // by ManagementPortMetricsIntegrationTest (T-t6b-02/T-t6b-04).
                auth.requestMatchers("/actuator/prometheus").permitAll();
                auth.anyRequest().authenticated();
            })
            // issue #83 P1-1 + #206 [AI-4]: replace the default authority converter with the
            // combined JwtRolesAndScopesConverter so realm_access.roles -> ROLE_* AND the
            // scope claim -> SCOPE_* authorities. JwtTenantFilter (added AFTER
            // BearerTokenAuthenticationFilter below) still maps tenant_id -> TenantContext;
            // role/scope checks are additive to RLS scoping.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        // Browser security headers per ASVS 14.4.1-14.4.7 (SEC-03).
        // HSTS gated by prod profile at runtime — dev HTTP traffic never sees it.
        // Pattern: 12-RESEARCH.md §4.2 Pattern A (single bean + runtime env check).
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
