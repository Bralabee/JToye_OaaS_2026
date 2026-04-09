package uk.jtoye.core.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
