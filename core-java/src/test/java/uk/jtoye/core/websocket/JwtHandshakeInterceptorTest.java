package uk.jtoye.core.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtHandshakeInterceptorTest {

    private JwtHandshakeInterceptor interceptor;
    private ServerHttpResponse response;
    private WebSocketHandler wsHandler;

    @BeforeEach
    void setUp() {
        interceptor = new JwtHandshakeInterceptor();
        response = mock(ServerHttpResponse.class);
        wsHandler = mock(WebSocketHandler.class);
    }

    @Test
    void shouldExtractTokenFromQueryString() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws?token=abc123"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("jwt_token", "abc123");
    }

    @Test
    void shouldHandleMissingTokenGracefully() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).doesNotContainKey("jwt_token");
    }

    @Test
    void shouldHandleEmptyTokenValue() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws?token="));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).doesNotContainKey("jwt_token");
    }

    @Test
    void shouldExtractTokenFromMultipleQueryParams() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws?foo=bar&token=abc123&baz=qux"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).containsEntry("jwt_token", "abc123");
    }

    @Test
    void shouldHandleNullQueryString() throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(new URI("ws://localhost/ws"));
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes).doesNotContainKey("jwt_token");
    }
}
