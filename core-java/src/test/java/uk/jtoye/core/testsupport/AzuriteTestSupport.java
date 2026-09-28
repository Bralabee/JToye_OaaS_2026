package uk.jtoye.core.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.utility.DockerImageName;
import uk.jtoye.core.storage.BlobObjectStore;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;
import uk.jtoye.core.storage.StorageProperties;

/**
 * Shared, digest-pinned Azurite fixture for every Blob integration test (Phase 36), modelled on
 * {@link IntegrationTestSupport}: static helpers only, and each test class still declares its own
 * static {@code @Container} (the repo has no singleton-container fixture).
 *
 * <p><b>No emulator key literal anywhere.</b> {@link AzuriteContainer#getConnectionString()} builds
 * the connection string at runtime from the constant inside the Testcontainers jar, so the
 * well-known development key never appears in this repository (gitleaks flags the literal as
 * {@code generic-api-key}; 36-RESEARCH Pitfall 11).
 */
public final class AzuriteTestSupport {

    /**
     * Azurite 3.37.0 targets service version 2026-06-06, the default of azure-storage-blob 12.35.1.
     * The digest is the manifest-list digest recorded in 36-RESEARCH.md.
     */
    public static final String AZURITE_IMAGE =
            "mcr.microsoft.com/azure-storage/azurite:3.37.0@sha256:830430c1da1a2d537e08f3e6764dd1f5ae00cf0346bcaf625b968ec3f0971fd5";

    public static final String PUBLIC_CONTAINER = "jtoye-images";
    public static final String QUARANTINE_CONTAINER = "jtoye-quarantine";

    /** The Blob service port inside the Azurite container. */
    private static final int BLOB_PORT = 10000;

    /** Azurite's fixed development account name (the name, not a credential). */
    private static final String DEV_ACCOUNT = "devstoreaccount1";

    private AzuriteTestSupport() {
    }

    /**
     * A new (unstarted) Azurite container for {@link #AZURITE_IMAGE}.
     *
     * <p>The API-version check is skipped through the environment variable rather than a command
     * flag: {@code AzuriteContainer.configure()} rebuilds the command and would override a later
     * {@code withCommand}. Skipping decouples SDK bumps from the Azurite pin; the trade is that a
     * genuinely unsupported newer feature fails at call time instead of at the version check.
     */
    public static AzuriteContainer newAzurite() {
        // A tag+digest reference parses with the tag inside the repository part, so Testcontainers'
        // image-compatibility check does not recognise it as Azurite; declare it explicitly rather
        // than drop the tag or the digest from the pin.
        DockerImageName image = DockerImageName.parse(AZURITE_IMAGE)
                .asCompatibleSubstituteFor("mcr.microsoft.com/azure-storage/azurite");
        return new AzuriteContainer(image)
                .withEnv("AZURITE_SKIP_API_VERSION_CHECK", "true");
    }

    /** {@code http://<host>:<mapped blob port>/devstoreaccount1} — the anonymous-HTTP base URL. */
    public static String blobEndpoint(AzuriteContainer azurite) {
        return "http://" + azurite.getHost() + ":" + azurite.getMappedPort(BLOB_PORT) + "/" + DEV_ACCOUNT;
    }

    /** Connection-string-mode {@link StorageProperties} pointing at {@code azurite}. */
    public static StorageProperties storageProperties(AzuriteContainer azurite) {
        StorageProperties properties = new StorageProperties();
        StorageProperties.Blob blob = properties.getBlob();
        blob.setAuthMode("connection-string");
        blob.setConnectionString(azurite.getConnectionString());
        blob.setPublicContainer(PUBLIC_CONTAINER);
        blob.setQuarantineContainer(QUARANTINE_CONTAINER);
        blob.setPublicUrl(blobEndpoint(azurite) + "/" + PUBLIC_CONTAINER);
        return properties;
    }

    /**
     * Create the public container at {@code publicAccess} and the quarantine container PRIVATE.
     * Tests pass {@link ContainerAccess#BLOB} for the real layout; a break arm passes
     * {@link ContainerAccess#CONTAINER} to prove the #626 listing test can fail.
     */
    public static void createContainers(BlobObjectStore store, ContainerAccess publicAccess) {
        store.createContainerIfMissing(PUBLIC_CONTAINER, publicAccess);
        store.createContainerIfMissing(QUARANTINE_CONTAINER, ContainerAccess.PRIVATE);
    }

    /** Registers the {@code storage.blob.*} properties for a Spring integration test. */
    public static void registerAzuriteProperties(DynamicPropertyRegistry registry, AzuriteContainer azurite) {
        registry.add("storage.blob.auth-mode", () -> "connection-string");
        registry.add("storage.blob.connection-string", azurite::getConnectionString);
        registry.add("storage.blob.public-container", () -> PUBLIC_CONTAINER);
        registry.add("storage.blob.quarantine-container", () -> QUARANTINE_CONTAINER);
        registry.add("storage.blob.public-url", () -> blobEndpoint(azurite) + "/" + PUBLIC_CONTAINER);
        registry.add("storage.blob.create-containers", () -> "true");
    }
}
