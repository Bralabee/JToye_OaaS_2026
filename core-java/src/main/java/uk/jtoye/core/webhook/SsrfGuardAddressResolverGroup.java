package uk.jtoye.core.webhook;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Predicate;

/**
 * A Reactor-Netty {@link AddressResolverGroup} that closes the webhook-delivery
 * SSRF / DNS-rebinding TOCTOU (T-22-05-03 / CR-01).
 *
 * <p>The prior guard validated a vendor URL by resolving its host, checking the
 * IP, then <em>discarding</em> it — after which Reactor Netty performed a second,
 * independent DNS resolution for the actual TCP connect. An attacker-controlled
 * authoritative DNS server (TTL 0) could return a public IP to the validation
 * lookup and {@code 169.254.169.254} (cloud metadata) to the connection lookup.
 *
 * <p>This group is wired into the delivery {@link org.springframework.web.reactive.function.client.WebClient}'s
 * {@code HttpClient} so that the resolution Netty uses <b>for the connection</b>
 * is the very resolution that gets validated — a single lookup, no window. Every
 * resolved address is tested against {@code allowed}; if any is disallowed the
 * resolve future fails and the connection is never opened. Because only the DNS
 * lookup is intercepted (not the request URI), the {@code Host} header, TLS SNI
 * and certificate hostname verification all still target the original hostname.
 *
 * <p>The {@code allowed} predicate is {@link WebhookUrlValidator#isAddressAllowed}
 * — the single source of truth for the private/loopback/link-local/metadata
 * classification — so create-time validation, delivery-time early-reject, and
 * connection-time pinning can never diverge.
 */
public final class SsrfGuardAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final AddressResolverGroup<InetSocketAddress> delegate;
    private final Predicate<InetAddress> allowed;

    public SsrfGuardAddressResolverGroup(AddressResolverGroup<InetSocketAddress> delegate,
                                         Predicate<InetAddress> allowed) {
        this.delegate = delegate;
        this.allowed = allowed;
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new SsrfGuardResolver(executor, delegate.getResolver(executor), allowed);
    }

    /**
     * Delegates the actual DNS lookup, then rejects the resolution if any returned
     * address is disallowed. Netty connects to exactly what this resolver returns,
     * so a rejection here means no socket is ever opened to the bad address.
     */
    static final class SsrfGuardResolver extends AbstractAddressResolver<InetSocketAddress> {

        private final AddressResolver<InetSocketAddress> delegate;
        private final Predicate<InetAddress> allowed;

        SsrfGuardResolver(EventExecutor executor,
                          AddressResolver<InetSocketAddress> delegate,
                          Predicate<InetAddress> allowed) {
            super(executor);
            this.delegate = delegate;
            this.allowed = allowed;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            return !address.isUnresolved();
        }

        @Override
        protected void doResolve(InetSocketAddress unresolvedAddress, Promise<InetSocketAddress> promise) {
            delegate.resolve(unresolvedAddress).addListener((FutureListener<InetSocketAddress>) future -> {
                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }
                InetSocketAddress resolved = future.getNow();
                InetAddress ip = resolved.getAddress();
                if (ip != null && !allowed.test(ip)) {
                    promise.setFailure(blocked(ip));
                } else {
                    promise.setSuccess(resolved);
                }
            });
        }

        @Override
        protected void doResolveAll(InetSocketAddress unresolvedAddress, Promise<List<InetSocketAddress>> promise) {
            delegate.resolveAll(unresolvedAddress).addListener((FutureListener<List<InetSocketAddress>>) future -> {
                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }
                List<InetSocketAddress> resolved = future.getNow();
                for (InetSocketAddress addr : resolved) {
                    InetAddress ip = addr.getAddress();
                    if (ip != null && !allowed.test(ip)) {
                        promise.setFailure(blocked(ip));
                        return;
                    }
                }
                promise.setSuccess(resolved);
            });
        }

        @Override
        public void close() {
            delegate.close();
        }

        private static UnknownHostException blocked(InetAddress ip) {
            // Host is not logged with the address here; the delivery worker records
            // only the failure class (T-22-05-06). Message aids test assertions.
            return new UnknownHostException(
                    "webhook egress blocked: target resolved to a disallowed address " + ip.getHostAddress());
        }
    }
}
