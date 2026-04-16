package uk.jtoye.core.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import uk.jtoye.core.security.TenantContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantChannelInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    private TenantChannelInterceptor interceptor;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID SHOP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new TenantChannelInterceptor(jwtDecoder);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- CONNECT tests ---

    @Test
    void shouldAuthenticateValidJwtOnConnect() {
        Jwt jwt = buildJwt("tenant_id", TENANT_A.toString());
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "valid-token");
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
        assertThat(accessor.getUser()).isNotNull();
    }

    @Test
    void shouldRejectMissingToken() {
        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, null);

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Missing JWT token");
    }

    @Test
    void shouldRejectBlankToken() {
        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "   ");

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Missing JWT token");
    }

    @Test
    void shouldRejectInvalidToken() {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new JwtException("Invalid token"));

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "invalid-token");

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectJwtWithNoTenantClaim() {
        Jwt jwt = Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .claim("sub", "user1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("no-tenant-token")).thenReturn(jwt);

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "no-tenant-token");

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("JWT missing tenant claim");
    }

    @Test
    void shouldExtractTenantIdFromTenantIdClaim() {
        Jwt jwt = buildJwt("tenant_id", TENANT_A.toString());
        when(jwtDecoder.decode("token-a")).thenReturn(jwt);

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "token-a");
        interceptor.preSend(message, mock(MessageChannel.class));

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        assertThat(accessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
    }

    @Test
    void shouldExtractTenantIdFromTenantIdCamelCaseClaim() {
        Jwt jwt = buildJwt("tenantId", TENANT_A.toString());
        when(jwtDecoder.decode("token-camel")).thenReturn(jwt);

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "token-camel");
        interceptor.preSend(message, mock(MessageChannel.class));

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        assertThat(accessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
    }

    @Test
    void shouldExtractTenantIdFromTidClaim() {
        Jwt jwt = buildJwt("tid", TENANT_A.toString());
        when(jwtDecoder.decode("token-tid")).thenReturn(jwt);

        Message<?> message = buildStompMessage(StompCommand.CONNECT, null, "token-tid");
        interceptor.preSend(message, mock(MessageChannel.class));

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        assertThat(accessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
    }

    @Test
    void shouldAuthenticateViaStompConnectHeader() {
        Jwt jwt = buildJwt("tenant_id", TENANT_A.toString());
        when(jwtDecoder.decode("header-token")).thenReturn(jwt);

        // No jwt_token in session — token only in STOMP CONNECT Authorization header
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("Authorization", "Bearer header-token");
        Map<String, Object> sessionAttrs = new HashMap<>();
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        assertThat(resultAccessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
        assertThat(resultAccessor.getUser()).isNotNull();
    }

    @Test
    void shouldPreferStompHeaderOverSessionAttribute() {
        Jwt jwtA = buildJwt("tenant_id", TENANT_A.toString());
        when(jwtDecoder.decode("header-token")).thenReturn(jwtA);

        // Both session and header have tokens — header should win
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        accessor.addNativeHeader("Authorization", "Bearer header-token");
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("jwt_token", "session-token");
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, mock(MessageChannel.class));

        // Should have decoded "header-token", not "session-token"
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(message);
        assertThat(resultAccessor.getSessionAttributes()).containsEntry("tenantId", TENANT_A);
    }

    // --- SUBSCRIBE tests ---

    @Test
    void shouldAllowOwnTenantSubscription() {
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    @Test
    void shouldBlockCrossTenantSubscription() {
        String destination = "/topic/kitchen/" + TENANT_B + "/" + SHOP_ID;
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Cross-tenant subscription denied");
    }

    @Test
    void shouldRejectInvalidTenantIdInDestination() {
        String destination = "/topic/kitchen/not-a-uuid/" + SHOP_ID;
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Invalid tenant ID in destination");
    }

    @Test
    void shouldRejectTopicWithoutTenantSegment() {
        String destination = "/topic/other";
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Topic subscriptions require a tenant segment");
    }

    @Test
    void shouldAllowNonKitchenTopicWithCorrectTenant() {
        String destination = "/topic/notifications/" + TENANT_A + "/updates";
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    @Test
    void shouldBlockCrossTenantNonKitchenTopic() {
        String destination = "/topic/notifications/" + TENANT_B + "/updates";
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Cross-tenant subscription denied");
    }

    @Test
    void shouldAllowNonTopicSubscription() {
        String destination = "/queue/reply-session123";
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    // --- SEND / TenantContext tests ---

    @Test
    void shouldSetTenantContextOnSend() {
        Message<?> message = buildStompMessage(StompCommand.SEND, "/app/kitchen/ack", null, TENANT_A);

        interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(TenantContext.get()).isPresent().hasValue(TENANT_A);
    }

    @Test
    void shouldClearTenantContextAfterMessageHandled() {
        TenantContext.set(TENANT_A);
        Message<?> message = buildStompMessage(StompCommand.SEND, "/app/kitchen/ack", null, TENANT_A);

        interceptor.afterMessageHandled(message, mock(MessageChannel.class), mock(MessageHandler.class), null);

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void shouldClearTenantContextAfterMessageHandledEvenOnException() {
        TenantContext.set(TENANT_A);
        Message<?> message = buildStompMessage(StompCommand.SEND, "/app/kitchen/ack", null, TENANT_A);

        interceptor.afterMessageHandled(message, mock(MessageChannel.class),
                mock(MessageHandler.class), new RuntimeException("handler error"));

        assertThat(TenantContext.get()).isEmpty();
    }

    // --- Helper methods ---

    private Jwt buildJwt(String claimName, String claimValue) {
        return Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .claim(claimName, claimValue)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private Message<?> buildStompMessage(StompCommand command, String destination, String jwtToken) {
        return buildStompMessage(command, destination, jwtToken, null);
    }

    private Message<?> buildStompMessage(StompCommand command, String destination, String jwtToken, UUID tenantId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }

        Map<String, Object> sessionAttrs = new HashMap<>();
        if (jwtToken != null) {
            sessionAttrs.put("jwt_token", jwtToken);
        }
        if (tenantId != null) {
            sessionAttrs.put("tenantId", tenantId);
        }
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
