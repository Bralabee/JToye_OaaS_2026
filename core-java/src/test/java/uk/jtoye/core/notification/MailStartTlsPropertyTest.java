package uk.jtoye.core.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-29-6 — STARTTLS must actually reach JavaMail.
 *
 * <p>{@code spring.mail.properties.*} is handed to JavaMail verbatim, so the key has to be
 * JavaMail's own spelling, {@code mail.smtp.starttls.enable}. {@code application.yml} spelled it
 * {@code starttls.enabled}: relaxed binding accepted the unknown key, JavaMail never read it, and
 * every real SMTP connection (SES, Gmail) went out without STARTTLS while {@code SMTP_STARTTLS}
 * looked configurable. Nothing failed — that is the whole defect.
 *
 * <p>This test runs the SHIPPED {@code application.yml} (read from the classpath, as the jar does)
 * through Boot's real {@link MailSenderAutoConfiguration} and asserts on the properties the built
 * {@link JavaMailSenderImpl} will hand to the {@code jakarta.mail.Session}, not on the YAML text.
 *
 * <p>D-20 (Phase 29, owner ruling 2026-10-18) — STARTTLS is REQUIRED in staging and production.
 * {@code application-prod.yml} (the profile every k8s environment runs) and
 * {@code application-staging.yml} carry {@code mail.smtp.starttls.enable} and
 * {@code mail.smtp.starttls.required} as LITERALS, so neither a missing ConfigMap entry nor
 * {@code SMTP_STARTTLS=false} can fall back to opportunistic TLS. The default and {@code dev}
 * profiles stay opportunistic for compose and the nightly. The profile cases layer the profile
 * file ABOVE the shipped {@code application.yml}, the order Boot gives them.
 */
class MailStartTlsPropertyTest {

    private static final String JAVAMAIL_STARTTLS = "mail.smtp.starttls.enable";
    private static final String JAVAMAIL_STARTTLS_REQUIRED = "mail.smtp.starttls.required";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withInitializer(ctx -> {
                MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
                // The runner's StandardEnvironment includes the caller's OS environment and JVM
                // properties, so an exported SMTP_STARTTLS would replace the ":true" default
                // under test. Drop both so the shipped default is what gets asserted.
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                shippedApplicationYml().forEach(sources::addLast);
            });

    @Test
    @DisplayName("DEF-29-6 — application.yml turns STARTTLS on under JavaMail's own key by default")
    void startTlsReachesJavaMailByDefault() {
        runner.run(ctx -> {
            Properties props = ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(props.getProperty(JAVAMAIL_STARTTLS))
                    .as("JavaMail reads %s; any other spelling is silently ignored", JAVAMAIL_STARTTLS)
                    .isEqualTo("true");
            assertThat(props.stringPropertyNames())
                    .as("no misspelt STARTTLS key may ship beside the real one")
                    .doesNotContain("mail.smtp.starttls.enabled");
        });
    }

    @Test
    @DisplayName("DEF-29-6 — SMTP_STARTTLS=false (local Mailhog) reaches the same key")
    void smtpStartTlsEnvTurnsItOff() {
        runner.withPropertyValues("SMTP_STARTTLS=false").run(ctx ->
                assertThat(ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties()
                        .getProperty(JAVAMAIL_STARTTLS)).isEqualTo("false"));
    }

    @Test
    @DisplayName("D-20 — prod profile (every k8s environment) requires STARTTLS")
    void prodProfileRequiresStartTls() {
        assertStartTlsRequired(profileRunner("prod"));
    }

    @Test
    @DisplayName("D-20 — prod profile: SMTP_STARTTLS=false cannot turn STARTTLS off")
    void prodProfileIgnoresSmtpStartTlsFalse() {
        assertStartTlsRequired(profileRunner("prod").withPropertyValues("SMTP_STARTTLS=false"));
    }

    @Test
    @DisplayName("D-20 — staging profile requires STARTTLS")
    void stagingProfileRequiresStartTls() {
        assertStartTlsRequired(profileRunner("staging"));
    }

    @Test
    @DisplayName("D-20 — staging profile: SMTP_STARTTLS=false cannot turn STARTTLS off")
    void stagingProfileIgnoresSmtpStartTlsFalse() {
        assertStartTlsRequired(profileRunner("staging").withPropertyValues("SMTP_STARTTLS=false"));
    }

    @Test
    @DisplayName("D-20 — no profile: STARTTLS stays opportunistic (enabled, not required)")
    void defaultProfileLeavesStartTlsOpportunistic() {
        assertStartTlsOpportunistic(runner);
    }

    @Test
    @DisplayName("D-20 — dev profile (compose, nightly): STARTTLS stays opportunistic")
    void devProfileLeavesStartTlsOpportunistic() {
        assertStartTlsOpportunistic(profileRunner("dev"));
    }

    private static void assertStartTlsRequired(ApplicationContextRunner profiled) {
        profiled.run(ctx -> {
            Properties props = ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(props.getProperty(JAVAMAIL_STARTTLS))
                    .as("%s under a deployed profile", JAVAMAIL_STARTTLS)
                    .isEqualTo("true");
            assertThat(props.getProperty(JAVAMAIL_STARTTLS_REQUIRED))
                    .as("%s under a deployed profile — a server without STARTTLS must fail the send",
                            JAVAMAIL_STARTTLS_REQUIRED)
                    .isEqualTo("true");
        });
    }

    private static void assertStartTlsOpportunistic(ApplicationContextRunner profiled) {
        profiled.run(ctx -> {
            Properties props = ctx.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(props.getProperty(JAVAMAIL_STARTTLS)).isEqualTo("true");
            assertThat(props.getProperty(JAVAMAIL_STARTTLS_REQUIRED))
                    .as("%s must stay unset outside staging/prod (Mailhog offers no STARTTLS)",
                            JAVAMAIL_STARTTLS_REQUIRED)
                    .isNull();
        });
    }

    /**
     * The shipped {@code application-<profile>.yml} layered ABOVE the shipped
     * {@code application.yml}, as Boot orders a profile-specific file over the base one. Both
     * go in below any {@code withPropertyValues} source, where an environment variable sits.
     */
    private static ApplicationContextRunner profileRunner(String profile) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withInitializer(ctx -> {
                    MutablePropertySources sources = ctx.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    shippedProfileYml(profile).forEach(sources::addLast);
                    shippedApplicationYml().forEach(sources::addLast);
                });
    }

    private static List<PropertySource<?>> shippedApplicationYml() {
        return shippedYml("application.yml");
    }

    private static List<PropertySource<?>> shippedProfileYml(String profile) {
        return shippedYml("application-" + profile + ".yml");
    }

    private static List<PropertySource<?>> shippedYml(String name) {
        ClassPathResource resource = new ClassPathResource(name);
        if (!resource.exists()) {
            throw new IllegalStateException("classpath " + name + " missing");
        }
        try {
            return new YamlPropertySourceLoader().load("shipped-" + name, resource);
        } catch (IOException e) {
            throw new IllegalStateException("classpath " + name + " unreadable", e);
        }
    }
}
