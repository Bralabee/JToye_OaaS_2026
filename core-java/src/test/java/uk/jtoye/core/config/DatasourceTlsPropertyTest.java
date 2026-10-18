package uk.jtoye.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 29 plan 29-08 (Blocker E, T-29-27): the datasource URL carries a TLS mode the
 * environment chooses, and a deployed target can make the driver VERIFY the server.
 *
 * <p>The managed Azure Database for PostgreSQL Flexible Server requires TLS, and ASVS V9 wants
 * the certificate verified, not merely used. Before this change the shipped URL was
 * {@code jdbc:postgresql://host:port/db} with no TLS parameter at all: pgjdbc's defaults
 * ({@code sslmode=prefer}, {@code LibPQFactory}) encrypt opportunistically and verify nothing,
 * so a cluster could reach the managed server with no check on who answered. The k8s render
 * now supplies {@code DB_SSL_MODE=verify-full} and
 * {@code DB_SSL_FACTORY=org.postgresql.ssl.DefaultJavaSSLFactory}, which validates against the
 * JVM default truststore (JDK cacerts plus the cluster CA, plan 29-22), while local compose
 * keeps {@code prefer} because its Postgres serves no TLS.
 *
 * <p>Shape copied from {@code MailStartTlsPropertyTest}: the SHIPPED {@code application.yml}
 * is read from the classpath into a {@link StandardEnvironment} whose OS environment and JVM
 * properties are removed (an exported {@code DB_SSL_MODE} must not replace the default under
 * test), env values are layered on top, and the RESOLVED property is asserted. The resolved
 * URL is then handed to pgjdbc's own {@link Driver#parseURL} so the assertion is on what the
 * driver will read, not on a string that merely contains the right words.
 */
class DatasourceTlsPropertyTest {

    private static final String LIBPQ_FACTORY = "org.postgresql.ssl.LibPQFactory";
    private static final String JAVA_FACTORY = "org.postgresql.ssl.DefaultJavaSSLFactory";

    @Test
    @DisplayName("29-08 — with no DB_SSL_* set, the URL keeps pgjdbc's own defaults: sslmode=prefer, LibPQFactory")
    void defaultUrlKeepsPreferAndLibPq() throws Exception {
        StandardEnvironment env = environmentWith(Map.of());

        String url = env.getProperty("spring.datasource.url");
        assertThat(url)
                .as("the shipped default must spell the behaviour local compose already has")
                .endsWith("?sslmode=prefer&sslfactory=" + LIBPQ_FACTORY);

        Properties parsed = Driver.parseURL(url, null);
        assertThat(parsed).as("pgjdbc must accept the resolved URL").isNotNull();
        assertThat(parsed.getProperty("sslmode")).isEqualTo("prefer");
        assertThat(parsed.getProperty("sslfactory")).isEqualTo(LIBPQ_FACTORY);
        assertThat(Class.forName(parsed.getProperty("sslfactory")))
                .as("the default factory must name a class pgjdbc ships")
                .isNotNull();

        assertThat(env.getProperty("spring.flyway.url"))
                .as("Flyway migrates over the SAME URL, TLS parameters included (#517 placeholder)")
                .isEqualTo(url);
    }

    @Test
    @DisplayName("29-08 — DB_SSL_MODE=verify-full + DefaultJavaSSLFactory reach the driver on both datasource and Flyway")
    void deployedTargetsVerifyTheServer() throws Exception {
        StandardEnvironment env = environmentWith(Map.of(
                "DB_HOST", "jtoye-stg-pg.postgres.database.azure.com",
                "DB_SSL_MODE", "verify-full",
                "DB_SSL_FACTORY", JAVA_FACTORY));

        String url = env.getProperty("spring.datasource.url");
        Properties parsed = Driver.parseURL(url, null);
        assertThat(parsed).as("pgjdbc must accept the resolved URL: %s", url).isNotNull();
        assertThat(parsed.getProperty("sslmode"))
                .as("verify-full: the certificate chain AND the host name are checked")
                .isEqualTo("verify-full");
        assertThat(parsed.getProperty("sslfactory"))
                .as("the JVM truststore validates the managed server, not ~/.postgresql/root.crt")
                .isEqualTo(JAVA_FACTORY);
        assertThat(Class.forName(parsed.getProperty("sslfactory")))
                .as("a misspelt factory would fail only at the first connection; it must be a real class")
                .isNotNull();
        assertThat(parsed.getProperty("PGHOST")).isEqualTo("jtoye-stg-pg.postgres.database.azure.com");

        assertThat(env.getProperty("spring.flyway.url"))
                .as("Flyway must verify the same server the pool verifies")
                .isEqualTo(url);
    }

    @Test
    @DisplayName("29-08 — spring.flyway.user is DB_MIGRATION_USER when set, the datasource username otherwise")
    void flywayUserFollowsTheMigratorSplit() throws Exception {
        StandardEnvironment unset = environmentWith(Map.of("DB_USER", "jtoye_runtime"));
        assertThat(unset.getProperty("spring.flyway.user"))
                .as("unset: the existing fallback onto the datasource username is preserved")
                .isEqualTo("jtoye_runtime");

        StandardEnvironment split = environmentWith(Map.of(
                "DB_USER", "jtoye_runtime",
                "DB_MIGRATION_USER", "jtoye_app",
                "DB_MIGRATION_PASSWORD", "migrator-probe"));
        assertThat(split.getProperty("spring.flyway.user"))
                .as("set: Flyway migrates as the owner role")
                .isEqualTo("jtoye_app");
        assertThat(split.getProperty("spring.flyway.password")).isEqualTo("migrator-probe");
        assertThat(split.getProperty("spring.datasource.username"))
                .as("the runtime role is not dragged onto the migrator")
                .isEqualTo("jtoye_runtime");
    }

    private static StandardEnvironment environmentWith(Map<String, Object> envValues) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        if (!envValues.isEmpty()) {
            sources.addFirst(new MapPropertySource("test-env", envValues));
        }
        ClassPathResource resource = new ClassPathResource("application.yml");
        assertThat(resource.exists()).as("application.yml must be on the classpath").isTrue();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("shipped-application.yml", resource)) {
            sources.addLast(source);
        }
        return env;
    }
}
