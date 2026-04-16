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

    private final JwtDecoder jwtDecoder;

    public TenantChannelInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
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

        // Prefer token from STOMP CONNECT frame headers (avoids URL query param leakage).
        // Fall back to session attribute from handshake for backwards compatibility.
        String token = extractTokenFromConnectHeaders(accessor);
        if (token == null || token.isBlank()) {
            token = (String) sessionAttrs.get("jwt_token");
        }
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
