package uk.jtoye.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-council 20260902-134741 SEC-7 (adjudication A7): the {@code staging} profile must refuse to
 * start when {@code REDIS_PASSWORD} is not supplied, exactly as {@code prod} already does.
 *
 * <p>Staging was the one DEPLOYED profile that failed open: {@code application-staging.yml} read
 * {@code ${REDIS_PASSWORD:}}, so a missing k8s secret produced an unauthenticated Redis client with
 * no warning. The fix is staging-only. Per A7 the base/test profiles deliberately keep the empty
 * default ({@code src/test/resources/application-test.yml} excludes {@code RedisAutoConfiguration};
 * the only binder of the property is {@code RateLimitConfig}, which is off under test), and this
 * class asserts that boundary rather than leaving it as a comment.
 *
 * <p><b>Placeholder shape, measured not assumed.</b> Compose's fail-closed idiom
 * {@code ${VAR:?message}} is NOT Spring's: Spring's separator is the first {@code :} and everything
 * after it is a default, so {@code ${REDIS_PASSWORD:?REDIS_PASSWORD must be set}} resolves to the
 * literal string {@code ?REDIS_PASSWORD must be set} and starts happily (measured on spring-core
 * 6.2.19 via {@code StandardEnvironment.resolvePlaceholders}). Spring's fail-closed shape is
 * {@code ${REDIS_PASSWORD}} with no default — {@code PlaceholderResolutionException} — which is what
 * {@code application-prod.yml} already uses.
 *
 * <p>Mirrors the {@code StagingActuatorPortIsolationTest} idea (assert the running context, not the
 * YAML text) without its Testcontainers cost: an {@link ApplicationContextRunner} loads the real
 * config data for the profile, registers the same strict {@code PropertySourcesPlaceholderConfigurer}
 * Boot's {@code PropertyPlaceholderAutoConfiguration} gives the real context, and binds the property
 * through the same {@code @Value} expression {@code RateLimitConfig} uses, so an unresolvable
 * placeholder fails the context the way it fails the real one. The host's own environment is removed from the context so a developer shell that
 * happens to export {@code REDIS_PASSWORD} cannot turn the fail arm into a false pass.
 */
class StagingRedisPasswordFailClosedTest {

    private static final String MARKER = "supplied-by-the-secret";

    /**
     * The binder under test. The expression is byte-identical to {@code RateLimitConfig#redisPassword}
     * and {@link #theBinderMirrorsRateLimitConfigExactly} pins that equality, so this class cannot
     * drift into testing an expression production does not use.
     */
    @Configuration
    static class RedisPasswordBinder {
        @Value("${spring.data.redis.password:}")
        String password;
    }

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                // Hermetic: drop the process environment before config data is applied.
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // Boot's STRICT placeholder resolver. Without it the runner falls back to the lenient
                // Environment.resolvePlaceholders, which leaves ${REDIS_PASSWORD} in place as a literal and
                // starts the context - measured: the fail arm passed VACUOUSLY until this line existed.
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(RedisPasswordBinder.class)
                .withPropertyValues(properties);
    }

    @Test
    void stagingRefusesToStartWhenRedisPasswordIsNotSupplied() {
        runner("spring.profiles.active=staging").run(ctx -> {
            assertThat(ctx).as("staging with REDIS_PASSWORD unset must FAIL to start (fail closed)").hasFailed();
            assertThat(ctx.getStartupFailure())
                    .rootCause()
                    .as("and it must fail for THIS reason, not some unrelated one")
                    .hasMessageContaining("REDIS_PASSWORD");
        });
    }

    @Test
    void stagingStartsAndBindsTheSuppliedRedisPassword() {
        runner("spring.profiles.active=staging")
                .withSystemProperties("REDIS_PASSWORD=" + MARKER)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(RedisPasswordBinder.class).password)
                            .as("the value reaches the binder unchanged — no literal '?...' default leaking through")
                            .isEqualTo(MARKER);
                });
    }

    /**
     * The A7 boundary: base (and therefore dev/local/test) keep {@code ${REDIS_PASSWORD:}}. Widening
     * the tightening to the base file would fail context startup for every Testcontainers class
     * that does NOT exclude Redis autoconfiguration and for the two real-Redis integration tests.
     */
    @Test
    void theBaseProfileKeepsTheEmptyDefaultByDesign() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RedisPasswordBinder.class).password).isEmpty();
        });
    }

    @Test
    void theBinderMirrorsRateLimitConfigExactly() throws NoSuchFieldException {
        String production = RateLimitConfig.class.getDeclaredField("redisPassword").getAnnotation(Value.class).value();
        String mirror = RedisPasswordBinder.class.getDeclaredField("password").getAnnotation(Value.class).value();

        assertThat(mirror)
                .as("if RateLimitConfig changes how it reads the password, this test must change with it")
                .isEqualTo(production);
    }
}
