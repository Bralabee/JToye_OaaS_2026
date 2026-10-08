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
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.testsupport.StrictScopingGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The shop-scoping posture is LOGGED at startup (QA-council 20260902 SEC-2), and since D-06
 * (Phase 37-04) the default is strict scoping ON.
 *
 * <p>Two kinds of assertion:
 * <ul>
 *   <li><strong>The booted default.</strong> A context is booted with the REAL
 *       {@code application.yml} (via {@link ConfigDataApplicationContextInitializer}) and the
 *       {@code ShopAccessService} bean is read back from it. With {@code ACCESS_STRICT_SCOPING}
 *       unset it must hold {@code true}, and the {@code @PostConstruct} posture line it emitted
 *       while booting must be the ON INFO line — the value is observed through the bean's own
 *       startup behaviour, not set by reflection.</li>
 *   <li><strong>Each log branch.</strong> A fresh instance with inert collaborators has the flag
 *       set through {@link StrictScopingGuard} and the line captured with a {@code ListAppender}.
 *       Both directions are asserted: OFF is a WARN naming the explicit override and the owner
 *       ruling, and ON emits no WARN at all — an unconditional warning would train operators to
 *       ignore it.</li>
 * </ul>
 */
class ShopAccessServiceScopingPostureTest {

    private static final String POSTURE_EVENT = "event=shop_scoping_posture";

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(ShopAccessService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    void bootedBean_readsTheD06Default_andLoggedTheOnPostureWhileBooting() {
        // The default under test is application.yml's; an exported value is honoured so a run with
        // ACCESS_STRICT_SCOPING=true stays green, and an exported false is reported, never hidden.
        String declared = System.getenv("ACCESS_STRICT_SCOPING");
        boolean expected = declared == null || Boolean.parseBoolean(declared);

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // Boot's conversion service, as every application bean factory has: the bean also binds a
                // Duration and a Set<String> from application.yml.
                .withInitializer(ctx -> ctx.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withBean(ShopStaffRepository.class, () -> mock(ShopStaffRepository.class))
                .withBean(UserDirectoryRepository.class, () -> mock(UserDirectoryRepository.class))
                .withBean(TenantCacheEvictor.class, () -> mock(TenantCacheEvictor.class))
                .withBean(ShopRepository.class, () -> mock(ShopRepository.class))
                .withBean(ShopAccessService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShopAccessService booted = context.getBean(ShopAccessService.class);
                    assertThat(StrictScopingGuard.current(booted))
                            .as("ShopAccessService.strictScoping booted from application.yml (ACCESS_STRICT_SCOPING=%s)",
                                    declared)
                            .isEqualTo(expected);
                    assertThat(context.getEnvironment().getProperty("jtoye.access.strict-scoping", Boolean.class))
                            .as("resolved jtoye.access.strict-scoping")
                            .isEqualTo(expected);
                });

        Level expectedLevel = expected ? Level.INFO : Level.WARN;
        String expectedMarker = "strict=" + expected;
        assertThat(appender.list)
                .as("the bean's own @PostConstruct posture line, emitted while the context booted")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(expectedLevel);
                    assertThat(event.getFormattedMessage()).contains(POSTURE_EVENT).contains(expectedMarker);
                });
    }

    @Test
    void strictScopingOff_emitsAWarnNamingTheExplicitOverrideAndTheOwnerRuling() {
        ShopAccessService service = freshService();
        StrictScopingGuard.set(service, false);   // a fresh unit-test instance: nothing to restore

        service.logScopingPosture();

        assertThat(appender.list)
                .as("OFF must be stated at WARN — it is an authorization posture, not a config echo")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage())
                            .contains(POSTURE_EVENT)
                            .contains("strict=false")
                            .contains("access: strict scoping OFF by explicit override (ACCESS_STRICT_SCOPING=false)")
                            .contains("ungranted users are implicit tenant-wide GROUP_ADMIN")
                            .contains("reverting D-06 needs an owner ruling");
                });
        assertThat(appender.list)
                .as("no misleading ON line when the posture is OFF")
                .noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("strict=true"));
    }

    @Test
    void strictScopingOn_emitsNoWarn_andStatesTheDefaultAtInfo() {
        ShopAccessService service = freshService();
        StrictScopingGuard.set(service, true);

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
                            .contains("access: strict scoping ON (default, D-06)");
                });
    }

    @SuppressWarnings("unchecked")
    private static ShopAccessService freshService() {
        return new ShopAccessService(
                mock(ShopStaffRepository.class),
                mock(UserDirectoryRepository.class),
                mock(TenantCacheEvictor.class),
                mock(ShopRepository.class),
                (ObjectProvider<ShopAccessService>) mock(ObjectProvider.class));
    }
}
