package uk.jtoye.core.webhook;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link SsrfGuardAddressResolverGroup} closes T-22-05-03 / CR-01
 * (webhook-delivery SSRF via DNS rebinding). The guard sits at Netty's DNS layer,
 * so the address Netty CONNECTS to is exactly the address that was validated —
 * there is no resolve-then-discard window an attacker's DNS server can exploit.
 *
 * <p>Hermetic: a fake delegate resolver maps hostnames to controlled IP literals
 * (no real DNS), simulating an authoritative server that "rebinds" a host to a
 * private/metadata address.
 */
class WebhookSsrfResolverTest {

    // block-private-ranges ON: the production SSRF posture.
    private final WebhookUrlValidator blockingValidator = new WebhookUrlValidator(true);
    // block-private-ranges OFF: the dev/test hermetic posture (toggle honoured).
    private final WebhookUrlValidator permissiveValidator = new WebhookUrlValidator(false);

    private AddressResolver<InetSocketAddress> guardedResolver(WebhookUrlValidator validator,
                                                               Map<String, String> hostToIp) {
        AddressResolverGroup<InetSocketAddress> fakeDns = new FakeDnsResolverGroup(hostToIp);
        return new SsrfGuardAddressResolverGroup(fakeDns, validator::isAddressAllowed)
                .getResolver(ImmediateEventExecutor.INSTANCE);
    }

    private Future<InetSocketAddress> resolve(AddressResolver<InetSocketAddress> resolver, String host) {
        return resolver.resolve(InetSocketAddress.createUnresolved(host, 443)).awaitUninterruptibly();
    }

    @Test
    void rebindToCloudMetadataAddressIsRejected() {
        var resolver = guardedResolver(blockingValidator,
                Map.of("evil-rebind.example", "169.254.169.254")); // Azure/AWS/GCP metadata

        Future<InetSocketAddress> result = resolve(resolver, "evil-rebind.example");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.cause()).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rebindToRfc1918AddressIsRejected() {
        var resolver = guardedResolver(blockingValidator, Map.of("evil.example", "10.0.0.5"));

        Future<InetSocketAddress> result = resolve(resolver, "evil.example");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.cause()).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void loopbackRebindIsRejected() {
        var resolver = guardedResolver(blockingValidator, Map.of("localhost-rebind.example", "127.0.0.1"));

        assertThat(resolve(resolver, "localhost-rebind.example").isSuccess()).isFalse();
    }

    @Test
    void publicAddressResolvesToTheExactValidatedAddress() {
        // The address the connection would use IS the address that was validated —
        // no rebinding window. 93.184.216.34 is example.com's documentation IP.
        var resolver = guardedResolver(blockingValidator, Map.of("good.example", "93.184.216.34"));

        Future<InetSocketAddress> result = resolve(resolver, "good.example");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getNow().getAddress().getHostAddress()).isEqualTo("93.184.216.34");
    }

    @Test
    void multiRecordSetIsRejectedIfAnyAddressIsPrivate() throws Exception {
        // An attacker returning a benign public A-record alongside a private one
        // must not slip the private one through — resolveAll rejects the whole set.
        var group = new SsrfGuardAddressResolverGroup(
                new FakeDnsResolverGroup(Map.of()) {
                    @Override
                    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                        return new FakeMultiResolver(executor,
                                List.of("93.184.216.34", "169.254.169.254"));
                    }
                },
                blockingValidator::isAddressAllowed);
        AddressResolver<InetSocketAddress> resolver = group.getResolver(ImmediateEventExecutor.INSTANCE);

        Future<List<InetSocketAddress>> all =
                resolver.resolveAll(InetSocketAddress.createUnresolved("mixed.example", 443))
                        .awaitUninterruptibly();

        assertThat(all.isSuccess()).isFalse();
        assertThat(all.cause()).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void toggleOffAllowsPrivateAddresses() {
        // block-private-ranges=false (dev/test) must let a private target through so
        // hermetic tests can hit a local mock server — matches WebhookUrlValidator.
        var resolver = guardedResolver(permissiveValidator, Map.of("mock.local", "127.0.0.1"));

        assertThat(resolve(resolver, "mock.local").isSuccess()).isTrue();
    }

    // --- Fake DNS: maps a hostname to a controlled IP literal, no real lookup ----

    private static class FakeDnsResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        private final Map<String, String> hostToIp;

        FakeDnsResolverGroup(Map<String, String> hostToIp) {
            this.hostToIp = hostToIp;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return !address.isUnresolved();
                }

                @Override
                protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise)
                        throws Exception {
                    String ip = hostToIp.get(unresolved.getHostString());
                    if (ip == null) {
                        promise.setFailure(new UnknownHostException(unresolved.getHostString()));
                        return;
                    }
                    promise.setSuccess(new InetSocketAddress(
                            InetAddress.getByName(ip), unresolved.getPort()));
                }

                @Override
                protected void doResolveAll(InetSocketAddress unresolved,
                                            Promise<List<InetSocketAddress>> promise) throws Exception {
                    String ip = hostToIp.get(unresolved.getHostString());
                    if (ip == null) {
                        promise.setFailure(new UnknownHostException(unresolved.getHostString()));
                        return;
                    }
                    promise.setSuccess(List.of(new InetSocketAddress(
                            InetAddress.getByName(ip), unresolved.getPort())));
                }
            };
        }
    }

    /** A resolver whose resolveAll returns a fixed multi-address set. */
    private static class FakeMultiResolver extends AbstractAddressResolver<InetSocketAddress> {
        private final List<String> ips;

        FakeMultiResolver(EventExecutor executor, List<String> ips) {
            super(executor);
            this.ips = ips;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            return !address.isUnresolved();
        }

        @Override
        protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) throws Exception {
            promise.setSuccess(new InetSocketAddress(InetAddress.getByName(ips.get(0)), unresolved.getPort()));
        }

        @Override
        protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise)
                throws Exception {
            var list = new java.util.ArrayList<InetSocketAddress>();
            for (String ip : ips) {
                list.add(new InetSocketAddress(InetAddress.getByName(ip), unresolved.getPort()));
            }
            promise.setSuccess(list);
        }
    }
}
