package uk.jtoye.core.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uk.jtoye.core.storage.BlobObjectStore.ContainerAccess;

/**
 * Boot-time storage probe (Phase 36, decisions D-02 and D-08): a misconfigured object store stops
 * the application at startup, not at the first upload.
 *
 * <p>Checked on every boot, in every runtime:
 * <ol>
 *   <li>both containers exist;</li>
 *   <li>the public container is access level {@code blob}: anonymous read of a blob by its URL,
 *       and NO anonymous listing. Level {@code container} is refused as a #626 regression;</li>
 *   <li>the quarantine container is private (no anonymous access at all).</li>
 * </ol>
 * With {@code storage.blob.create-containers} on (emulator mode only; the shape rules refuse it
 * under workload identity) the containers are first created with exactly those levels. An
 * EXISTING container is never "repaired": a wrong level is refused and the container named, because
 * silently changing an access level in place is exactly how #626 would be reintroduced unnoticed.
 *
 * <p><b>Why {@link ApplicationStartedEvent} and not {@code ApplicationReadyEvent}</b> (the
 * {@code DatabaseConfigurationValidator} precedent). Spring Boot runs every
 * {@code ApplicationRunner} BETWEEN the two events, and the dev {@code DemoDataSeeder} is one. On a
 * fresh Azurite volume a ready-time probe would create the containers only after the seeder's first
 * writes had already failed with "container not found", so a first boot in compose or the hybrid
 * runtime would silently seed no images. Running at started time also means a misconfigured store
 * stops the application before any runner writes to it. Readiness has not flipped to accepting
 * traffic yet at either event, so no request is served by an unchecked store.
 *
 * <p>Gated on {@code storage.blob.validate-on-startup} (default {@code true}, and a literal
 * {@code true} in application.yml that no env var maps), NOT on a profile: the switch exists only
 * for test contexts that never touch storage.
 *
 * <p><b>Known limitation.</b> The container's access-level PROPERTY can read {@code blob} while
 * anonymous reads still fail, when the storage ACCOUNT disallows public blob access
 * ({@code AllowBlobPublicAccess=false}). That account setting is not visible to a data-plane
 * identity; it is covered by the Phase 29 read-back in docs/runbooks/azure-blob-provisioning.md.
 */
@Component
@ConditionalOnProperty(prefix = "storage.blob", name = "validate-on-startup", havingValue = "true", matchIfMissing = true)
public class StorageStartupValidator {
    private static final Logger log = LoggerFactory.getLogger(StorageStartupValidator.class);

    private final BlobObjectStore store;
    private final StorageProperties properties;

    public StorageStartupValidator(BlobObjectStore store, StorageProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * Runs the probe. Throws {@link StorageConfigurationException} on any failure, including an
     * unreachable store (wrapped, cause preserved), which fails the application's startup.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void validate() {
        StorageProperties.Blob blob = properties.getBlob();
        String publicContainer = blob.getPublicContainer();
        String quarantineContainer = blob.getQuarantineContainer();
        try {
            if (blob.isCreateContainers()) {
                store.createContainerIfMissing(publicContainer, ContainerAccess.BLOB);
                store.createContainerIfMissing(quarantineContainer, ContainerAccess.PRIVATE);
            }

            requireExists(publicContainer, "public");
            requireExists(quarantineContainer, "quarantine");

            ContainerAccess publicAccess = store.containerAccess(publicContainer);
            if (publicAccess == ContainerAccess.CONTAINER) {
                throw new StorageConfigurationException("Public container '" + publicContainer
                        + "' allows anonymous LIST (access level container) -- #626 requires access level blob. "
                        + "Change the container's level; the application never repairs an existing container.");
            }
            if (publicAccess != ContainerAccess.BLOB) {
                throw new StorageConfigurationException("Public container '" + publicContainer + "' is access level "
                        + publicAccess + "; it must be blob so image URLs load anonymously (#626 forbids container)");
            }

            ContainerAccess quarantineAccess = store.containerAccess(quarantineContainer);
            if (quarantineAccess != ContainerAccess.PRIVATE) {
                throw new StorageConfigurationException("Quarantine container '" + quarantineContainer
                        + "' is access level " + quarantineAccess + "; it must be private (raw uploads are "
                        + "never anonymously readable)");
            }
        } catch (StorageConfigurationException e) {
            log.error("Storage startup check FAILED: {}", e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.error("Storage startup check FAILED: the object store could not be checked", e);
            throw new StorageConfigurationException("storage startup check failed: " + e.getMessage(), e);
        }

        // Mode, endpoint HOST and container names only; never the connection string (T-36-03).
        log.info("Storage startup check passed: mode={}, endpointHost={}, publicContainer={} (blob), "
                        + "quarantineContainer={} (private), createContainers={}",
                blob.getAuthMode(), blob.endpointHostForLog(), publicContainer, quarantineContainer,
                blob.isCreateContainers());
    }

    private void requireExists(String container, String role) {
        if (!store.containerExists(container)) {
            throw new StorageConfigurationException("The " + role + " container '" + container + "' does not exist. "
                    + "Provision it (staging/production) or set storage.blob.create-containers=true "
                    + "(emulator only, STORAGE_CREATE_CONTAINERS).");
        }
    }
}
