package uk.jtoye.core.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #587: the shipped retry schedule must give a receiver hours, not 127 seconds.
 *
 * <p>The schedule is judged from the values {@code application.yml} actually ships (the same binding the
 * production {@link WebhookProperties} gets) through the worker's own {@code computeBackoffMillis}, not from a
 * restatement of the numbers in the test. Before this change the defaults were 8 attempts from a 1 s base:
 * waits of 1, 2, 4 ... 64 s, 127 s in total, and a 1 h cap that no wait ever reached. A receiver that was down for
 * a deploy or a short outage lost the event for good.
 */
class WebhookRetryScheduleTest {

    private static WebhookProperties.Delivery shipped() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        WebhookProperties bound = new WebhookProperties();
        binder.bind("webhook.delivery", Bindable.ofInstance(bound.getDelivery())).get();
        return bound.getDelivery();
    }

    /** Waits between consecutive attempts: after failed attempt n (1-based) the worker waits wait(n). */
    private static List<Duration> waits(WebhookProperties.Delivery d) {
        return java.util.stream.IntStream.range(1, d.getMaxAttempts())
                .mapToObj(n -> Duration.ofMillis(WebhookDeliveryWorker.computeBackoffMillis(
                        n, d.getBackoffBaseMs(), d.getBackoffCapMs())))
                .toList();
    }

    @Test
    @DisplayName("#587: a receiver that is down for two minutes, or a deploy's worth, still gets the event")
    void aReceiverDownForHoursIsStillRetried() throws Exception {
        WebhookProperties.Delivery d = shipped();
        Duration total = waits(d).stream().reduce(Duration.ZERO, Duration::plus);

        assertThat(total)
                .as("the whole retry window before an event is parked FAILED (was 127 s)")
                .isGreaterThanOrEqualTo(Duration.ofHours(23))
                .isLessThanOrEqualTo(Duration.ofHours(26));
        assertThat(waits(d).get(0))
                .as("the first retry is soon enough for a blip, but not a hot loop against a struggling receiver")
                .isBetween(Duration.ofSeconds(10), Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("#587: the declared cap is reachable, so it bounds the schedule rather than decorating it")
    void theCapIsReached() throws Exception {
        WebhookProperties.Delivery d = shipped();
        Duration longest = waits(d).stream().max(Duration::compareTo).orElseThrow();

        assertThat(longest).as("longest single wait").isEqualTo(Duration.ofMillis(d.getBackoffCapMs()));
        assertThat(longest).as("no single wait is longer than a working day").isLessThanOrEqualTo(Duration.ofHours(8));
    }

    @Test
    @DisplayName("#587: the field defaults in WebhookProperties agree with the yml defaults")
    void fieldDefaultsMatchTheYml() throws Exception {
        WebhookProperties.Delivery yml = shipped();
        WebhookProperties.Delivery field = new WebhookProperties().getDelivery();

        assertThat(field.getMaxAttempts()).isEqualTo(yml.getMaxAttempts());
        assertThat(field.getBackoffBaseMs()).isEqualTo(yml.getBackoffBaseMs());
        assertThat(field.getBackoffCapMs()).isEqualTo(yml.getBackoffCapMs());
    }
}
