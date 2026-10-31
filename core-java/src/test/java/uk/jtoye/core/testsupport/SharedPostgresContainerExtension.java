package uk.jtoye.core.testsupport;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ModifierSupport;
import org.junit.platform.commons.support.ReflectionSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

/**
 * Per-JVM-fork shared PostgreSQL Testcontainers extension for {@code @Tag("testcontainers")}
 * integration tests.
 *
 * <p>Instead of booting 200 separate {@code postgres:18-alpine} Docker containers and running all
 * 88+ Flyway migrations 200 times, each Gradle test worker JVM starts a single
 * {@code postgres:18-alpine} container, applies the full Flyway migration chain once, and resets
 * cluster roles, triggers, constraints, and user tables in {@code <5ms} before and after each test
 * class while keeping {@code revinfo_seq} monotonic across the fork.
 *
 * <p>Classes that test custom Flyway migration targets, broken migration chains, pre-Flyway role
 * ownership ({@code ALTER DEFAULT PRIVILEGES}), or non-default Postgres images/databases are
 * automatically excluded and retain their own dedicated per-class container.
 */
public class SharedPostgresContainerExtension implements BeforeAllCallback, AfterAllCallback {

    private static final String EXPECTED_IMAGE = "postgres:18-alpine";
    private static final String EXPECTED_DB = "jtoye_test";
    private static final String EXPECTED_USER = "test";
    private static final String EXPECTED_PASS = "test";

    /**
     * Classes that perform partial/out-of-order manual Flyway migrations or pre-Flyway
     * {@code ALTER DEFAULT PRIVILEGES} role setup on {@code jtoye_test} and therefore must keep a
     * dedicated per-class container. (All other manual-migration and role-ownership classes
     * already declare a distinct database name such as {@code jtoye_v80} or {@code jtoye} and are
     * excluded automatically by the {@link #EXPECTED_DB} check.)
     */
    private static final Set<String> EXCLUDED_CLASSES = Set.of(
            "uk.jtoye.core.customer.CustomerOriginShopScopeIntegrationTest",
            "uk.jtoye.core.media.MediaRedriveControllerTest",
            "uk.jtoye.core.media.MediaSweepTenantScopeIntegrationTest"
    );

    private static PostgreSQLContainer<?> forkSingleton;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == null || testClass.getEnclosingClass() != null) {
            return;
        }
        if (!isTestcontainersTagged(testClass) || EXCLUDED_CLASSES.contains(testClass.getName())) {
            return;
        }
        // Prevent any @Tag("testcontainers") class that omits spring.jpa.hibernate.ddl-auto=none in
        // its @DynamicPropertySource from inheriting ddl-auto=create-drop from application-test.yml
        // and dropping/recreating tables (wiping Flyway RLS policies) on the shared container.
        System.setProperty("spring.jpa.hibernate.ddl-auto", "none");

        List<Field> fields = ReflectionSupport.findFields(
                testClass,
                f -> ModifierSupport.isStatic(f)
                        && !ModifierSupport.isFinal(f)
                        && PostgreSQLContainer.class.isAssignableFrom(f.getType())
                        && AnnotationSupport.isAnnotated(f, org.testcontainers.junit.jupiter.Container.class),
                HierarchyTraversalMode.TOP_DOWN);

        boolean usesPooledContainer = false;
        for (Field field : fields) {
            field.setAccessible(true);
            Object current = field.get(null);
            if (current instanceof PooledPostgreSQLContainer) {
                usesPooledContainer = true;
                continue;
            }
            if (!(current instanceof PostgreSQLContainer<?> original)) {
                continue;
            }
            if (original.isRunning() || !isStandardPostgresConfig(original)) {
                continue;
            }
            PooledPostgreSQLContainer pooled = new PooledPostgreSQLContainer();
            field.set(null, pooled);
            usesPooledContainer = true;
        }
        if (usesPooledContainer) {
            ensureSingletonStartedAndReset();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == null || testClass.getEnclosingClass() != null) {
            return;
        }
        if (!isTestcontainersTagged(testClass) || EXCLUDED_CLASSES.contains(testClass.getName())) {
            return;
        }
        if (AnnotationSupport.isAnnotated(testClass, SpringBootTest.class)) {
            try {
                ApplicationContext appCtx = SpringExtension.getApplicationContext(context);
                appCtx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)
                        .values()
                        .forEach(ScheduledAnnotationBeanPostProcessor::destroy);
                appCtx.getBeansOfType(HikariDataSource.class)
                        .values()
                        .forEach(ds -> {
                            if (ds.getHikariPoolMXBean() != null) {
                                ds.getHikariPoolMXBean().softEvictConnections();
                            }
                        });
            } catch (Exception ignored) {
                // Context may not have started if a test failed before context refresh
            }
        }
    }

    private static boolean isTestcontainersTagged(Class<?> testClass) {
        return AnnotationSupport.findRepeatableAnnotations(testClass, Tag.class).stream()
                .anyMatch(t -> "testcontainers".equals(t.value()));
    }

    private static boolean isStandardPostgresConfig(PostgreSQLContainer<?> container) {
        try {
            return EXPECTED_IMAGE.equals(container.getDockerImageName())
                    && EXPECTED_DB.equals(container.getDatabaseName())
                    && EXPECTED_USER.equals(container.getUsername())
                    && EXPECTED_PASS.equals(container.getPassword());
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized PostgreSQLContainer<?> ensureSingletonStarted() {
        if (forkSingleton == null) {
            PostgreSQLContainer<?> c = new PostgreSQLContainer<>(DockerImageName.parse(EXPECTED_IMAGE))
                    .withDatabaseName(EXPECTED_DB)
                    .withUsername(EXPECTED_USER)
                    .withPassword(EXPECTED_PASS)
                    .withCommand(
                            "postgres",
                            "-c", "max_connections=300",
                            "-c", "fsync=off",
                            "-c", "synchronous_commit=off",
                            "-c", "full_page_writes=off");
            c.start();
            IntegrationTestSupport.ensureDowngradableTestRole(c);
            Flyway.configure()
                    .dataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword())
                    .locations("classpath:db/migration")
                    .outOfOrder(true)
                    .validateOnMigrate(true)
                    .initSql("SET app.current_tenant_id = '00000000-0000-0000-0000-000000000000'")
                    .load()
                    .migrate();
            forkSingleton = c;
        }
        return forkSingleton;
    }

    static synchronized PostgreSQLContainer<?> ensureSingletonStartedAndReset() {
        PostgreSQLContainer<?> pg = ensureSingletonStarted();
        resetDatabaseAndRoles(pg);
        return pg;
    }

    static synchronized void resetIfStarted() {
        if (forkSingleton != null && forkSingleton.isRunning()) {
            resetDatabaseAndRoles(forkSingleton);
        }
    }

    private static void resetDatabaseAndRoles(PostgreSQLContainer<?> pg) {
        try (Connection conn = DriverManager.getConnection(
                     pg.getJdbcUrl(), "_jtoye_bootstrap_superuser", EXPECTED_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER ROLE \"test\" SUPERUSER CREATEDB CREATEROLE INHERIT NOBYPASSRLS LOGIN PASSWORD 'test'");
            stmt.execute("ALTER TABLE IF EXISTS public.products ENABLE TRIGGER ALL");
            stmt.execute("ALTER TABLE IF EXISTS public.shops ENABLE TRIGGER ALL");
            stmt.execute("ALTER TABLE IF EXISTS public.user_directory DROP CONSTRAINT IF EXISTS test_directory_failure");
            stmt.execute("""
                    DO $$
                    DECLARE
                        r RECORD;
                    BEGIN
                        FOR r IN
                            SELECT rolname
                            FROM pg_roles
                            WHERE rolname NOT LIKE 'pg_%'
                              AND rolname NOT IN ('test', '_jtoye_admin', '_jtoye_bootstrap_superuser')
                        LOOP
                            EXECUTE format('REASSIGN OWNED BY %I TO "test"', r.rolname);
                            EXECUTE format('DROP OWNED BY %I', r.rolname);
                            EXECUTE format('DROP ROLE %I', r.rolname);
                        END LOOP;
                    END $$;
                    """);
            stmt.execute("""
                    DO $$
                    DECLARE
                        r RECORD;
                        has_rows BOOLEAN;
                        truncate_list TEXT := '';
                    BEGIN
                        FOR r IN
                            SELECT tablename
                            FROM pg_tables
                            WHERE schemaname = 'public'
                              AND tablename <> 'flyway_schema_history'
                        LOOP
                            EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', r.tablename) INTO has_rows;
                            IF has_rows THEN
                                IF truncate_list <> '' THEN
                                    truncate_list := truncate_list || ', ';
                                END IF;
                                truncate_list := truncate_list || format('public.%I', r.tablename);
                            END IF;
                        END LOOP;
                        IF truncate_list <> '' THEN
                            EXECUTE 'TRUNCATE TABLE ' || truncate_list || ' CASCADE';
                        END IF;
                        INSERT INTO public.tenants (id, name, created_at) VALUES
                            ('00000000-0000-0000-0000-000000000001'::uuid, 'Tenant A', NOW()),
                            ('00000000-0000-0000-0000-000000000002'::uuid, 'Tenant B', NOW())
                        ON CONFLICT (id) DO NOTHING;
                    END $$;
                    """);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reset shared PostgreSQL test container state", e);
        }
    }

    /**
     * Lightweight {@link PostgreSQLContainer} adapter that delegates to the per-JVM-fork singleton
     * container while resetting database tables and cluster roles on {@link #start()} and
     * {@link #stop()}.
     */
    static final class PooledPostgreSQLContainer extends PostgreSQLContainer<PooledPostgreSQLContainer> {

        PooledPostgreSQLContainer() {
            super(DockerImageName.parse(EXPECTED_IMAGE));
            withDatabaseName(EXPECTED_DB);
            withUsername(EXPECTED_USER);
            withPassword(EXPECTED_PASS);
        }

        private PostgreSQLContainer<?> activeDelegate() {
            return forkSingleton != null ? forkSingleton : ensureSingletonStarted();
        }

        @Override
        public void start() {
            ensureSingletonStarted();
        }

        @Override
        public void stop() {
            resetIfStarted();
        }

        @Override
        public void close() {
            resetIfStarted();
        }

        @Override
        public boolean isRunning() {
            return forkSingleton != null && forkSingleton.isRunning();
        }

        @Override
        public boolean isCreated() {
            return forkSingleton != null && forkSingleton.isCreated();
        }

        @Override
        public String getContainerId() {
            return activeDelegate().getContainerId();
        }

        @Override
        public InspectContainerResponse getContainerInfo() {
            return activeDelegate().getContainerInfo();
        }

        @Override
        public String getHost() {
            return activeDelegate().getHost();
        }

        @Override
        public Integer getMappedPort(int originalPort) {
            return activeDelegate().getMappedPort(originalPort);
        }

        @Override
        public Integer getFirstMappedPort() {
            return activeDelegate().getFirstMappedPort();
        }

        @Override
        public String getJdbcUrl() {
            return activeDelegate().getJdbcUrl();
        }

        @Override
        public String getDatabaseName() {
            return EXPECTED_DB;
        }

        @Override
        public String getUsername() {
            return EXPECTED_USER;
        }

        @Override
        public String getPassword() {
            return EXPECTED_PASS;
        }

        @Override
        public Container.ExecResult execInContainer(String... command)
                throws UnsupportedOperationException, IOException, InterruptedException {
            return activeDelegate().execInContainer(command);
        }

        @Override
        public Container.ExecResult execInContainer(Charset outputCharset, String... command)
                throws UnsupportedOperationException, IOException, InterruptedException {
            return activeDelegate().execInContainer(outputCharset, command);
        }

        @Override
        public void copyFileToContainer(MountableFile mountableFile, String containerPath) {
            activeDelegate().copyFileToContainer(mountableFile, containerPath);
        }
    }
}
