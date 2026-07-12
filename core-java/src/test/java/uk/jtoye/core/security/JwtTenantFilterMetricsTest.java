package uk.jtoye.core.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #98 [P2-7]: proves the tenant.context.missing counter — the metric the
 * Prometheus TenantIsolationFailure alert references — is emitted at its natural
 * detection point (an authenticated JWT principal reaching the tenant filter with
 * no resolvable tenant claim) and stays untouched when a valid tenant is present.
 */
class JwtTenantFilterMetricsTest {

    private SimpleMeterRegistry registry;
    private JwtTenantFilter filter;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("authenticated JWT with no resolvable tenant claim increments tenant.context.missing")
    void noTenantClaim_incrementsCounter() throws Exception {
        newFilter();
        setAuthentication(buildJwt(Map.of("sub", "user-1"))); // no tenant claim

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mockChain());

        assertEquals(1.0, registry.get("tenant.context.missing").counter().count(),
                "an authenticated principal with no tenant claim is the isolation-failure signal");
        assertTrue(TenantContext.get().isEmpty());
    }

    @Test
    @DisplayName("authenticated JWT with a valid tenant_id does NOT increment the counter")
    void validTenantClaim_doesNotIncrement() throws Exception {
        newFilter();
        UUID tenantId = UUID.randomUUID();
        setAuthentication(buildJwt(Map.of("tenant_id", tenantId.toString())));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), mockChain());

        assertEquals(0.0, registry.get("tenant.context.missing").counter().count(),
                "a resolvable tenant claim must not trip the isolation-failure counter");
        assertEquals(tenantId, TenantContext.get().orElseThrow());
    }

    private void newFilter() {
        registry = new SimpleMeterRegistry();
        filter = new JwtTenantFilter(providerOf(registry));
    }

    private FilterChain mockChain() {
        return mock(FilterChain.class);
    }

    private Jwt buildJwt(Map<String, Object> claims) {
        return Jwt.withTokenValue("mock-token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void setAuthentication(Jwt jwt) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(jwt);
        SecurityContextImpl context = new SecurityContextImpl();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }
        };
    }
}
