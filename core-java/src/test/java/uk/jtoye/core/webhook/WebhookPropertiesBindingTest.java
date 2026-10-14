package uk.jtoye.core.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@code webhook.delivery.*} and {@code webhook.envelope.*} key in {@code application.yml} binds to a {@link WebhookProperties} field (37-43).
 *
 * <p>Why this exists: {@code @ConfigurationProperties} ignores an unknown key, so when 37-43 replaced the
 * attempt threshold ({@code auto-pause-threshold}) with {@code auto-pause-after-failed-deliveries}, a yml
 * line left behind would have bound to nothing and silently left an operator's override without effect.
 * {@code ConfigKeyContractTest} cannot see this: it judges only {@code spring.*}, {@code management.*},
 * {@code server.*} and {@code logging.*} keys (its report holds no {@code webhook} row). This test binds the
 * shipped yml with {@link NoUnboundElementsBindHandler}, which fails on any key no field claims.
 */
class WebhookPropertiesBindingTest {

    private static Binder shippedYaml() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).as("application.yml is on the test classpath").isNotEmpty();
        // The yml values are ${ENV:default} placeholders; resolve them to the defaults the jar ships.
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    @DisplayName("every webhook.* key in application.yml binds, and the auto-pause rule defaults to 3 failed deliveries")
    void shippedWebhookKeysAllBind() throws Exception {
        // The two sections this class owns. webhook.target.* is read by @Value in WebhookUrlValidator and
        // WebhookDeliveryClientConfig, so binding the whole "webhook" prefix would flag it as unbound.
        Binder binder = shippedYaml();
        WebhookProperties bound = new WebhookProperties();
        binder.bind("webhook.delivery", Bindable.ofInstance(bound.getDelivery()),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();
        binder.bind("webhook.envelope", Bindable.ofInstance(bound.getEnvelope()),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).get();

        assertThat(bound.getDelivery().getAutoPauseAfterFailedDeliveries()).isEqualTo(3);
        assertThat(bound.getDelivery().getMaxAttempts()).isEqualTo(8);
        assertThat(new WebhookProperties().getDelivery().getAutoPauseAfterFailedDeliveries())
                .as("the field default matches the yml default").isEqualTo(3);
    }

    @Test
    @DisplayName("control: the retired attempt-threshold key is refused, so the arm above can fail")
    void retiredAttemptThresholdKeyIsRefused() {
        Binder binder = new Binder(ConfigurationPropertySources.from(new MapPropertySource("retired",
                Map.of("webhook.delivery.auto-pause-threshold", "10"))));

        assertThatThrownBy(() -> binder.bind("webhook.delivery",
                Bindable.ofInstance(new WebhookProperties().getDelivery()),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT)))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("auto-pause-threshold");
    }
}
