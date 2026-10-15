package uk.jtoye.core.security;

import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Refuses to start the web server when anything but {@link ClientIpResolver} could decide who the
 * client is (T-37-59, phase 37 security audit).
 *
 * <p><b>Why a guard and not only the property.</b> {@code server.forward-headers-strategy: none} in
 * {@code application.yml} is the fix, but three other doors reach the same place, and none of
 * them is visible from that file: an environment variable ({@code SERVER_FORWARD_HEADERS_STRATEGY})
 * outranks it; an UNSET strategy (a profile or config location that drops the key) is deduced ON
 * from {@code CloudPlatform.KUBERNETES} by Boot 4.1's {@code TomcatWebServerFactoryCustomizer};
 * and {@code server.tomcat.remoteip.remote-ip-header} / {@code protocol-header} install the valve
 * on their own, whatever the strategy says. Each would put Tomcat's {@code RemoteIpValve} — which
 * trusts every private and loopback range — in front of the resolver, so its trust-none default and
 * its checked CIDR list would stop deciding anything, with every compose test still green.
 *
 * <p>So this checks both the input and the effect. It runs AFTER Boot's customizer (which is order
 * 0 and is what adds the valve) and inspects the factory's live engine-valve list: a
 * {@link RemoteIpValve} there means some door was opened, and the server does not start. The same
 * fail-closed shape as the resolver refusing {@code /0} at startup.
 */
@Component
public class ForwardHeadersGuard implements WebServerFactoryCustomizer<TomcatWebServerFactory>, Ordered {

    /** The property this guard requires to be {@code none}. */
    public static final String STRATEGY_PROPERTY = "server.forward-headers-strategy";

    private final Environment environment;

    public ForwardHeadersGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(TomcatWebServerFactory factory) {
        requireStrategyNone(environment.getProperty(STRATEGY_PROPERTY));
        for (Valve valve : factory.getEngineValves()) {
            if (valve instanceof RemoteIpValve) {
                throw new IllegalStateException("T-37-59: a Tomcat RemoteIpValve is in the engine pipeline. "
                        + "It would rewrite the client address, scheme and host from X-Forwarded-* headers "
                        + "trusted from every private range, before ClientIpResolver runs. Remove "
                        + "server.tomcat.remoteip.* and keep " + STRATEGY_PROPERTY + "=none; declare the "
                        + "proxies to believe in " + ClientIpResolver.TRUSTED_PROXIES_PROPERTY + " instead.");
            }
        }
    }

    /**
     * @param strategy the resolved {@code server.forward-headers-strategy}, possibly null
     * @throws IllegalStateException unless it is {@code none} (any case, relaxed binding)
     */
    static void requireStrategyNone(String strategy) {
        if (strategy != null && strategy.trim().toLowerCase(Locale.ROOT).equals("none")) {
            return;
        }
        throw new IllegalStateException("T-37-59: " + STRATEGY_PROPERTY + " must be 'none' but is "
                + (strategy == null ? "UNSET, which Boot deduces ON for Kubernetes" : "'" + strategy + "'")
                + ". Any other value lets X-Forwarded-* headers decide the client before "
                + ClientIpResolver.class.getSimpleName() + " and its "
                + ClientIpResolver.TRUSTED_PROXIES_PROPERTY + " list.");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
