package uk.jtoye.core.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-04 / #552 (D-01) — the Flyway credential is decoupled from the application's.
 *
 * <p>The runtime-role split points {@code spring.datasource.username} at the
 * DML-only {@code jtoye_runtime}. {@code spring.flyway.user} must NOT move with it
 * (Flyway needs {@code CREATE} on the schema), so {@code application.yml} indirects
 * it through a nested placeholder default:
 * {@code ${DB_MIGRATION_USER:${spring.datasource.username}}}. The whole change rests
 * on Spring resolving that nested default — RESEARCH assumption A3, which was
 * UNVERIFIED.
 *
 * <p>This proves it from the RESOLVED configuration (the fact), never from the YAML
 * text (the intent), in BOTH directions:
 * <ol>
 *   <li>unset -> {@code spring.flyway.user} resolves to
 *       {@code spring.datasource.username}, so an environment that adopted neither
 *       new key behaves exactly as before;</li>
 *   <li>set   -> {@code spring.flyway.user} resolves to {@code DB_MIGRATION_USER}'s
 *       value, and the datasource username is NOT dragged onto it.</li>
 * </ol>
 * A third arm pins that {@code spring.flyway.url} is still a live placeholder onto
 * the datasource url — its presence is the #517 fix (a dedicated non-pooling Flyway
 * DataSource that keeps the migration sentinel GUC out of the app pool); the break
 * arm proving that url is load-bearing lives in {@code FreshChainMigrationIntegrationTest}.
 *
 * <p>Reads {@code application.yml} exactly as Spring Boot loads it
 * ({@link StandardEnvironment} + {@link YamlPropertySourceLoader}), mirroring
 * {@code FreshChainMigrationIntegrationTest}'s own {@code environmentFor} helper, so
 * placeholder resolution runs through the same {@code PropertySourcesPropertyResolver}
 * the running app uses. Plain JUnit, no Testcontainers — this is a
 * property-resolution fact, not a database one.
 */
class FlywayCredentialDecouplingTest {

    @Test
    void unsetMigrationUserResolvesToTheDatasourceUsername() throws IOException {
        StandardEnvironment env = environmentWith(Map.of());   // neither new key set

        String flywayUser = env.getProperty("spring.flyway.user");
        String datasourceUser = env.getProperty("spring.datasource.username");

        assertThat(datasourceUser)
                .as("control: the datasource username resolves to a concrete value")
                .isNotBlank();
        assertThat(flywayUser)
                .as("A3 unset direction: the nested default resolves to the datasource username")
                .isEqualTo(datasourceUser);

        // Password resolves through the same nested default (empty string when
        // DB_PASSWORD is unset — the point is that Flyway and the datasource agree).
        assertThat(env.getProperty("spring.flyway.password"))
                .as("A3 unset direction (password): resolves to the datasource password")
                .isEqualTo(env.getProperty("spring.datasource.password"));
    }

    @Test
    void setMigrationUserOverridesTheFlywayCredentialOnly() throws IOException {
        String migrator = "jtoye_app_migrator_probe";
        String migratorPw = "migrator_pw_probe_value";
        StandardEnvironment env = environmentWith(Map.of(
                "DB_MIGRATION_USER", migrator,
                "DB_MIGRATION_PASSWORD", migratorPw));

        assertThat(env.getProperty("spring.flyway.user"))
                .as("A3 set direction: spring.flyway.user resolves to DB_MIGRATION_USER")
                .isEqualTo(migrator);
        assertThat(env.getProperty("spring.flyway.password"))
                .as("A3 set direction: spring.flyway.password resolves to DB_MIGRATION_PASSWORD")
                .isEqualTo(migratorPw);

        // The app credential is unmoved — the split's whole point: the app can go to
        // jtoye_runtime without dragging the migrator off jtoye_app.
        assertThat(env.getProperty("spring.datasource.username"))
                .as("the datasource username is NOT dragged onto the migrator value")
                .isNotEqualTo(migrator);
    }

    @Test
    void flywayUrlStaysAPlaceholderOntoTheDatasourceUrl() throws IOException {
        StandardEnvironment env = environmentWith(Map.of());
        assertThat(env.getProperty("spring.flyway.url"))
                .as("#517: spring.flyway.url must stay declared and resolve to the datasource url")
                .isNotBlank()
                .isEqualTo(env.getProperty("spring.datasource.url"));
    }

    private static StandardEnvironment environmentWith(Map<String, Object> overrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        if (!overrides.isEmpty()) {
            // addFirst so the injected keys outrank the real OS environment.
            env.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        }
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertThat(resource.exists()).as("application.yml must be on the classpath").isTrue();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml", resource)) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }
}
