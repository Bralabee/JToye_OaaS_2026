package uk.jtoye.core.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantChannelInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private ShopAccessService shopAccessService;

    private TenantChannelInterceptor interceptor;

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID SHOP_ID = UUID.randomUUID();
    private static final UUID SUBJECT = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new TenantChannelInterceptor(jwtDecoder, shopAccessService);
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
    void sessionAttributeTokenIsRejected() {
        // #113: the handshake jwt_token session-attribute fallback was removed.
        // A decodable token present ONLY in session attributes (no Authorization
        // CONNECT header) must be ignored — CONNECT is rejected as tokenless.
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("jwt_token", "session-token");
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Missing JWT token");
    }

    // --- SUBSCRIBE: tenant-wall tests (unchanged behaviour) ---

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
        // A non-kitchen topic carries no shop segment → tenant check only, no shop gate.
        String destination = "/topic/notifications/" + TENANT_A + "/updates";
        Message<?> message = buildStompMessage(StompCommand.SUBSCRIBE, destination, null, TENANT_A);

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verifyNoInteractions(shopAccessService);
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
        verifyNoInteractions(shopAccessService);
    }

    // --- SUBSCRIBE: shop-segment gate (CR-02) ---

    /** Case 1 — the CR-02 proof: a scoped user is rejected from an ungranted shop's feed. */
    @Test
    void shouldRejectSubscriptionToUngrantedShop() {
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(false);
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Shop-scoped subscription denied");
    }

    /** Case 2 — a granted subscriber is permitted. */
    @Test
    void shouldAllowSubscriptionToGrantedShop() {
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(true);
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        assertThat(result).isNotNull();
    }

    /** Case 3 — no subscriber identity on the frame is a DENIAL, never inferred trust (CR-03 class). */
    @Test
    void shouldRejectSubscriptionWhenSubscriberIdentityMissing() {
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, null);  // no principal

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Subscriber identity required");
        verifyNoInteractions(shopAccessService);
    }

    /** Case 4 — a malformed (non-UUID) shop segment is rejected, never silently permitted. */
    @Test
    void shouldRejectMalformedShopSegment() {
        String destination = "/topic/kitchen/" + TENANT_A + "/not-a-uuid";
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Invalid shop ID in destination");
        verifyNoInteractions(shopAccessService);
    }

    /** Case 5 — a cross-tenant subscribe fails at the tenant wall BEFORE the shop gate runs. */
    @Test
    void shouldStillRejectCrossTenantBeforeCheckingShop() {
        String destination = "/topic/kitchen/" + TENANT_B + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Cross-tenant subscription denied");
        // Ordering proof: the shop gate is never consulted once the tenant wall rejects.
        verifyNoInteractions(shopAccessService);
    }

    /** Case 6 — the pooled inbound thread must not retain TenantContext after either outcome. */
    @Test
    void shouldNotLeakTenantContextAfterValidation() {
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;

        // Permitted subscribe.
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(true);
        interceptor.preSend(buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false)),
                mock(MessageChannel.class));
        assertThat(TenantContext.get()).as("no leaked tenant after a permitted subscribe").isEmpty();

        // Denied subscribe.
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(
                buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false)),
                mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class);
        assertThat(TenantContext.get()).as("no leaked tenant after a denied subscribe").isEmpty();
    }

    /**
     * Case 7 — the tenant, subject and SHOP segment actually reach the gate. Without this a fix
     * that parses the wrong path index would still pass cases 1-2.
     */
    @Test
    void shouldPassShopAndSubjectThroughToTheGate() {
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(true);
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        interceptor.preSend(message, mock(MessageChannel.class));

        ArgumentCaptor<UUID> tenantCap = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> userCap = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Boolean> realmCap = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<UUID> shopCap = ArgumentCaptor.forClass(UUID.class);
        verify(shopAccessService).canAccessShop(
                tenantCap.capture(), userCap.capture(), realmCap.capture(), shopCap.capture());

        assertThat(tenantCap.getValue()).as("session tenant reaches the gate").isEqualTo(TENANT_A);
        assertThat(userCap.getValue()).as("subscriber subject reaches the gate").isEqualTo(SUBJECT);
        assertThat(shopCap.getValue()).as("the SHOP segment (parts[4]) reaches the gate").isEqualTo(SHOP_ID);
        assertThat(realmCap.getValue()).as("a non-admin token yields realmAdmin=false").isFalse();
    }

    /** Case 7b — realm_access.roles is re-parsed here (no authority conversion on the STOMP thread). */
    @Test
    void shouldPassRealmAdminTrueWhenRealmAccessHasAdmin() {
        when(shopAccessService.canAccessShop(any(), any(), anyBoolean(), any())).thenReturn(true);
        String destination = "/topic/kitchen/" + TENANT_A + "/" + SHOP_ID;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, true));

        interceptor.preSend(message, mock(MessageChannel.class));

        ArgumentCaptor<Boolean> realmCap = ArgumentCaptor.forClass(Boolean.class);
        verify(shopAccessService).canAccessShop(any(), any(), realmCap.capture(), any());
        assertThat(realmCap.getValue())
                .as("realm_access.roles=[admin] is re-parsed to realmAdmin=true on the STOMP thread")
                .isTrue();
    }

    /** A kitchen destination with no shop segment is malformed, not treated as tenant-wide. */
    @Test
    void shouldRejectKitchenTopicWithoutShopSegment() {
        String destination = "/topic/kitchen/" + TENANT_A;
        Message<?> message = buildSubscribe(destination, TENANT_A, subscriberJwt(SUBJECT, false));

        assertThatThrownBy(() -> interceptor.preSend(message, mock(MessageChannel.class)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Kitchen subscriptions require a shop segment");
        verifyNoInteractions(shopAccessService);
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

    /** A subscriber JWT with a UUID subject; realm-{@code admin} role present iff {@code realmAdmin}. */
    private Jwt subscriberJwt(UUID sub, boolean realmAdmin) {
        Jwt.Builder builder = Jwt.withTokenValue("mock")
                .header("alg", "RS256")
                .subject(sub.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (realmAdmin) {
            builder.claim("realm_access", Map.of("roles", List.of("admin")));
        }
        return builder.build();
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

        // #113: the token travels ONLY in the STOMP CONNECT Authorization header
        // (the query-param / jwt_token session-attribute path was removed).
        if (jwtToken != null) {
            accessor.addNativeHeader("Authorization", "Bearer " + jwtToken);
        }

        Map<String, Object> sessionAttrs = new HashMap<>();
        if (tenantId != null) {
            sessionAttrs.put("tenantId", tenantId);
        }
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * A SUBSCRIBE frame carrying the subscriber's identity on the STOMP session principal
     * (as CONNECT sets it via {@code accessor.setUser(new JwtAuthenticationToken(jwt))}),
     * plus the session {@code tenantId}. A null {@code subscriber} models a frame with no
     * authenticated principal.
     */
    private Message<?> buildSubscribe(String destination, UUID sessionTenant, Jwt subscriber) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriber != null) {
            accessor.setUser(new JwtAuthenticationToken(subscriber));
        }
        Map<String, Object> sessionAttrs = new HashMap<>();
        if (sessionTenant != null) {
            sessionAttrs.put("tenantId", sessionTenant);
        }
        accessor.setSessionAttributes(sessionAttrs);
        accessor.setSessionId("test-session");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
