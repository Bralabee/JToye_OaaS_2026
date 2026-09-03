package uk.jtoye.core.security.access;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.shop.ShopRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * QA-council 20260902 SEC-2 — the shop-scoping posture is LOGGED at startup, at WARN when
 * strict-scoping is OFF, so a deployment's own logs state which authorization rule it booted
 * with instead of leaving OFF-by-choice indistinguishable from OFF-by-omission.
 *
 * <p>Pure unit test: the service is constructed with inert collaborators (the posture line
 * reads only the bound flag and touches nothing else), the flag is set by reflection exactly as
 * {@code StaffManagementIntegrationTest} / {@code ShopAccessEnforcementIntegrationTest} set it,
 * and the emission is captured with the {@code ListAppender} pattern from
 * {@code RabbitListenerContainerFactoryTest}. Both directions are asserted — WARN present when
 * OFF, ABSENT when ON — because a test that only looked for the WARN could pass against an
 * implementation that warns unconditionally, which would train operators to ignore it.
 */
class ShopAccessServiceScopingPostureTest {

    private static final String POSTURE_EVENT = "event=shop_scoping_posture";

    private ShopAccessService service;
    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ShopAccessService(
                mock(ShopStaffRepository.class),
                mock(UserDirectoryRepository.class),
                mock(TenantCacheEvictor.class),
                mock(ShopRepository.class),
                (ObjectProvider<ShopAccessService>) mock(ObjectProvider.class));
        serviceLogger = (Logger) LoggerFactory.getLogger(ShopAccessService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    void strictScopingOff_emitsAWarnNamingThePostureAndItsBlockers() {
        ReflectionTestUtils.setField(service, "strictScoping", false);

        service.logScopingPosture();

        assertThat(appender.list)
                .as("OFF must be stated at WARN — it is an authorization posture, not a config echo")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(POSTURE_EVENT)
                            .contains("strict=false")
                            .contains("NOT enforced")
                            .contains("implicit tenant-wide GROUP_ADMIN")
                            .contains("jtoye.access.strict-scoping=false")
                            // the flip's blockers travel with the warning so the reader knows
                            // it is tracked, not forgotten
                            .contains("#285")
                            .contains("/sync/batch")
                            .contains("integration-orders-rw");
                });
        assertThat(appender.list)
                .as("no misleading ENFORCED line when the posture is OFF")
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("strict=true"));
    }

    @Test
    void strictScopingOn_emitsNoWarn_andStatesEnforcedAtInfo() {
        ReflectionTestUtils.setField(service, "strictScoping", true);

        service.logScopingPosture();

        assertThat(appender.list)
                .as("ON must NOT produce the WARN — an unconditional warning is noise operators learn to ignore")
                .noneSatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(POSTURE_EVENT);
                });
        assertThat(appender.list)
                .as("ON is still stated, at INFO, so the posture is always in the startup log")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.INFO);
                    assertThat(event.getFormattedMessage())
                            .contains(POSTURE_EVENT)
                            .contains("strict=true")
                            .contains("ENFORCED");
                });
    }
}
