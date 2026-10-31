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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import uk.jtoye.core.security.TenantContext;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-JVM-fork shared PostgreSQL Testcontainers extension for {@code @Tag("testcontainers")}
 * integration tests.
 *
 * <p>Instead of booting 200 separate {@code postgres:18-alpine} Docker containers and running all
 * 88+ Flyway migrations 200 times, each Gradle test worker JVM starts a single
 * {@code postgres:18-alpine} container, applies the full Flyway migration chain once, and resets
 * cluster roles, triggers, constraints, and user tables in {@code <5ms} before each test class
 * while keeping {@code revinfo_seq} monotonic across the fork.
 *
 * <p>In addition, for {@code @SpringBootTest} classes whose {@code @DynamicPropertySource} method
 * registers the exact standard property set from
 * {@link IntegrationTestSupport#registerPostgresTestProperties}, this extension normalizes the
 * {@code DynamicPropertiesContextCustomizer} method reference so Spring's
 * {@code DefaultContextCache} reuses the cached {@link ApplicationContext} across standard classes
 * in the same worker fork instead of booting a fresh context per class.
 */
public class SharedPostgresContainerExtension implements BeforeAllCallback, AfterAllCallback {

    private static final String EXPECTED_IMAGE = "postgres:18-alpine";
    private static final String EXPECTED_DB = "jtoye_test";
    private static final String EXPECTED_USER = "test";
    private static final String EXPECTED_PASS = "test";
    private static final String DYNAMIC_PROPERTIES_CUSTOMIZER_CLASS =
            "org.springframework.test.context.support.DynamicPropertiesContextCustomizer";

    /**
     * Classes that perform partial/out-of-order manual Flyway migrations, pre-Flyway
     * {@code ALTER DEFAULT PRIVILEGES} role setup, or rely on {@code ddl-auto=create-drop}
     * instead of {@link IntegrationTestSupport#registerPostgresTestProperties} and therefore must
     * keep a dedicated per-class container.
     */
    private static final Set<String> EXCLUDED_CLASSES = Set.of(
            "uk.jtoye.core.customer.CustomerOriginShopScopeIntegrationTest",
            "uk.jtoye.core.media.MediaRedriveControllerTest",
            "uk.jtoye.core.media.MediaSweepTenantScopeIntegrationTest"
    );

    private static final Set<String> DROP_SHOP_NAME_UNIQUE_INDEX_CLASSES = Set.of(
            "uk.jtoye.core.payment.RefundWebhookHandlingIntegrationTest",
            "uk.jtoye.core.payment.StripeWebhookIdempotencyIntegrationTest"
    );

    private static final Set<TestContext> SEEN_TEST_CONTEXTS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final Method CANONICAL_DYNAMIC_PROPERTY_METHOD;

    static {
        try {
            CANONICAL_DYNAMIC_PROPERTY_METHOD = SharedPostgresContainerExtension.class
                    .getDeclaredMethod("registerCanonicalSharedPostgresProperties", DynamicPropertyRegistry.class);
            CANONICAL_DYNAMIC_PROPERTY_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static PostgreSQLContainer<?> forkSingleton;

    static void registerCanonicalSharedPostgresProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, ensureSingletonStarted());
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getTestClass().orElse(null);
        if (testClass == null || testClass.getEnclosingClass() != null) {
            return;
        }
        if (!isTestcontainersTagged(testClass) || EXCLUDED_CLASSES.contains(testClass.getName())) {
            return;
        }
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
            PostgreSQLContainer<?> pg = ensureSingletonStarted();
            if (AnnotationSupport.isAnnotated(testClass, SpringBootTest.class)) {
                normalizeDynamicPropertiesCustomizerIfStandard(context, pg);
            }
            evictAndClearSeenContexts();
            resetDatabaseAndRoles(pg, DROP_SHOP_NAME_UNIQUE_INDEX_CLASSES.contains(testClass.getName()));
            evictAndClearSeenContexts();
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
        System.setProperty("spring.jpa.hibernate.ddl-auto", "none");
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        evictAndClearSeenContexts();
    }

    @SuppressWarnings("unchecked")
    private static void normalizeDynamicPropertiesCustomizerIfStandard(
            ExtensionContext context, PostgreSQLContainer<?> pg) {
        try {
            Method getTcm = SpringExtension.class.getDeclaredMethod(
                    "getTestContextManager", ExtensionContext.class);
            getTcm.setAccessible(true);
            TestContextManager tcm = (TestContextManager) getTcm.invoke(null, context);
            TestContext tc = tcm.getTestContext();
            SEEN_TEST_CONTEXTS.add(tc);
            Field mergedConfigField = tc.getClass().getDeclaredField("mergedConfig");
            mergedConfigField.setAccessible(true);
            MergedContextConfiguration mergedConfig =
                    (MergedContextConfiguration) mergedConfigField.get(tc);
            if (!Arrays.equals(mergedConfig.getActiveProfiles(), new String[]{"test"})) {
                return;
            }

            Set<ContextCustomizer> currentCustomizers = mergedConfig.getContextCustomizers();
            ContextCustomizer targetCustomizer = null;
            for (ContextCustomizer customizer : currentCustomizers) {
                if (DYNAMIC_PROPERTIES_CUSTOMIZER_CLASS.equals(customizer.getClass().getName())) {
                    targetCustomizer = customizer;
                    break;
                }
            }
            if (targetCustomizer == null) {
                return;
            }
            Field methodsField = targetCustomizer.getClass().getDeclaredField("methods");
            methodsField.setAccessible(true);
            Set<Method> methods = (Set<Method>) methodsField.get(targetCustomizer);
            if (methods == null || methods.isEmpty()) {
                return;
            }

            Map<String, String> expectedProps = new LinkedHashMap<>();
            IntegrationTestSupport.registerPostgresTestProperties(
                    (name, supplier) -> expectedProps.put(name, String.valueOf(supplier.get())),
                    pg);

            Map<String, String> actualProps = new LinkedHashMap<>();
            DynamicPropertyRegistry actualRegistry =
                    (name, supplier) -> actualProps.put(name, String.valueOf(supplier.get()));
            for (Method m : methods) {
                m.setAccessible(true);
                m.invoke(null, actualRegistry);
            }
            expectedProps.remove("spring.datasource.hikari.minimum-idle");
            actualProps.remove("spring.datasource.hikari.minimum-idle");
            if (!expectedProps.equals(actualProps)) {
                return;
            }

            Constructor<?> ctor = targetCustomizer.getClass().getDeclaredConstructor(Set.class);
            ctor.setAccessible(true);
            ContextCustomizer canonicalCustomizer =
                    (ContextCustomizer) ctor.newInstance(Set.of(CANONICAL_DYNAMIC_PROPERTY_METHOD));

            Set<ContextCustomizer> normalized = new LinkedHashSet<>();
            for (ContextCustomizer customizer : currentCustomizers) {
                if (customizer == targetCustomizer) {
                    normalized.add(canonicalCustomizer);
                } else {
                    normalized.add(customizer);
                }
            }
            Field contextCustomizersField =
                    MergedContextConfiguration.class.getDeclaredField("contextCustomizers");
            contextCustomizersField.setAccessible(true);
            contextCustomizersField.set(mergedConfig, Collections.unmodifiableSet(normalized));
        } catch (Throwable ignored) {
            // Class has non-standard or container-dependent dynamic properties; leave untouched
        }
    }

    private static void evictAndClearSeenContexts() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        synchronized (SEEN_TEST_CONTEXTS) {
            for (TestContext tc : SEEN_TEST_CONTEXTS) {
                if (!tc.hasApplicationContext()) {
                    continue;
                }
                ApplicationContext appCtx;
                try {
                    appCtx = tc.getApplicationContext();
                } catch (Exception ignored) {
                    continue;
                }
                if (appCtx instanceof ConfigurableApplicationContext cac && !cac.isActive()) {
                    continue;
                }
                try {
                    appCtx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)
                            .values()
                            .forEach(ScheduledAnnotationBeanPostProcessor::destroy);
                } catch (Exception ignored) {
                }
                try {
                    appCtx.getBeansOfType(CacheManager.class)
                            .values()
                            .forEach(cm -> cm.getCacheNames().forEach(name -> {
                                Cache cache = cm.getCache(name);
                                if (cache != null) {
                                    cache.clear();
                                }
                            }));
                } catch (Exception ignored) {
                }
                try {
                    appCtx.getBeansOfType(HikariDataSource.class)
                            .values()
                            .forEach(ds -> {
                                if (ds.getHikariPoolMXBean() != null) {
                                    ds.getHikariPoolMXBean().softEvictConnections();
                                }
                            });
                } catch (Exception ignored) {
                }
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

    private static void resetDatabaseAndRoles(PostgreSQLContainer<?> pg, boolean dropShopNameUniqueIndex) {
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
            if (dropShopNameUniqueIndex) {
                stmt.execute("DROP INDEX IF EXISTS public.idx_shops_tenant_name");
            } else {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_shops_tenant_name ON public.shops(tenant_id, name)");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reset shared PostgreSQL test container state", e);
        }
    }

    /**
     * Lightweight {@link PostgreSQLContainer} adapter that delegates to the per-JVM-fork singleton
     * container.
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
            // Kept running for the life of the worker fork JVM; reset occurs in beforeAll.
        }

        @Override
        public void close() {
            // Kept running for the life of the worker fork JVM; reset occurs in beforeAll.
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
