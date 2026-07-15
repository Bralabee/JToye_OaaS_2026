package uk.jtoye.core.webhook;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Builds the dedicated {@link WebClient} the {@link WebhookDeliveryWorker} uses
 * for vendor egress (COMMS-05). It is deliberately SEPARATE from the app-wide
 * {@code WebClient.Builder}: the SSRF address-resolver guard must apply ONLY to
 * vendor-supplied webhook targets, never to trusted in-cluster egress such as
 * {@code keycloak:8080} or Stripe (a global customizer would wrongly block those
 * private/DNS hostnames).
 *
 * <p>The {@link SsrfGuardAddressResolverGroup} makes the address Netty connects to
 * the same address that was validated — closing the DNS-rebinding TOCTOU
 * (T-22-05-03 / CR-01). Redirects are disabled because a {@code 3xx} to a private
 * host would otherwise re-open the same SSRF surface after the initial validation.
 */
@Configuration
public class WebhookDeliveryClientConfig {

    @Bean
    public WebClient webhookDeliveryWebClient(WebClient.Builder builder, WebhookUrlValidator urlValidator) {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(false) // a 3xx to a private host would re-open SSRF (T-22-05-03)
                .resolver(new SsrfGuardAddressResolverGroup(
                        DefaultAddressResolverGroup.INSTANCE, urlValidator::isAddressAllowed));

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
