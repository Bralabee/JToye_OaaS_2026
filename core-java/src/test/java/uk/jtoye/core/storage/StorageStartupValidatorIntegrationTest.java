package uk.jtoye.core.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.testsupport.AzuriteTestSupport;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The boot-time storage probe (Phase 36, D-02 and D-08) against a real, digest-pinned Azurite.
 *
 * <p>Every arm creates its OWN containers with deliberately right or wrong access levels, so the
 * arms are independent inside one emulator. The probe is called directly for the level rules, and
 * through a real {@link org.springframework.boot.SpringApplication} for the two properties that only
 * a real startup can show: a bad level stops the application from starting, and the containers
 * exist before any {@link ApplicationRunner} (the dev demo seeder is one) writes to them.
 *
 * <p>The Spring boots load no config file ({@code spring.config.location} points at an optional,
 * absent location), so the only storage settings are the ones each arm passes.
 */
@Testcontainers
@Tag("testcontainers")
class StorageStartupValidatorIntegrationTest {

    @Container
    static final AzuriteContainer AZURITE = AzuriteTestSupport.newAzurite();

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** An administrative store used only to arrange and inspect containers. */
    private static BlobObjectStore admin;

    @BeforeAll
    static void adminStore() {
        StorageConfig config = new StorageConfig();
        admin = config.blobObjectStore(config.blobServiceClient(AzuriteTestSupport.storageProperties(AZURITE)));
    }

    private static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0xffffff));
    }

    private static StorageProperties properties(String publicContainer, String quarantineContainer, boolean createContainers) {
        StorageProperties properties = AzuriteTestSupport.storageProperties(AZURITE);
        properties.getBlob().setPublicContainer(publicContainer);
        properties.getBlob().setQuarantineContainer(quarantineContainer);
        properties.getBlob().setPublicUrl(AzuriteTestSupport.blobEndpoint(AZURITE) + "/" + publicContainer);
        properties.getBlob().setCreateContainers(createContainers);
        return properties;
    }

    private static StorageStartupValidator validator(StorageProperties properties) {
        return new StorageStartupValidator(admin, properties);
    }

    // ---- the level rules, probe called directly -----------------------------------------------

    @Test
    @DisplayName("public BLOB + quarantine PRIVATE passes")
    void correctLayoutPasses() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.BLOB);
        admin.createContainerIfMissing(quar, ContainerAccess.PRIVATE);

        assertDoesNotThrow(() -> validator(properties(pub, quar, false)).validate());
    }

    @Test
    @DisplayName("public container at level CONTAINER (anonymous LIST) is refused as a #626 regression")
    void publicContainerLevelIsRefused() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.CONTAINER);
        admin.createContainerIfMissing(quar, ContainerAccess.PRIVATE);

        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> validator(properties(pub, quar, false)).validate());
        assertThat(e.getMessage()).contains("#626").contains(pub).contains("anonymous LIST");
    }

    @Test
    @DisplayName("quarantine container at level BLOB (anonymous read) is refused")
    void readableQuarantineIsRefused() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.BLOB);
        admin.createContainerIfMissing(quar, ContainerAccess.BLOB);

        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> validator(properties(pub, quar, false)).validate());
        assertThat(e.getMessage()).contains(quar).contains("private");
    }

    @Test
    @DisplayName("a missing container with create-containers false is refused, naming it")
    void missingContainerIsRefused() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.BLOB);

        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> validator(properties(pub, quar, false)).validate());
        assertThat(e.getMessage()).contains(quar).contains("does not exist");
        assertThat(admin.containerExists(quar)).as("the probe must not create anything when create-containers is off").isFalse();
    }

    @Test
    @DisplayName("create-containers on an empty emulator creates public BLOB and quarantine PRIVATE, then passes")
    void createContainersCreatesWithTheRightLevels() {
        String pub = unique("pub");
        String quar = unique("quar");

        assertDoesNotThrow(() -> validator(properties(pub, quar, true)).validate());

        assertThat(admin.containerExists(pub)).isTrue();
        assertThat(admin.containerExists(quar)).isTrue();
        assertThat(admin.containerAccess(pub)).isEqualTo(ContainerAccess.BLOB);
        assertThat(admin.containerAccess(quar)).isEqualTo(ContainerAccess.PRIVATE);
    }

    @Test
    @DisplayName("create-containers never repairs an existing container's level: it refuses and leaves it as found")
    void existingWrongLevelIsNotRepaired() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.CONTAINER);

        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> validator(properties(pub, quar, true)).validate());
        assertThat(e.getMessage()).contains("#626").contains(pub);
        assertThat(admin.containerAccess(pub)).isEqualTo(ContainerAccess.CONTAINER);
    }

    @Test
    @DisplayName("an unreachable store fails the probe with a StorageConfigurationException, cause preserved")
    void unreachableStoreIsATypedStartupFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        StorageProperties properties = properties(unique("pub"), unique("quar"), false);
        properties.getBlob().setConnectionString("AccountName=devstoreaccount1;AccountKey=eA==;BlobEndpoint=http://127.0.0.1:"
                + closedPort + "/devstoreaccount1");
        properties.getBlob().setMaxTries(1);
        properties.getBlob().setTryTimeoutSeconds(2);
        StorageConfig config = new StorageConfig();
        BlobObjectStore unreachable = config.blobObjectStore(config.blobServiceClient(properties));

        StorageConfigurationException e = assertThrows(StorageConfigurationException.class,
                () -> new StorageStartupValidator(unreachable, properties).validate());
        assertThat(e).hasCauseInstanceOf(StorageUnavailableException.class);
        assertThat(e.getMessage()).contains("storage startup check failed");
    }

    // ---- through a real SpringApplication ------------------------------------------------------

    private static String[] bootArgs(String pub, String quar, boolean createContainers) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=optional:classpath:/storage-probe-no-config/",
                "--spring.main.banner-mode=off",
                "--storage.blob.auth-mode=connection-string",
                "--storage.blob.connection-string=" + AZURITE.getConnectionString(),
                "--storage.blob.public-container=" + pub,
                "--storage.blob.quarantine-container=" + quar,
                "--storage.blob.public-url=" + AzuriteTestSupport.blobEndpoint(AZURITE) + "/" + pub,
                "--storage.blob.create-containers=" + createContainers));
        return args.toArray(String[]::new);
    }

    @Test
    @DisplayName("SpringApplication fails to start when the public container is at level CONTAINER")
    void applicationFailsToStartOnABadLevel() {
        String pub = unique("pub");
        String quar = unique("quar");
        admin.createContainerIfMissing(pub, ContainerAccess.CONTAINER);
        admin.createContainerIfMissing(quar, ContainerAccess.PRIVATE);

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(StorageConfig.class, StorageStartupValidator.class)
                .web(WebApplicationType.NONE)
                .run(bootArgs(pub, quar, false))
                .close());

        assertThat(thrown).isInstanceOf(StorageConfigurationException.class);
        assertThat(thrown.getMessage()).contains("#626");
    }

    /** Stands in for the dev demo seeder: an ApplicationRunner that writes to the public container. */
    @Configuration(proxyBeanMethods = false)
    static class RunnerWritesToThePublicContainer {
        @Bean
        ApplicationRunner writesAtStartup(BlobObjectStore store, StorageProperties properties) {
            return args -> store.put(properties.getBlob().getPublicContainer(), "runner/probe.txt",
                    "written by a runner".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        }
    }

    @Test
    @DisplayName("on an empty emulator the containers exist before ApplicationRunners run (the demo seeder's first write lands)")
    void containersExistBeforeRunners() {
        String pub = unique("pub");
        String quar = unique("quar");

        Throwable thrown = catchThrowable(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(StorageConfig.class,
                    StorageStartupValidator.class, RunnerWritesToThePublicContainer.class)
                    .web(WebApplicationType.NONE)
                    .run(bootArgs(pub, quar, true))) {
                // started and runners completed
            }
        });

        assertThat(thrown).as("startup, including the runner's first write").isNull();
        assertThat(new String(admin.get(pub, "runner/probe.txt"), StandardCharsets.UTF_8)).isEqualTo("written by a runner");
        assertThat(admin.containerAccess(pub)).isEqualTo(ContainerAccess.BLOB);
        assertThat(admin.containerAccess(quar)).isEqualTo(ContainerAccess.PRIVATE);
    }

    // ---- the switch -----------------------------------------------------------------------------

    @Test
    @DisplayName("the probe bean exists by default and is absent only with storage.blob.validate-on-startup=false")
    void probeBeanFollowsTheProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(StorageConfig.class, StorageStartupValidator.class)
                .withPropertyValues("storage.blob.connection-string=UseDevelopmentStorage=true");

        runner.run(context -> assertThat(context).hasSingleBean(StorageStartupValidator.class));
        runner.withPropertyValues("storage.blob.validate-on-startup=true")
                .run(context -> assertThat(context).hasSingleBean(StorageStartupValidator.class));
        runner.withPropertyValues("storage.blob.validate-on-startup=false")
                .run(context -> assertThat(context).doesNotHaveBean(StorageStartupValidator.class));
    }
}
