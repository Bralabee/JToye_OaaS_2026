package uk.jtoye.core.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket/STOMP configuration for real-time KDS communication.
 *
 * Uses in-memory simple broker (no RabbitMQ STOMP relay needed for single replica).
 * JWT authentication is handled at STOMP level by TenantChannelInterceptor,
 * not at HTTP level (SecurityConfig permits /ws/**).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final TenantChannelInterceptor tenantChannelInterceptor;

    public WebSocketConfig(TenantChannelInterceptor tenantChannelInterceptor) {
        this.tenantChannelInterceptor = tenantChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        // D-09: No .withSockJS() — modern browsers all support native WebSocket
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tenantChannelInterceptor);
    }
}
