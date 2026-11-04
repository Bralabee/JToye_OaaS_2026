package uk.jtoye.core.order;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import uk.jtoye.core.notification.EmailNotificationService;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sweep refuses to start on an empty, inverted or too-narrow alert window, naming the keys, and no value an
 * operator can type overflows or slips past the check (review of PRs 1123 and 1125). The check runs on the values
 * the query will use (both bounds clamped to one ceiling), and the sweep interval is read the way
 * {@code @Scheduled(fixedDelayString)} reads it.
 */
class UnacceptedOrderSweepConfigTest {

    private static final String MINUTE = "60000";

    private static UnacceptedOrderSweep sweep(Duration threshold, Duration maxAge, String sweepInterval) {
        return new UnacceptedOrderSweep(null, null, null, null, null, null, true, threshold, maxAge, sweepInterval);
    }

    private static void assertRefused(Duration threshold, Duration maxAge, String sweepInterval) {
        assertThatThrownBy(() -> sweep(threshold, maxAge, sweepInterval))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UnacceptedOrderSweep.MAX_AGE_PROPERTY)
                .hasMessageContaining(UnacceptedOrderSweep.THRESHOLD_PROPERTY)
                .hasMessageContaining(UnacceptedOrderSweep.SWEEP_INTERVAL_PROPERTY);
    }

    @Test
    @DisplayName("the shipped window (10m to 2h, one run a minute) starts, and so does one exactly three intervals wide")
    void validWindowStarts() {
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofHours(2), MINUTE)).isNotNull();
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofMinutes(13), MINUTE)).as("exactly 3 x 60 s").isNotNull();
    }

    @Test
    @DisplayName("review of PR 1123: a window narrower than three sweep intervals is refused, because an order can pass through it between two runs")
    void windowNarrowerThanTheSweepCadenceIsRefused() {
        assertRefused(Duration.ofMinutes(10), Duration.ofMinutes(11), MINUTE);
        assertRefused(Duration.ofMinutes(10), Duration.ofSeconds(12 * 60 + 59), MINUTE);
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofMinutes(11), "10000")).isNotNull();
        assertRefused(Duration.ofMinutes(10), Duration.ofHours(2), String.valueOf(40 * 60_000L));
    }

    @Test
    @DisplayName("max-age equal to or below the threshold, zero or negative, fails fast naming the keys")
    void invalidWindowFailsFast() {
        for (Duration bad : new Duration[] {Duration.ofMinutes(10), Duration.ofMinutes(9), Duration.ZERO, Duration.ofMinutes(-5)}) {
            assertRefused(Duration.ofMinutes(10), bad, MINUTE);
        }
    }

    @Test
    @DisplayName("review of PR 1125: the window is checked on the CLAMPED values: two bounds beyond the ceiling are an empty window, not a pass")
    void windowIsCheckedAfterClamping() {
        // 4000d and 5000d both clamp to the 10-year ceiling: the query would run an EMPTY window, so it is refused.
        assertRefused(Duration.ofDays(4000), Duration.ofDays(5000), MINUTE);
        // A huge threshold (past make_interval's range) beside a normal max age is simply an inverted window.
        assertRefused(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofHours(2), MINUTE);
        // An enormous max age with a sane threshold is legal: effectively unbounded, clamped.
        assertThat(sweep(Duration.ofMinutes(10), Duration.ofSeconds(Long.MAX_VALUE), MINUTE)).isNotNull();
        // 4000d threshold clamps to the ceiling, which the (also clamped) max age then equals: refused.
        assertRefused(Duration.ofDays(4000), Duration.ofSeconds(Long.MAX_VALUE), MINUTE);
    }

    @Test
    @DisplayName("no interval value overflows the check; a non-positive or unparseable one is refused naming its key")
    void intervalValuesAreChecked() {
        assertRefused(Duration.ofMinutes(10), Duration.ofHours(2), String.valueOf(Long.MAX_VALUE));
        for (String bad : new String[] {"0", "-5", "soon", ""}) {
            assertThatThrownBy(() -> sweep(Duration.ofMinutes(10), Duration.ofHours(2), bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(UnacceptedOrderSweep.SWEEP_INTERVAL_PROPERTY);
        }
    }

    @Test
    @DisplayName("review of PR 1125: the interval binds from the property the way @Scheduled reads it: PT1M, 1m and 60000 all start")
    void intervalBindsLikeTheScheduler() {
        for (String value : new String[] {"PT1M", "60000", "1m"}) {
            try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
                ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                        UnacceptedOrderSweep.SWEEP_INTERVAL_PROPERTY, value)));
                // What Spring Boot gives the real context: ${...} placeholders and String -> Duration conversion.
                ctx.registerBean("placeholders", org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class);
                ctx.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
                ctx.registerBean(OrderRepository.class, () -> Mockito.mock(OrderRepository.class));
                ctx.registerBean(EntityManager.class, () -> Mockito.mock(EntityManager.class));
                ctx.registerBean(PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class));
                ctx.registerBean(OrderEmailContextFactory.class, () -> Mockito.mock(OrderEmailContextFactory.class));
                ctx.registerBean(EmailNotificationService.class, () -> Mockito.mock(EmailNotificationService.class));
                ctx.register(UnacceptedOrderSweep.class);
                ctx.refresh();
                assertThat(ctx.getBean(UnacceptedOrderSweep.class)).as("interval " + value).isNotNull();
            }
        }
    }
}
