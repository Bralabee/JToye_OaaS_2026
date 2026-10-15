package uk.jtoye.core.security;

import org.apache.catalina.valves.AccessLogValve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-37-59: {@link ForwardHeadersGuard} refuses every door that would put a second trust list in
 * front of {@link ClientIpResolver}, and lets the one safe configuration through. Each refusal arm
 * sits beside a passing control, so the guard is shown able to both fail and pass.
 */
class ForwardHeadersGuardTest {

    @Test
    void none_inAnyCase_isAccepted() {
        assertThatCode(() -> ForwardHeadersGuard.requireStrategyNone("none")).doesNotThrowAnyException();
        assertThatCode(() -> ForwardHeadersGuard.requireStrategyNone(" NONE ")).doesNotThrowAnyException();
    }

    @Test
    void unset_isRefused_becauseBootDeducesItOnForKubernetes() {
        assertThatThrownBy(() -> ForwardHeadersGuard.requireStrategyNone(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNSET")
                .hasMessageContaining(ForwardHeadersGuard.STRATEGY_PROPERTY);
    }

    @Test
    void nativeAndFramework_areRefused() {
        assertThatThrownBy(() -> ForwardHeadersGuard.requireStrategyNone("native"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'native'");
        assertThatThrownBy(() -> ForwardHeadersGuard.requireStrategyNone("FRAMEWORK"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("'FRAMEWORK'");
    }

    @Test
    void aRemoteIpValveInThePipeline_isRefused_evenWithStrategyNone() {
        // server.tomcat.remoteip.remote-ip-header installs the valve whatever the strategy says.
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addEngineValves(new RemoteIpValve());
        ForwardHeadersGuard guard = new ForwardHeadersGuard(
                new MockEnvironment().withProperty(ForwardHeadersGuard.STRATEGY_PROPERTY, "none"));

        assertThatThrownBy(() -> guard.customize(factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RemoteIpValve")
                .hasMessageContaining(ClientIpResolver.TRUSTED_PROXIES_PROPERTY);
    }

    @Test
    void control_otherValvesAndStrategyNone_pass() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.addEngineValves(new AccessLogValve());
        ForwardHeadersGuard guard = new ForwardHeadersGuard(
                new MockEnvironment().withProperty(ForwardHeadersGuard.STRATEGY_PROPERTY, "none"));

        assertThatCode(() -> guard.customize(factory)).doesNotThrowAnyException();
    }
}
