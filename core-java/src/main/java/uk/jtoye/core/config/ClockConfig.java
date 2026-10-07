package uk.jtoye.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * The application's clock, in UK time.
 *
 * <p>#861 (D-17): a PPDS label's default production date is "today", and today for a UK food
 * business is the date in Europe/London, not the server's own zone (a container is usually UTC,
 * so for the first hour of every BST day the server's date is still yesterday). Injecting the
 * clock also lets a test fix "now" instead of depending on when it runs.
 *
 * <p>Consumers that need a date must still resolve it in {@link #UK_ZONE} themselves
 * ({@code LocalDate.now(clock.withZone(UK_ZONE))}), so a clock supplied in any other zone,
 * such as a fixed UTC test clock, cannot shift a UK date.
 */
@Configuration
public class ClockConfig {

    /** The zone every UK-facing date (labels, order records) is rendered in. */
    public static final ZoneId UK_ZONE = ZoneId.of("Europe/London");

    @Bean
    public Clock clock() {
        return Clock.system(UK_ZONE);
    }
}
