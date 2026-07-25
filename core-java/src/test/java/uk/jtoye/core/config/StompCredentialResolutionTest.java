package uk.jtoye.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution proof for the D-05 (Phase 26 / DEF-4) three-level STOMP credential chain in
 * {@code application.yml}:
 * {@code ${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}} and the passcode equivalent.
 *
 * <p><strong>Why this exists.</strong> DEF-4 was a two-sided env-name mismatch that no test could
 * see. {@code application.yml} read the single-level {@code ${RABBITMQ_USER:guest}} for the relay
 * credentials, while {@code k8s/base/core-java-deployment.yaml} injected an env named after the
 * secret KEY (never read by any {@code application*.yml}) plus {@code STOMP_CLIENT_LOGIN} /
 * {@code STOMP_CLIENT_PASSCODE} — which no {@code application*.yml} read either. The relay
 * therefore authenticated as the terminal default {@code guest} and RabbitMQ logged
 * {@code Access refused for user 'guest'} at boot. Compose never exercised the relay
 * ({@code STOMP_BROKER_MODE} defaults to {@code in-memory}), so the defect was invisible locally.
 *
 * <p><strong>Falsifiability.</strong> The four "dedicated credential wins" cases below are RED
 * against the pre-change single-level form: with both names supplied, a single-level
 * {@code ${RABBITMQ_USER:guest}} resolves to the RabbitMQ value, not the dedicated STOMP value.
 * The two fallback cases ({@code RABBITMQ_*} only) and the two terminal-default cases (nothing
 * supplied) are GREEN both before and after — that is the Incremental Betterment half: the chain
 * is purely ADDITIVE, so compose (which sets {@code RABBITMQ_USER}/{@code RABBITMQ_PASSWORD} and
 * no {@code STOMP_CLIENT_*}) resolves to exactly the values it did before.
 *
 * <p>The real {@code application.yml} is loaded via {@link ConfigDataApplicationContextInitializer}
 * rather than a synthetic property map, so the assertions bind to the shipped configuration and
 * fail if someone flattens the chain again. The system-environment and system-property sources are
 * stripped so an ambient {@code RABBITMQ_USER} on a developer machine or CI runner can neither
 * mask a regression nor invent one.
 *
 * <p>Deliberately an UNTAGGED plain unit test — it carries no JUnit tag annotation at all (in
 * particular not the {@code "testcontainers"} tag), so it runs in the fast {@code :core-java:test}
 * task rather than {@code integrationTest}. It needs no Postgres, no broker and no cluster: only
 * the shipped YAML and Spring's placeholder resolver.
 */
class StompCredentialResolutionTest {

    private static final String CLIENT_LOGIN = "stomp.broker.client-login";
    private static final String SYSTEM_LOGIN = "stomp.broker.system-login";
    private static final String CLIENT_PASSCODE = "stomp.broker.client-passcode";
    private static final String SYSTEM_PASSCODE = "stomp.broker.system-passcode";

    /**
     * A runner over the REAL application.yml with a deterministic environment.
     *
     * @param envShapedProperties env-variable-shaped names (Spring's relaxed binding resolves a
     *                            {@code ${STOMP_CLIENT_LOGIN}} placeholder from any PropertySource)
     */
    private ApplicationContextRunner runner(String... envShapedProperties) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(context -> {
                    MutablePropertySources sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withPropertyValues(envShapedProperties);
    }

    // ---------------------------------------------------------------------
    // Level 1 — the dedicated STOMP credential wins (RED before the change)
    // ---------------------------------------------------------------------

    @Test
    void clientLoginPrefersTheDedicatedStompCredential() {
        runner("STOMP_CLIENT_LOGIN=stomp-relay-user", "RABBITMQ_USER=amqp-pool-user")
                .run(context -> assertThat(context.getEnvironment().getProperty(CLIENT_LOGIN))
                        .isEqualTo("stomp-relay-user"));
    }

    @Test
    void systemLoginPrefersTheDedicatedStompCredential() {
        runner("STOMP_CLIENT_LOGIN=stomp-relay-user", "RABBITMQ_USER=amqp-pool-user")
                .run(context -> assertThat(context.getEnvironment().getProperty(SYSTEM_LOGIN))
                        .isEqualTo("stomp-relay-user"));
    }

    @Test
    void clientPasscodePrefersTheDedicatedStompPasscode() {
        runner("STOMP_CLIENT_PASSCODE=stomp-relay-secret", "RABBITMQ_PASSWORD=amqp-pool-secret")
                .run(context -> assertThat(context.getEnvironment().getProperty(CLIENT_PASSCODE))
                        .isEqualTo("stomp-relay-secret"));
    }

    @Test
    void systemPasscodePrefersTheDedicatedStompPasscode() {
        runner("STOMP_CLIENT_PASSCODE=stomp-relay-secret", "RABBITMQ_PASSWORD=amqp-pool-secret")
                .run(context -> assertThat(context.getEnvironment().getProperty(SYSTEM_PASSCODE))
                        .isEqualTo("stomp-relay-secret"));
    }

    // ---------------------------------------------------------------------
    // Level 2 — fall back to the RabbitMQ credential (compose behaviour, unchanged)
    // ---------------------------------------------------------------------

    @Test
    void loginsFallBackToTheRabbitMqUserWhenNoStompCredentialIsSupplied() {
        runner("RABBITMQ_USER=amqp-pool-user").run(context -> {
            assertThat(context.getEnvironment().getProperty(CLIENT_LOGIN)).isEqualTo("amqp-pool-user");
            assertThat(context.getEnvironment().getProperty(SYSTEM_LOGIN)).isEqualTo("amqp-pool-user");
        });
    }

    @Test
    void passcodesFallBackToTheRabbitMqPasswordWhenNoStompPasscodeIsSupplied() {
        runner("RABBITMQ_PASSWORD=amqp-pool-secret").run(context -> {
            assertThat(context.getEnvironment().getProperty(CLIENT_PASSCODE)).isEqualTo("amqp-pool-secret");
            assertThat(context.getEnvironment().getProperty(SYSTEM_PASSCODE)).isEqualTo("amqp-pool-secret");
        });
    }

    // ---------------------------------------------------------------------
    // Level 3 — `guest` is reached ONLY when nothing at all is supplied
    // ---------------------------------------------------------------------

    @Test
    void loginsReachGuestOnlyWhenNeitherCredentialIsSupplied() {
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty(CLIENT_LOGIN)).isEqualTo("guest");
            assertThat(context.getEnvironment().getProperty(SYSTEM_LOGIN)).isEqualTo("guest");
        });
    }

    @Test
    void passcodesReachGuestOnlyWhenNeitherCredentialIsSupplied() {
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty(CLIENT_PASSCODE)).isEqualTo("guest");
            assertThat(context.getEnvironment().getProperty(SYSTEM_PASSCODE)).isEqualTo("guest");
        });
    }
}
