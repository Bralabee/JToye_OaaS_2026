package uk.jtoye.core.storage;

/**
 * The object-storage port {@link StorageService} depends on (Phase 36). It carries no SDK type in
 * any signature, so the service and its unit tests stay independent of the Azure Blob SDK, and the
 * one implementation ({@link AzureBlobObjectStore}) is the only class that touches it.
 *
 * <p>Public only so the media-package sweep test can mock it. No main-code file outside
 * {@code uk.jtoye.core.storage} may reference this type: every caller goes through
 * {@link StorageService}, which is the single owner of container routing.
 *
 * <p>Failure contract for every method: a transport failure (the store could not be reached)
 * surfaces as {@link StorageUnavailableException}; an error the store ANSWERED with (for example
 * "blob not found" on {@link #get}) propagates as the SDK's own service exception; and any other
 * runtime exception (a programming, validation or identity error) propagates unchanged, never
 * disguised as unavailability.
 */
public interface BlobObjectStore {

    /** Anonymous-access level of a container. {@code PRIVATE} means no anonymous access at all. */
    enum ContainerAccess {
        /** No anonymous read and no anonymous list. */
        PRIVATE,
        /** Anonymous read of a blob by its URL; the container itself cannot be listed (#626). */
        BLOB,
        /** Anonymous read AND anonymous list of the whole container. Never used for media. */
        CONTAINER
    }

    /**
     * Store {@code bytes} at {@code key}, overwriting any existing blob.
     *
     * @param cacheControl the {@code Cache-Control} to stamp on the blob, or {@code null} for none
     */
    void put(String container, String key, byte[] bytes, String contentType, String cacheControl);

    /**
     * Store {@code bytes} at {@code key} only if no blob exists there (an atomic create-only
     * write, not a check followed by a write).
     *
     * @return {@code true} if this call created the blob; {@code false} if one was already present
     */
    boolean putIfAbsent(String container, String key, byte[] bytes, String contentType, String cacheControl);

    /** Read the whole blob. A missing blob is a service error, not {@code null}. */
    byte[] get(String container, String key);

    /**
     * Delete the blob if it exists.
     *
     * @return {@code true} if a blob was removed; {@code false} if there was nothing to remove
     */
    boolean deleteIfExists(String container, String key);

    boolean containerExists(String container);

    ContainerAccess containerAccess(String container);

    /** Create the container at {@code access} if it does not exist; an existing one is left as is. */
    void createContainerIfMissing(String container, ContainerAccess access);
}
