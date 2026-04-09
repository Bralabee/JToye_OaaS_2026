package uk.jtoye.core.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Extracts JWT token from the WebSocket handshake query parameter and stores it
 * in session attributes for later validation by TenantChannelInterceptor on STOMP CONNECT.
 *
 * The browser WebSocket API does not support custom HTTP headers, so the JWT is passed
 * as a query parameter: /ws?token=<jwt>
 */
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        if (query != null) {
            String token = extractTokenFromQuery(query);
            if (token != null && !token.isBlank()) {
                attributes.put("jwt_token", token);
                log.debug("JWT token extracted from WebSocket handshake query parameter");
            }
        }
        // Always allow handshake — reject at STOMP CONNECT level, not HTTP upgrade
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // No-op
    }

    private String extractTokenFromQuery(String query) {
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }
}
