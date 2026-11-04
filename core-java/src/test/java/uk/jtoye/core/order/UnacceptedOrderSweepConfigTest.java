package uk.jtoye.core.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sweep refuses to start on an empty or inverted alert window, naming the key. */
class UnacceptedOrderSweepConfigTest {

    private static UnacceptedOrderSweep sweep(Duration threshold, long maxAgeMinutes) {
        return new UnacceptedOrderSweep(null, null, null, null, null, null, true, threshold, maxAgeMinutes);
    }

    @Test
    @DisplayName("max-age above the threshold starts")
    void validWindowStarts() {
        assertThat(sweep(Duration.ofMinutes(10), 120)).isNotNull();
        assertThat(sweep(Duration.ofMinutes(10), 11)).isNotNull();
    }

    @Test
    @DisplayName("max-age equal to or below the threshold, or non-positive, fails fast naming the key")
    void invalidWindowFailsFast() {
        for (long bad : new long[] {10, 9, 0, -5}) {
            assertThatThrownBy(() -> sweep(Duration.ofMinutes(10), bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(UnacceptedOrderSweep.MAX_AGE_PROPERTY);
        }
    }
}
