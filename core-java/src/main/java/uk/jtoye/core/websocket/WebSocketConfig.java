package uk.jtoye.core.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket/STOMP configuration for real-time KDS communication.
 *
 * Supports two broker modes controlled by {@code stomp.broker.mode}:
 * <ul>
 *   <li>{@code in-memory} (default) -- uses SimpleBroker, suitable for single-replica dev</li>
 *   <li>{@code relay} -- uses StompBrokerRelay over RabbitMQ STOMP plugin (port 61613),
 *       required for horizontal scaling so all replicas share broadcasts</li>
 * </ul>
 *
 * JWT authentication is handled at STOMP level by TenantChannelInterceptor,
 * not at HTTP level (SecurityConfig permits /ws/**).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final TenantChannelInterceptor tenantChannelInterceptor;

    @Value("${stomp.broker.mode:in-memory}")
    private String brokerMode;

    @Value("${stomp.broker.relay-host:localhost}")
    private String relayHost;

    @Value("${stomp.broker.relay-port:61613}")
    private int relayPort;

    @Value("${stomp.broker.client-login:guest}")
    private String clientLogin;

    @Value("${stomp.broker.client-passcode:guest}")
    private String clientPasscode;

    @Value("${stomp.broker.system-login:guest}")
    private String systemLogin;

    @Value("${stomp.broker.system-passcode:guest}")
    private String systemPasscode;

    public WebSocketConfig(TenantChannelInterceptor tenantChannelInterceptor) {
        this.tenantChannelInterceptor = tenantChannelInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        if ("relay".equals(brokerMode)) {
            config.enableStompBrokerRelay("/topic", "/queue")
                  .setRelayHost(relayHost)
                  .setRelayPort(relayPort)
                  .setClientLogin(clientLogin)
                  .setClientPasscode(clientPasscode)
                  .setSystemLogin(systemLogin)
                  .setSystemPasscode(systemPasscode);
            log.info("STOMP broker relay configured: {}:{}", relayHost, relayPort);
        } else {
            config.enableSimpleBroker("/topic");
            log.info("In-memory simple broker configured");
        }
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        // D-09: No .withSockJS() -- modern browsers all support native WebSocket
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(tenantChannelInterceptor);
    }
}
