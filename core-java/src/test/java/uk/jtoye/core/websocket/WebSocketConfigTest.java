package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import org.springframework.messaging.simp.config.SimpleBrokerRegistration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void shouldBeAnnotatedWithEnableWebSocketMessageBroker() {
        assertThat(WebSocketConfig.class.isAnnotationPresent(EnableWebSocketMessageBroker.class)).isTrue();
    }

    @Test
    void shouldImplementWebSocketMessageBrokerConfigurer() {
        assertThat(WebSocketMessageBrokerConfigurer.class.isAssignableFrom(WebSocketConfig.class)).isTrue();
    }

    @Test
    void shouldRegisterTenantChannelInterceptorOnInboundChannel() {
        TenantChannelInterceptor mockInterceptor = mock(TenantChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(mockInterceptor);
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(mockInterceptor);
    }

    @Test
    void shouldConfigureSimpleBrokerInDefaultMode() {
        TenantChannelInterceptor mockInterceptor = mock(TenantChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(mockInterceptor);
        // brokerMode defaults to null in unit test (no Spring context)
        // so it should fall through to enableSimpleBroker (else branch)
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        SimpleBrokerRegistration simpleBrokerReg = mock(SimpleBrokerRegistration.class);
        when(registry.enableSimpleBroker("/topic")).thenReturn(simpleBrokerReg);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry, never()).enableStompBrokerRelay(any(String[].class));
    }

    @Test
    void shouldConfigureStompBrokerRelayInRelayMode() {
        TenantChannelInterceptor mockInterceptor = mock(TenantChannelInterceptor.class);
        WebSocketConfig config = new WebSocketConfig(mockInterceptor);
        ReflectionTestUtils.setField(config, "brokerMode", "relay");
        ReflectionTestUtils.setField(config, "relayHost", "rabbitmq");
        ReflectionTestUtils.setField(config, "relayPort", 61613);
        ReflectionTestUtils.setField(config, "clientLogin", "guest");
        ReflectionTestUtils.setField(config, "clientPasscode", "guest");
        ReflectionTestUtils.setField(config, "systemLogin", "guest");
        ReflectionTestUtils.setField(config, "systemPasscode", "guest");

        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        StompBrokerRelayRegistration relayReg = mock(StompBrokerRelayRegistration.class);
        when(registry.enableStompBrokerRelay("/topic", "/queue")).thenReturn(relayReg);
        when(relayReg.setRelayHost(anyString())).thenReturn(relayReg);
        when(relayReg.setRelayPort(anyInt())).thenReturn(relayReg);
        when(relayReg.setClientLogin(anyString())).thenReturn(relayReg);
        when(relayReg.setClientPasscode(anyString())).thenReturn(relayReg);
        when(relayReg.setSystemLogin(anyString())).thenReturn(relayReg);
        when(relayReg.setSystemPasscode(anyString())).thenReturn(relayReg);

        config.configureMessageBroker(registry);

        verify(registry).enableStompBrokerRelay("/topic", "/queue");
        verify(relayReg).setRelayHost("rabbitmq");
        verify(relayReg).setRelayPort(61613);
        verify(relayReg).setClientLogin("guest");
        verify(relayReg).setClientPasscode("guest");
        verify(relayReg).setSystemLogin("guest");
        verify(relayReg).setSystemPasscode("guest");
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry, never()).enableSimpleBroker(any(String[].class));
    }
}
