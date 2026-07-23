package uk.jtoye.core.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.security.Principal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP channel interceptor that enforces tenant isolation for WebSocket connections.
 *
 * Responsibilities:
 * - CONNECT: Validate JWT token, extract tenantId, store in session attributes
 * - SUBSCRIBE: Validate destination tenant matches session tenant
 * - SEND: Propagate TenantContext from session attributes for message handlers
 *
 * Implements ExecutorChannelInterceptor so afterMessageHandled() runs on the same
 * thread as the message handler, ensuring thread-safe TenantContext cleanup.
 */
@Component
public class TenantChannelInterceptor implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantChannelInterceptor.class);

    /** The single {@code /topic/} feature segment (parts[2]) that carries a shop id. */
    private static final String KITCHEN_FEATURE = "kitchen";

    private final JwtDecoder jwtDecoder;
    private final ShopAccessService shopAccessService;

    public TenantChannelInterceptor(JwtDecoder jwtDecoder, ShopAccessService shopAccessService) {
        this.jwtDecoder = jwtDecoder;
        this.shopAccessService = shopAccessService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT -> authenticateConnection(accessor);
            case SUBSCRIBE -> validateSubscription(accessor);
            case SEND -> propagateTenantContext(accessor);
            default -> { /* DISCONNECT, ACK, etc. — no action needed */ }
        }
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel,
                                     MessageHandler handler, Exception ex) {
        // Always clear TenantContext — runs on same thread as handler (ExecutorChannelInterceptor contract)
        TenantContext.clear();
    }

    private void authenticateConnection(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs == null) {
            throw new MessageDeliveryException("No session attributes");
        }

        // The STOMP CONNECT Authorization header is the ONLY token path. The
        // /ws handshake query-parameter path was removed for #113: a JWT carried
        // in the URL leaks via access logs, reverse proxies and Referer headers.
        String token = extractTokenFromConnectHeaders(accessor);
        if (token == null || token.isBlank()) {
            throw new MessageDeliveryException("Missing JWT token");
        }

        Jwt jwt = jwtDecoder.decode(token); // throws on invalid/expired
        UUID tenantId = extractTenantId(jwt);
        sessionAttrs.put("tenantId", tenantId);

        // Set authenticated user principal
        accessor.setUser(new JwtAuthenticationToken(jwt));
        log.debug("WebSocket CONNECT authenticated for tenant {}", tenantId);
    }

    private String extractTokenFromConnectHeaders(StompHeaderAccessor accessor) {
        java.util.List<String> authHeaders = accessor.getNativeHeader("Authorization");
        if (authHeaders != null && !authHeaders.isEmpty()) {
            String value = authHeaders.get(0);
            if (value != null && value.startsWith("Bearer ")) {
                return value.substring(7);
            }
            return value;
        }
        return null;
    }

    private void validateSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        // Enforce tenant isolation on ALL /topic/ subscriptions.
        // Convention: /topic/{feature}/{tenantId}/{...}  (e.g. /topic/kitchen/{tid}/{shopId})
        // Any topic destination with a tenant segment must match the session tenant.
        if (!destination.startsWith("/topic/")) {
            return;
        }

        UUID sessionTenant = getSessionTenant(accessor);
        String[] parts = destination.split("/");
        // parts: ["", "topic", "{feature}", "{tenantId}", ...]
        if (parts.length < 4) {
            log.warn("Subscription to topic without tenant segment denied: {}", destination);
            throw new MessageDeliveryException("Topic subscriptions require a tenant segment");
        }

        try {
            UUID destTenant = UUID.fromString(parts[3]);
            if (!destTenant.equals(sessionTenant)) {
                log.warn("Cross-tenant subscription denied: session={}, destination={}", sessionTenant, destTenant);
                throw new MessageDeliveryException("Cross-tenant subscription denied");
            }
        } catch (IllegalArgumentException e) {
            throw new MessageDeliveryException("Invalid tenant ID in destination: " + parts[3]);
        }

        // CR-02: the tenant wall above is not enough for the KDS kitchen topic, whose
        // {shopId} segment (parts[4]) carries live order state changes for ONE shop. A
        // subscriber granted only shop A could otherwise SUBSCRIBE to shop B's feed within
        // its own tenant. Grant-check the shop segment AFTER the tenant check has passed, so
        // a cross-tenant subscribe still fails with the cross-tenant message, not a shop one.
        if (KITCHEN_FEATURE.equals(parts[2])) {
            validateShopSubscription(accessor, sessionTenant, parts, destination);
        }
    }

    /**
     * Grant-check the shop segment of a kitchen subscription against the subscriber's OWN
     * identity (CR-02). Resolved from the STOMP session principal set at CONNECT, NOT the
     * ambient security context — see {@link ShopAccessService#canAccessShop} for why the
     * ambient path would inherit the internal-caller bypass and fail OPEN. Denies on an
     * absent/non-UUID identity or a missing/malformed shop segment (the same
     * infer-no-trust-from-absence discipline as CR-03).
     */
    private void validateShopSubscription(StompHeaderAccessor accessor, UUID sessionTenant,
                                          String[] parts, String destination) {
        // The kitchen topic REQUIRES a shop segment; its absence is malformed, not tenant-wide.
        if (parts.length < 5 || parts[4] == null || parts[4].isBlank()) {
            log.warn("Kitchen subscription without a shop segment denied: {}", destination);
            throw new MessageDeliveryException("Kitchen subscriptions require a shop segment");
        }
        UUID shopId;
        try {
            shopId = UUID.fromString(parts[4]);
        } catch (IllegalArgumentException e) {
            log.warn("Kitchen subscription with a malformed shop segment denied: {}", destination);
            throw new MessageDeliveryException("Invalid shop ID in destination: " + parts[4]);
        }

        // A SUBSCRIBE always follows an authenticated CONNECT, so an absent or non-UUID
        // subscriber identity here is anomalous — DENY it, never infer trust from absence (the
        // CR-03 defect class one transport down).
        Jwt jwt = subscriberJwt(accessor);
        if (jwt == null) {
            log.warn("Kitchen subscription denied — no authenticated subscriber identity: {}", destination);
            throw new MessageDeliveryException("Subscriber identity required");
        }
        UUID subjectId = parseSubject(jwt);
        if (subjectId == null) {
            log.warn("Kitchen subscription denied — subscriber subject is not a UUID: {}", destination);
            throw new MessageDeliveryException("Subscriber identity required");
        }

        // The CONNECT path builds `new JwtAuthenticationToken(jwt)` with NO authority
        // conversion, so the realm-admin bridge cannot be read from authorities here. This is
        // the ONE place the project re-parses realm_access.roles directly (elsewhere
        // KeycloakRealmRoleConverter maps the realm role `admin` -> ROLE_admin at
        // resource-server setup); it is deliberate, not an oversight — the boolean feeds the
        // shared decision ladder in ShopAccessService.canAccessShop.
        boolean realmAdmin = hasRealmAdminRole(jwt);

        // Pin the tenant GUC around the RLS-scoped grant read, then ALWAYS clear it in a
        // finally HERE (not afterMessageHandled, which does not run for a rejected preSend):
        // the inbound channel thread is pooled, so a leaked TenantContext would be a
        // cross-tenant hazard worse than the CR-02 leak being fixed (T-23-11-04).
        try {
            TenantContext.set(sessionTenant);
            if (!shopAccessService.canAccessShop(sessionTenant, subjectId, realmAdmin, shopId)) {
                log.warn("Shop-scoped subscription denied: session={}, subject={}, shop={}",
                        sessionTenant, subjectId, shopId);
                throw new MessageDeliveryException("Shop-scoped subscription denied");
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** The subscriber's {@link Jwt} from the STOMP session principal, or null if absent/other. */
    private Jwt subscriberJwt(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user instanceof JwtAuthenticationToken token && token.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    private static UUID parseSubject(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null) {
            return null;
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Re-parse Keycloak's {@code realm_access.roles} for the {@code admin} realm role,
     * mirroring {@link uk.jtoye.core.security.KeycloakRealmRoleConverter} (which maps
     * {@code admin -> ROLE_admin} at resource-server setup). Applied HERE because the STOMP
     * CONNECT path builds the principal WITHOUT authority conversion, so the realm-admin
     * bridge is not available as an authority on this thread.
     */
    private boolean hasRealmAdminRole(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            return false;
        }
        Object roles = realmAccessMap.get("roles");
        if (!(roles instanceof Collection<?> roleCollection)) {
            return false;
        }
        return roleCollection.stream().anyMatch("admin"::equals);
    }

    private void propagateTenantContext(StompHeaderAccessor accessor) {
        UUID tenantId = getSessionTenant(accessor);
        if (tenantId != null) {
            TenantContext.set(tenantId);
        }
    }

    private UUID getSessionTenant(StompHeaderAccessor accessor) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        return attrs != null ? (UUID) attrs.get("tenantId") : null;
    }

    /**
     * Extract tenant ID from JWT claims. Uses same claim preference order as JwtTenantFilter:
     * tenant_id, tenantId, tid.
     */
    private UUID extractTenantId(Jwt jwt) {
        for (String claim : new String[]{"tenant_id", "tenantId", "tid"}) {
            Object v = jwt.getClaim(claim);
            if (v instanceof String s) {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException ignore) {
                }
            }
        }
        throw new MessageDeliveryException("JWT missing tenant claim");
    }
}
