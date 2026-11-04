package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sweep refuses to start on an empty, inverted or too-narrow alert window, naming the keys, and no value an
 * operator can type overflows the check (review of PR 1123).
 */
class UnacceptedOrderSweepConfigTest {

    private static final long MINUTE_MS = 60_000L;

    private static UnacceptedOrderSweep sweep(Duration threshold, Duration maxAge, long sweepIntervalMs) {
        return new UnacceptedOrderSweep(null, null, null, null, null, null, true, threshold, maxAge, sweepIntervalMs);
    }

    private static void assertRefused(Duration threshold, Duration maxAge, long sweepIntervalMs) {
        assertThatThrownBy(() -> sweep(threshold, maxAge, sweepIntervalMs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UnacceptedOrderSweep.MAX_AGE_PROPERTY)
                .hasMessageContaining(UnacceptedOrderSweep.THRESHOLD_PROPERTY)
                .hasMessageContaining(UnacceptedOrderSweep.SWEEP_INTERVAL_PROPERTY);
    }

    @Test
    @DisplayName("the shipped window (10m to 2h, one run a minute) starts, and so does one exactly three intervals wide")
    void validWindowStarts() {
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofHours(2), MINUTE_MS)).isNotNull();
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofMinutes(13), MINUTE_MS)).as("exactly 3 x 60 s").isNotNull();
    }

    @Test
    @DisplayName("review of PR 1123: a window narrower than three sweep intervals is refused, because an order can pass through it between two runs")
    void windowNarrowerThanTheSweepCadenceIsRefused() {
        // 10m threshold, 11m max age, runs about a minute apart: an order that turns 10 minutes old just after
        // a run is older than 11 minutes at the next one, and would never be alerted.
        assertRefused(Duration.ofMinutes(10), Duration.ofMinutes(11), MINUTE_MS);
        assertRefused(Duration.ofMinutes(10), Duration.ofSeconds(12 * 60 + 59), MINUTE_MS);
        // The same window is fine for a sweep that runs every 10 seconds, and not for one every 10 minutes.
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofMinutes(11), 10_000L)).isNotNull();
        assertRefused(Duration.ofMinutes(10), Duration.ofHours(2), 40 * MINUTE_MS);
    }

    @Test
    @DisplayName("max-age equal to or below the threshold, zero or negative, fails fast naming the keys")
    void invalidWindowFailsFast() {
        for (Duration bad : new Duration[] {Duration.ofMinutes(10), Duration.ofMinutes(9), Duration.ZERO, Duration.ofMinutes(-5)}) {
            assertRefused(Duration.ofMinutes(10), bad, MINUTE_MS);
        }
    }

    @Test
    @DisplayName("review of PR 1123: no value overflows the check; an enormous max age starts (clamped), an enormous interval is refused by name")
    void enormousValuesDoNotOverflow() {
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofSeconds(Long.MAX_VALUE), MINUTE_MS))
                .as("an effectively unbounded max age is legal").isNotNull();
        assertRefused(Duration.ofMinutes(10), Duration.ofHours(2), Long.MAX_VALUE);
        assertRefused(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE - 1), MINUTE_MS);
    }

    @Test
    @DisplayName("a non-positive sweep interval is refused naming its key")
    void nonPositiveIntervalIsRefused() {
        assertThatThrownBy(() -> sweep(Duration.ofMinutes(10), Duration.ofHours(2), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UnacceptedOrderSweep.SWEEP_INTERVAL_PROPERTY);
    }
}
